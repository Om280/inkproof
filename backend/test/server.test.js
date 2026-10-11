import test from "node:test";
import assert from "node:assert/strict";
import { start, buildCheckPrompt, buildSolvePrompt, mockCheck, normalizeModelPayload, sanitize, healthBody } from "../server.js";

async function withServer(fn) {
  const server = await start(0, "127.0.0.1");
  const port = server.address().port;
  try {
    await fn(port);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function post(port, path, body) {
  const res = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, json: JSON.parse(text) };
}

test("health does not leak a configured key", async () => {
  process.env.GEMINI_API_KEY = "AIza-test-secret-value";
  process.env.GEMINI_MODEL = "gemini-3.8-flash";
  const body = healthBody();
  assert.equal(body.configured, true);
  assert.equal(body.model, "gemini-3.8-flash");
  assert.equal(JSON.stringify(body).includes("AIza-test-secret-value"), false);
  const redacted = sanitize("failure AIza-test-secret-value in body");
  assert.equal(redacted.includes("AIza-test-secret-value"), false);
  delete process.env.GEMINI_API_KEY;
});

test("missing key is an error, not a fake success", async () => {
  delete process.env.GEMINI_API_KEY;
  await withServer(async (port) => {
    const health = await fetch(`http://127.0.0.1:${port}/health`);
    const healthJson = await health.json();
    assert.equal(healthJson.configured, false);
    assert.equal(JSON.stringify(healthJson).includes("AIza"), false);
    const result = await post(port, "/v1/check", {
      questionId: "B",
      promptText: "x^2 + 4x + 1 = 0",
      solutionSteps: ["x = -2"],
    });
    assert.notEqual(result.status, 200);
    assert.equal(result.json.status, "error");
    assert.equal(result.json.mock, false);
    assert.equal(result.json.errorCode, "MISSING_API_KEY");
    assert.notEqual(result.json.status, "correct");
  });
});

test("mock endpoint is labeled and real check does not call it", async () => {
  delete process.env.GEMINI_API_KEY;
  await withServer(async (port) => {
    const mock = await post(port, "/v1/mock/check", {
      questionId: "B",
      promptText: "SECRET_B only",
      solutionSteps: ["x = 1"],
    });
    assert.equal(mock.status, 200);
    assert.equal(mock.json.mock, true);
    assert.equal(mock.json.provider, "mock");
    assert.equal(mock.json.message, "MOCK");
    assert.equal(mock.json.recognizedQuestion.includes("SECRET_B"), true);
    const real = await post(port, "/v1/check", { questionId: "B", promptText: "2x+6=14", solutionSteps: [] });
    assert.equal(real.json.mock, false);
    assert.equal(real.json.status, "error");
  });
});

test("prompt builder keeps only the supplied question", () => {
  const prompt = buildCheckPrompt({
    questionId: "B",
    promptText: "x^2 + 4x + 1 = 0 SECRET_B",
    solutionSteps: ["x = -2 ± sqrt(3)"],
  });
  assert.equal(prompt.includes("SECRET_B"), true);
  assert.equal(prompt.includes("SECRET_A"), false);
  assert.equal(prompt.includes("2x + 6 = 14"), false);
  const solve = buildSolvePrompt({ questionId: "B", promptText: "SECRET_B only" });
  assert.equal(solve.includes("SECRET_A"), false);
  assert.equal(solve.includes("student work"), true);
});

test("model payload cannot claim to be mock or skip the schema", () => {
  const normalized = normalizeModelPayload(
    {
      status: "incorrect",
      confidence: 0.8,
      provider: "mock",
      mock: true,
      explanation: "Subtracted wrong.",
      hints: ["Check the constant."],
      firstErrorStep: 1,
      firstErrorExplanation: "14 - 6 is 8.",
      finalAnswer: "x = 4",
      recognizedQuestion: "2x+6=14",
    },
    "check",
    "B",
  );
  assert.equal(normalized.provider, "gemini");
  assert.equal(normalized.mock, false);
  assert.equal(normalized.schemaVersion, 1);
  assert.equal(normalized.firstError.stepIndex, 1);
  assert.equal(normalized.questionId, "B");
  assert.throws(() => normalizeModelPayload({ status: "fabulous", explanation: "x", hints: [] }, "check", "B"));
});

test("mock helper echoes only its own question id", () => {
  const body = mockCheck({ questionId: "B", promptText: "SECRET_B", solutionSteps: ["1"] });
  assert.equal(body.mock, true);
  assert.equal(body.questionId, "B");
  assert.equal(JSON.stringify(body).includes("SECRET_A"), false);
});
