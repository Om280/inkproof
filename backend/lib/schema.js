// Strict request/response schema for CHECK MY WORK / SOLVE.
// The Android app owns the UI; the AI only ever supplies this structure.

export const CHECK_STATUSES = [
  'correct', 'incorrect', 'incomplete', 'unclear', 'unsupported', 'error'
];

export const STEP_STATUSES = [
  'correct', 'incorrect', 'dependent_on_previous_error', 'unclear', 'skipped'
];

/** Validate an incoming CheckRequest. Returns { ok, error }. */
export function validateCheckRequest(body) {
  if (!body || typeof body !== 'object') return bad('Request body must be JSON.');
  if (typeof body.question_id !== 'string' || !body.question_id) {
    return bad('question_id is required.');
  }
  if (typeof body.question_text !== 'string') return bad('question_text is required.');
  if (!body.question_text.trim()) return bad('question_text is empty.');
  if (!Array.isArray(body.solution_lines)) return bad('solution_lines must be an array.');
  for (const line of body.solution_lines) {
    if (typeof line.text !== 'string') return bad('each solution line needs text.');
  }
  if (body.question_text.length > 4000) return bad('question_text too long.');
  if (body.solution_lines.length > 200) return bad('too many solution lines.');
  return { ok: true };
}

function bad(error) {
  return { ok: false, error };
}

/**
 * Normalize + validate a CheckResponse object (typically produced by an AI).
 * Anything malformed is coerced into a safe structure or rejected.
 * Returns null when the object is unusable.
 */
export function sanitizeCheckResponse(obj) {
  if (!obj || typeof obj !== 'object') return null;
  if (!CHECK_STATUSES.includes(obj.status)) return null;

  const steps = Array.isArray(obj.steps)
    ? obj.steps
        .filter((s) => s && typeof s === 'object')
        .map((s, i) => ({
          step_id: typeof s.step_id === 'string' ? s.step_id : `step_${i + 1}`,
          status: STEP_STATUSES.includes(s.status) ? s.status : 'unclear',
          expression: typeof s.expression === 'string' ? s.expression : '',
          explanation: typeof s.explanation === 'string' ? s.explanation : undefined,
          hint: typeof s.hint === 'string' ? s.hint : undefined
        }))
    : [];

  return {
    status: obj.status,
    confidence: clamp01(obj.confidence),
    question_echo: strOrUndef(obj.question_echo),
    first_error_step: strOrUndef(obj.first_error_step),
    steps,
    hints: Array.isArray(obj.hints) ? obj.hints.filter((h) => typeof h === 'string').slice(0, 5) : [],
    final_answer: strOrUndef(obj.final_answer),
    full_solution: strOrUndef(obj.full_solution),
    summary: strOrUndef(obj.summary),
    message: strOrUndef(obj.message),
    verified_by: strOrUndef(obj.verified_by)
  };
}

function clamp01(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return 0;
  return Math.min(1, Math.max(0, n));
}

function strOrUndef(v) {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

export function errorResponse(message) {
  return { status: 'error', confidence: 0, steps: [], hints: [], message };
}
