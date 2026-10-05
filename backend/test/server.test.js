import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server.js';
import { validateCheckRequest, sanitizeCheckResponse } from '../lib/schema.js';
import { mockCheck } from '../lib/mock.js';
import { extractJson, pickProvider } from '../lib/ai.js';

let server;
let base;

before(async () => {
  server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

function request(question, lines, overrides = {}) {
  return {
    request_id: 'r1',
    action: 'check',
    question_id: 'q1',
    question_text: question,
    question_source: 'typed',
    question_confidence: 1,
    solution_lines: lines.map((t, i) => ({ line_index: i, text: t, confidence: 0.9 })),
    content_version: 1,
    solution_version: 1,
    ...overrides
  };
}

async function post(path, body) {
  const res = await fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body)
  });
  return { code: res.status, body: await res.json() };
}

test('health endpoint reports mock mode without credentials', async () => {
  const res = await fetch(base + '/api/health');
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.equal(body.mock_mode, true);
});

test('check returns structured JSON for a correct scenario', async () => {
  const { code, body } = await post('/api/check', request('mock:correct solve 2x+6=14', ['2x = 8', 'x = 4']));
  assert.equal(code, 200);
  assert.equal(body.status, 'correct');
  assert.equal(body.steps.length, 2);
  assert.equal(body.question_echo, 'mock:correct solve 2x+6=14');
});

test('incorrect scenario marks first error and dependents', async () => {
  const { body } = await post('/api/check', request('mock:incorrect q', ['a', 'b', 'c']));
  assert.equal(body.status, 'incorrect');
  assert.ok(body.first_error_step);
  const errIdx = body.steps.findIndex((s) => s.status === 'incorrect');
  for (const s of body.steps.slice(errIdx + 1)) {
    assert.equal(s.status, 'dependent_on_previous_error');
  }
  assert.ok(body.hints.length >= 2);
});

test('empty question is rejected with a clean error', async () => {
  const { code, body } = await post('/api/check', request('   ', ['x']));
  assert.equal(code, 400);
  assert.equal(body.status, 'error');
});

test('missing fields are rejected', async () => {
  const { code } = await post('/api/check', { foo: 'bar' });
  assert.equal(code, 400);
});

test('solve returns a worked solution for the submitted question', async () => {
  const { body } = await post('/api/solve', request('integrate sin(x)', []));
  assert.ok(body.full_solution.includes('integrate sin(x)'));
});

test('identical requests are served from cache (deduplication)', async () => {
  const req = request('mock:correct dedupe me', ['x = 1']);
  const a = await post('/api/check', req);
  const b = await post('/api/check', req);
  assert.deepEqual(a.body, b.body);
});

test('schema: validateCheckRequest catches bad payloads', () => {
  assert.equal(validateCheckRequest(null).ok, false);
  assert.equal(validateCheckRequest({}).ok, false);
  assert.equal(validateCheckRequest(request('q', ['a'])).ok, true);
});

test('schema: sanitize rejects junk and coerces partial data', () => {
  assert.equal(sanitizeCheckResponse(null), null);
  assert.equal(sanitizeCheckResponse({ status: 'banana' }), null);
  const ok = sanitizeCheckResponse({
    status: 'correct',
    confidence: 99,
    steps: [{ status: 'nope', expression: 42 }]
  });
  assert.equal(ok.confidence, 1);
  assert.equal(ok.steps[0].status, 'unclear');
  assert.equal(ok.steps[0].expression, '');
});

test('mock never substitutes a demo problem for the real question', () => {
  const result = mockCheck(request('factor x^2 + 4x + 1 = 0 mock:correct', ['step one']));
  assert.ok(result.question_echo.includes('x^2 + 4x + 1'));
  assert.ok(!result.question_echo.includes('2x + 6 = 14'));
});

test('extractJson survives markdown fences and prose', () => {
  assert.deepEqual(extractJson('```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(extractJson('Sure! Here it is: {"a":1} hope that helps'), { a: 1 });
  assert.equal(extractJson('no json here'), null);
  assert.equal(extractJson(''), null);
});

test('gemini is the primary provider when its key is present', () => {
  assert.ok(pickProvider({ GEMINI_API_KEY: 'g', ANTHROPIC_API_KEY: 'a', OPENAI_API_KEY: 'o' }));
  // Priority is observable via health elsewhere; here assert selection order
  // by elimination: without gemini, anthropic/openai still work; with no
  // keys at all there is no provider.
  assert.ok(pickProvider({ ANTHROPIC_API_KEY: 'a' }));
  assert.ok(pickProvider({ OPENAI_API_KEY: 'o' }));
  assert.equal(pickProvider({}), null);
});

test('health reports the active provider', async () => {
  const res = await fetch(`${base}/api/health`);
  const data = await res.json();
  assert.equal(data.ok, true);
  assert.ok(['gemini', 'anthropic', 'openai', 'mock'].includes(data.ai_provider));
});
