// Cloud handwriting-recognition fallback.
//
// The app recognizes ink ON DEVICE first (ML Kit digital ink, vector
// strokes). Only when that comes back uncertain — and only during an
// explicit user action (RECOGNIZE/CHECK) — does it POST a small PNG render
// of the selected strokes here. Gemini multimodal transcribes it into the
// same structured line format the local recognizer produces.
//
// Never guesses: the model is instructed to return uncertain=true rather
// than invent a reading.

import { extractJson } from './ai.js';

const GEMINI_BASE = 'https://generativelanguage.googleapis.com/v1beta';
const GEMINI_DEFAULT_MODEL = 'gemini-flash-latest';

const PROMPT = [
  'Transcribe the handwritten mathematics in this image, line by line.',
  'Respond with STRICT JSON only, no prose:',
  '{"lines":[{"index":0,"text":"..."}],"confidence":0.0,"uncertain":false}',
  'Rules:',
  '- Use ASCII math notation: x^2, ->, /, *, sqrt(), standard symbols like = + -.',
  '- One entry per visual line, top to bottom, index starting at 0.',
  '- confidence is your overall reading confidence from 0 to 1.',
  '- If any line is genuinely unreadable, OMIT it and lower confidence.',
  '- If the whole image is unreadable set uncertain=true with empty lines.',
  '- NEVER guess. An honest uncertain=true is always better than a wrong reading.'
].join('\n');

export function mockRecognize() {
  // Deterministic sample so the full pipeline is testable offline.
  return {
    status: 'ok',
    lines: [{ index: 0, text: '2x + 6 = 14', confidence: 0.9 }],
    confidence: 0.9,
    uncertain: false,
    mock: true
  };
}

export function validateRecognizeRequest(body) {
  if (!body || typeof body !== 'object') return { ok: false, error: 'Body must be a JSON object.' };
  if (typeof body.image_base64 !== 'string' || body.image_base64.length === 0) {
    return { ok: false, error: 'image_base64 (string) is required.' };
  }
  if (body.image_base64.length > 8_000_000) {
    return { ok: false, error: 'image too large.' };
  }
  const mime = body.mime || 'image/png';
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(mime)) {
    return { ok: false, error: 'mime must be image/png, image/jpeg or image/webp.' };
  }
  return { ok: true, mime };
}

function sanitizeRecognition(parsed) {
  if (!parsed || typeof parsed !== 'object') return null;
  const rawLines = Array.isArray(parsed.lines) ? parsed.lines : [];
  const lines = rawLines
    .filter((l) => l && typeof l.text === 'string' && l.text.trim().length > 0)
    .slice(0, 64)
    .map((l, i) => ({
      index: Number.isInteger(l.index) ? l.index : i,
      text: String(l.text).slice(0, 400),
      confidence: clamp01(l.confidence, 0.8)
    }));
  const confidence = clamp01(parsed.confidence, lines.length > 0 ? 0.8 : 0);
  return {
    status: 'ok',
    lines,
    confidence,
    uncertain: Boolean(parsed.uncertain) || lines.length === 0
  };
}

function clamp01(v, dflt) {
  const n = Number(v);
  if (!Number.isFinite(n)) return dflt;
  return Math.min(1, Math.max(0, n));
}

async function geminiCall(env, model, mime, imageBase64) {
  return fetch(`${GEMINI_BASE}/models/${encodeURIComponent(model)}:generateContent`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-goog-api-key': env.GEMINI_API_KEY
    },
    body: JSON.stringify({
      contents: [{
        role: 'user',
        parts: [
          { text: PROMPT },
          { inline_data: { mime_type: mime, data: imageBase64 } }
        ]
      }],
      generationConfig: {
        responseMimeType: 'application/json',
        temperature: 0,
        maxOutputTokens: 2000
      }
    }),
    signal: AbortSignal.timeout(60000)
  });
}

async function discoverFlashModel(env) {
  const res = await fetch(`${GEMINI_BASE}/models?pageSize=200`, {
    headers: { 'x-goog-api-key': env.GEMINI_API_KEY },
    signal: AbortSignal.timeout(30000)
  });
  if (!res.ok) return null;
  const data = await res.json();
  const models = (data.models || [])
    .filter((m) => (m.supportedGenerationMethods || []).includes('generateContent'))
    .map((m) => (m.name || '').replace(/^models\//, ''));
  return (
    models.find((n) => n.includes('flash') && n.includes('latest')) ||
    models.find((n) => n.includes('flash') && !n.includes('lite')) ||
    models.find((n) => n.includes('flash')) ||
    models[0] || null
  );
}

export async function aiRecognize(env, body, mime) {
  if (!env.GEMINI_API_KEY) {
    return {
      status: 'error',
      message: 'Cloud recognition needs GEMINI_API_KEY on the backend.'
    };
  }
  try {
    let model = env.GEMINI_MODEL || GEMINI_DEFAULT_MODEL;
    let res = await geminiCall(env, model, mime, body.image_base64);
    if (res.status === 404) {
      const fallback = await discoverFlashModel(env);
      if (fallback && fallback !== model) {
        model = fallback;
        res = await geminiCall(env, model, mime, body.image_base64);
      }
    }
    if (!res.ok) {
      return { status: 'error', message: `Recognition service error (${res.status}).` };
    }
    const data = await res.json();
    const text =
      data.candidates?.[0]?.content?.parts?.map((p) => p.text).join('') ?? '';
    const sanitized = sanitizeRecognition(extractJson(text));
    return sanitized || { status: 'error', message: 'Recognition returned malformed output.' };
  } catch (e) {
    return { status: 'error', message: 'Recognition service unreachable.' };
  }
}
