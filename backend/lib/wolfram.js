// MathVerificationProvider: Wolfram|Alpha implementation.
// Replaceable: anything exposing verify(expressionA, expressionB) works.

const WOLFRAM_RESULT_API = 'https://api.wolframalpha.com/v1/result';

/**
 * Ask Wolfram whether two expressions are equivalent.
 * Returns true / false / null (unknown or unavailable).
 */
export async function wolframEquivalent(appId, exprA, exprB) {
  if (!appId) return null;
  try {
    const query = `Simplify[(${exprA}) - (${exprB})] == 0`;
    const url = `${WOLFRAM_RESULT_API}?appid=${encodeURIComponent(appId)}&i=${encodeURIComponent(query)}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (!res.ok) return null;
    const text = (await res.text()).trim().toLowerCase();
    if (text.includes('true') || text === 'yes') return true;
    if (text.includes('false') || text === 'no') return false;
    return null;
  } catch {
    return null;
  }
}

/** Ask Wolfram for a short answer to a math question (used by SOLVE). */
export async function wolframShortAnswer(appId, question) {
  if (!appId) return null;
  try {
    const url = `${WOLFRAM_RESULT_API}?appid=${encodeURIComponent(appId)}&i=${encodeURIComponent(question)}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (!res.ok) return null;
    return (await res.text()).trim();
  } catch {
    return null;
  }
}
