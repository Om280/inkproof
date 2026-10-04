// Deterministic mock provider — mirrors the Android MockCheckProvider.
// It NEVER substitutes a demo problem: only the submitted request is used.

export function mockCheck(request) {
  const question = request.question_text;
  const lines = request.solution_lines || [];

  if (lines.length === 0) {
    return {
      status: 'incomplete',
      confidence: 1,
      question_echo: question,
      steps: [],
      hints: [],
      summary: 'No solution steps found.',
      message: 'Write your solution in the solution area, then check again.',
      verified_by: 'mock'
    };
  }

  const q = question.toLowerCase();
  let scenario;
  if (q.includes('mock:correct')) scenario = 'correct';
  else if (q.includes('mock:incorrect')) scenario = 'incorrect';
  else if (q.includes('mock:incomplete')) scenario = 'incomplete';
  else if (q.includes('mock:unclear')) scenario = 'unclear';
  else {
    const h = hash(question) ^ lines.length;
    scenario = ['correct', 'incorrect', 'incomplete', 'unclear'][h % 4];
  }

  const steps = lines.map((line, i) => ({
    step_id: `step_${i + 1}`,
    status: 'correct',
    expression: line.text,
    explanation: 'This step follows from the previous line.'
  }));

  switch (scenario) {
    case 'correct':
      return {
        status: 'correct',
        confidence: 0.97,
        question_echo: question,
        steps,
        hints: [],
        summary: 'Every step checks out. Nicely done.',
        final_answer: steps.at(-1)?.expression,
        verified_by: 'mock'
      };
    case 'incorrect': {
      const errorIndex = Math.max(steps.length > 1 ? 1 : 0, Math.floor(steps.length / 2));
      const marked = steps.map((s, i) => {
        if (i < errorIndex) return s;
        if (i === errorIndex) {
          return {
            ...s,
            status: 'incorrect',
            explanation: 'The operation applied here does not preserve equality.',
            hint: 'Compare this line carefully with the one above it.'
          };
        }
        return {
          ...s,
          status: 'dependent_on_previous_error',
          explanation: "This step builds on the earlier mistake, so it can't be marked correct on its own."
        };
      });
      return {
        status: 'incorrect',
        confidence: 0.95,
        question_echo: question,
        first_error_step: `step_${errorIndex + 1}`,
        steps: marked,
        hints: [
          `Look at what changed between step ${errorIndex} and step ${errorIndex + 1}.`,
          'One side of the equation was changed without applying the same change to the other side.',
          `Undo the operation in step ${errorIndex + 1} and redo it on both sides.`
        ],
        summary: `First mistake found at step ${errorIndex + 1}.`,
        full_solution: `Mock full solution: rework from step ${errorIndex + 1} applying each operation to both sides.`,
        verified_by: 'mock'
      };
    }
    case 'incomplete':
      return {
        status: 'incomplete',
        confidence: 0.9,
        question_echo: question,
        steps,
        hints: ["You've set things up correctly — keep going to isolate the unknown."],
        summary: "The work so far is fine, but the problem isn't finished.",
        verified_by: 'mock'
      };
    default:
      return {
        status: 'unclear',
        confidence: 0.3,
        question_echo: question,
        steps: [],
        hints: [],
        summary: "I couldn't confidently read this step.",
        message: "I couldn't confidently read part of the handwriting. Edit the transcription or select the work again — InkProof never guesses.",
        verified_by: 'mock'
      };
  }
}

export function mockSolve(request) {
  const question = request.question_text;
  return {
    status: 'correct',
    confidence: 0.9,
    question_echo: question,
    steps: [],
    hints: [],
    summary: `Mock worked solution for: ${question}`,
    full_solution: `Mock mode: a fully worked solution for "${question}" would appear here, step by step.`,
    final_answer: 'mock answer',
    verified_by: 'mock'
  };
}

function hash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}
