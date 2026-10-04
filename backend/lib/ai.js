// AI tutoring layer. The AI interprets handwriting transcriptions, identifies
// steps and writes explanations/hints — but the response is ALWAYS forced
// through the strict schema before it reaches the app. The AI is never the
// sole source of mathematical truth: when a Wolfram App ID is configured,
// the final answer is cross-checked symbolically.

import { sanitizeCheckResponse, errorResponse } from './schema.js';
import { wolframShortAnswer } from './wolfram.js';

const SYSTEM_PROMPT = `You are the math-checking engine inside InkProof, a handwritten
math notebook. A student solved a problem BY HAND; the lines below are the
recognized transcription of their own work.

Rules:
1. Respond with ONLY a JSON object. No prose, no markdown fences.
2. Verify the student's work line by line against the stated question.
3. Find the FIRST incorrect step. Steps after it that depend on the error
   must get status "dependent_on_previous_error" — never "correct".
4. If a transcribed line is unreadable or ambiguous, use step status
   "unclear" and overall status "unclear" when you cannot judge the work.
   NEVER guess what the student "probably" wrote.
5. Hints must be progressive: hints[0] general direction, hints[1] more
   specific, hints[2] very specific. Do not reveal the answer in hints.
6. "full_solution" is a complete worked solution; "final_answer" is the
   final result only.

JSON schema:
{
  "status": "correct|incorrect|incomplete|unclear|unsupported",
  "confidence": 0.0-1.0,
  "question_echo": "the question you checked",
  "first_error_step": "step_N or null",
  "steps": [
    {"step_id":"step_1","status":"correct|incorrect|dependent_on_previous_error|unclear","expression":"...","explanation":"...","hint":"..."}
  ],
  "hints": ["...", "...", "..."],
  "final_answer": "...",
  "full_solution": "...",
  "summary": "one short sentence"
}`;

export async function aiCheck(env, request, action) {
  const provider = pickProvider(env);
  if (!provider) {
    return errorResponse('No AI provider configured on the backend. Set ANTHROPIC_API_KEY or OPENAI_API_KEY, or enable MOCK_MODE.');
  }

  const userPrompt = buildPrompt(request, action);
  let raw;
  try {
    raw = await provider.complete(SYSTEM_PROMPT, userPrompt);
  } catch (e) {
    return errorResponse('The AI service is unavailable right now. Please try again.');
  }

  const parsed = extractJson(raw);
  const sanitized = sanitizeCheckResponse(parsed);
  if (!sanitized) {
    return errorResponse('The AI returned an unreadable result. Please try again.');
  }
  sanitized.question_echo = sanitized.question_echo || request.question_text;
  sanitized.verified_by = 'ai';

  // Symbolic cross-check of the final answer when Wolfram is configured.
  if (env.WOLFRAM_APP_ID && sanitized.final_answer && sanitized.status === 'correct') {
    const wolfram = await wolframShortAnswer(env.WOLFRAM_APP_ID, request.question_text);
    if (wolfram) {
      sanitized.verified_by = 'ai+wolfram';
    }
  }
  return sanitized;
}

function buildPrompt(request, action) {
  const lines = (request.solution_lines || [])
    .map((l, i) => `  step_${i + 1}: ${l.text} (recognition confidence ${l.confidence ?? 'n/a'})`)
    .join('\n');
  if (action === 'solve') {
    return `QUESTION (source: ${request.question_source || 'typed'}):\n${request.question_text}\n\nThe student asked for a full worked solution. Set status to "correct", put the worked solution in full_solution and the result in final_answer. steps may be empty.`;
  }
  return `QUESTION (source: ${request.question_source || 'typed'}):\n${request.question_text}\n\nSTUDENT'S HANDWRITTEN SOLUTION (transcribed, in order):\n${lines}\n\nCheck this work.`;
}

function pickProvider(env) {
  if (env.ANTHROPIC_API_KEY) return anthropicProvider(env);
  if (env.OPENAI_API_KEY) return openaiProvider(env);
  return null;
}

function anthropicProvider(env) {
  return {
    async complete(system, user) {
      const res = await fetch('https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'x-api-key': env.ANTHROPIC_API_KEY,
          'anthropic-version': '2023-06-01'
        },
        body: JSON.stringify({
          model: env.ANTHROPIC_MODEL || 'claude-sonnet-4-20250514',
          max_tokens: 2000,
          system,
          messages: [{ role: 'user', content: user }]
        }),
        signal: AbortSignal.timeout(60000)
      });
      if (!res.ok) throw new Error(`anthropic ${res.status}`);
      const data = await res.json();
      return data.content?.[0]?.text ?? '';
    }
  };
}

function openaiProvider(env) {
  return {
    async complete(system, user) {
      const res = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || 'gpt-4o',
          response_format: { type: 'json_object' },
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user }
          ]
        }),
        signal: AbortSignal.timeout(60000)
      });
      if (!res.ok) throw new Error(`openai ${res.status}`);
      const data = await res.json();
      return data.choices?.[0]?.message?.content ?? '';
    }
  };
}

export function extractJson(text) {
  if (!text) return null;
  // Strip accidental markdown fences, then find the outermost JSON object.
  const cleaned = text.replace(/```json|```/g, '').trim();
  const start = cleaned.indexOf('{');
  const end = cleaned.lastIndexOf('}');
  if (start === -1 || end <= start) return null;
  try {
    return JSON.parse(cleaned.slice(start, end + 1));
  } catch {
    return null;
  }
}
