import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const SCHEMA_VERSION = 1;
const DEFAULT_MODEL = "gemini-3.8-flash";
const STATUSES = new Set(["correct", "incorrect", "incomplete", "unclear", "unsupported", "error"]);

export function loadEnvFiles() {
  for (const file of [path.join(here, "..", ".env"), path.join(here, ".env")]) {
    if (!fs.existsSync(file)) continue;
    for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
      const idx = trimmed.indexOf("=");
      const key = trimmed.slice(0, idx).trim();
      let value = trimmed.slice(idx + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      if (!process.env[key]) process.env[key] = value;
    }
  }
}

loadEnvFiles();

export function config() {
  return {
    apiKey: process.env.GEMINI_API_KEY || "",
    model: process.env.GEMINI_MODEL || DEFAULT_MODEL,
    port: Number(process.env.PORT || 8787),
  };
}

export function sanitize(text) {
  let out = String(text ?? "");
  const key = config().apiKey;
  if (key) out = out.split(key).join("[redacted]");
  return out.slice(0, 600);
}

export function healthBody() {
  const cfg = config();
  return {
    service: "inkproof-backend",
    ok: true,
    configured: Boolean(cfg.apiKey),
    model: cfg.model,
    schemaVersion: SCHEMA_VERSION,
    mockAvailable: true,
  };
}

const responseSchema = {
  type: "object",
  properties: {
    status: { type: "string", enum: ["correct", "incorrect", "incomplete", "unclear", "unsupported", "error"] },
    confidence: { type: "number" },
    recognizedQuestion: { type: "string" },
    recognizedSteps: { type: "array", items: { type: "string" } },
    stepAssessments: {
      type: "array",
      items: {
        type: "object",
        properties: {
          stepIndex: { type: "integer" },
          text: { type: "string" },
          verdict: { type: "string" },
          note: { type: "string" },
        },
        required: ["stepIndex", "verdict"],
      },
    },
    firstErrorStep: { type: "integer" },
    firstErrorExplanation: { type: "string" },
    explanation: { type: "string" },
    hints: { type: "array", items: { type: "string" } },
    finalAnswer: { type: "string" },
    fullSolution: { type: "array", items: { type: "string" } },
  },
  required: ["status", "confidence", "explanation", "hints"],
};

export function buildCheckPrompt(payload) {
  const steps = Array.isArray(payload.solutionSteps) ? payload.solutionSteps : [];
  return [
    "You are InkProof, a mathematics tutor checking ONE student question.",
    "Use only the question and student steps in this request. Do not import other problems, demo data, or earlier questions.",
    "Return JSON only. status must be correct, incorrect, incomplete, unclear, or unsupported.",
    "If the work is wrong, identify the first incorrect step in firstErrorStep (0-based) and firstErrorExplanation.",
    "Do not mark later steps correct if they depend on that earlier error. Use verdict depends_on_error for those.",
    "explanation and hints must NOT reveal the final answer. Put the answer only in finalAnswer and fullSolution.",
    "If the question or handwriting is ambiguous, use status unclear instead of inventing mathematics.",
    "If the topic is outside reliable checking (arbitrary integrals, matrices, multivariable calculus), use unsupported.",
    "",
    `Question id: ${payload.questionId || ""}`,
    "Question:",
    String(payload.promptText || ""),
    "",
    "Student steps:",
    steps.length ? steps.map((s, i) => `${i + 1}. ${s}`).join("\n") : "(none)",
    "",
    "On-device verifier, if any. Prefer its correctness verdict when it is definitive:",
    payload.localVerdict ? JSON.stringify(payload.localVerdict) : "null",
  ].join("\n");
}

export function buildSolvePrompt(payload) {
  return [
    "You are InkProof. Solve ONLY the question below from scratch.",
    "Ignore any student work. None is provided, and you must not assume any.",
    "Do not refer to any other question.",
    "Return JSON. status is correct if you solved it, unclear if the question is ambiguous, unsupported if you cannot solve it reliably.",
    "Put the worked steps in fullSolution and the final result in finalAnswer.",
    "explanation may summarize the method but the app decides what to reveal.",
    "",
    `Question id: ${payload.questionId || ""}`,
    "Question:",
    String(payload.promptText || ""),
  ].join("\n");
}

export function buildRecognizePrompt(payload) {
  const strokes = Array.isArray(payload.strokes) ? payload.strokes : [];
  return [
    "Transcribe the handwritten mathematics in these strokes. Do not solve the problem.",
    "Return JSON with status unclear, recognizedQuestion set to the transcription, confidence from 0 to 1, and explanation empty.",
    "If you cannot read it, status unclear and confidence below 0.4.",
    JSON.stringify(strokes).slice(0, 12000),
  ].join("\n");
}

export function normalizeModelPayload(raw, action, questionId) {
  let data = raw;
  if (typeof raw === "string") {
    data = JSON.parse(raw);
  }
  const status = String(data.status || "").toLowerCase();
  if (!STATUSES.has(status) || status === "error") {
    throw new Error("Model status is missing or invalid");
  }
  const firstStep = Number.isInteger(data.firstErrorStep) ? data.firstErrorStep : -1;
  return {
    schemaVersion: SCHEMA_VERSION,
    status,
    confidence: Number(data.confidence ?? 0),
    provider: "gemini",
    mock: false,
    model: config().model,
    recognizedQuestion: String(data.recognizedQuestion || ""),
    recognizedSteps: Array.isArray(data.recognizedSteps) ? data.recognizedSteps.map(String) : [],
    stepAssessments: Array.isArray(data.stepAssessments) ? data.stepAssessments : [],
    firstError: firstStep >= 0 ? { stepIndex: firstStep, explanation: String(data.firstErrorExplanation || "") } : null,
    explanation: String(data.explanation || ""),
    hints: Array.isArray(data.hints) ? data.hints.map(String).slice(0, 5) : [],
    finalAnswer: data.finalAnswer ? String(data.finalAnswer) : null,
    fullSolution: Array.isArray(data.fullSolution) ? data.fullSolution.map(String) : [],
    errorCode: null,
    message: null,
    questionId: String(questionId || ""),
    action,
  };
}

export function errorBody(code, message, action = "") {
  return {
    schemaVersion: SCHEMA_VERSION,
    status: "error",
    confidence: 0,
    provider: "gemini",
    mock: false,
    model: config().model,
    recognizedQuestion: "",
    recognizedSteps: [],
    stepAssessments: [],
    firstError: null,
    explanation: "",
    hints: [],
    finalAnswer: null,
    fullSolution: [],
    errorCode: code,
    message: sanitize(message),
    questionId: "",
    action,
  };
}

function classifyUpstream(status, text) {
  const lower = text.toLowerCase();
  if (status === 401 || status === 403 || lower.includes("api key")) return "INVALID_API_KEY";
  if (status === 429 || lower.includes("resource_exhausted") || lower.includes("rate")) return "RATE_LIMIT";
  if (status === 404 || lower.includes("not found") || lower.includes("model")) return "MODEL_ERROR";
  if (status >= 500) return "UPSTREAM";
  return "MODEL_ERROR";
}

export async function callGemini(prompt, fetchImpl = fetch) {
  const cfg = config();
  if (!cfg.apiKey) {
    return { ok: false, status: 503, body: errorBody("MISSING_API_KEY", "GEMINI_API_KEY is not set. Add it to the repository .env and restart the backend.") };
  }
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(cfg.model)}:generateContent`;
  let response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": cfg.apiKey,
      },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: 0.2,
          responseMimeType: "application/json",
          responseSchema,
        },
      }),
      signal: AbortSignal.timeout(45000),
    });
  } catch (err) {
    const timedOut = err && (err.name === "TimeoutError" || err.name === "AbortError");
    return {
      ok: false,
      status: 504,
      body: errorBody(timedOut ? "TIMEOUT" : "NETWORK", err && err.message ? err.message : "Gemini request failed"),
    };
  }
  const text = await response.text();
  if (!response.ok) {
    return { ok: false, status: response.status, body: errorBody(classifyUpstream(response.status, text), text) };
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { ok: false, status: 502, body: errorBody("INVALID_RESPONSE", "Gemini returned non-JSON") };
  }
  const part = parsed?.candidates?.[0]?.content?.parts?.map((p) => p.text || "").join("") || "";
  if (!part) {
    return { ok: false, status: 502, body: errorBody("INVALID_RESPONSE", "Gemini returned no text") };
  }
  try {
    return { ok: true, status: 200, body: normalizeModelPayload(part) };
  } catch (err) {
    return { ok: false, status: 502, body: errorBody("INVALID_RESPONSE", err.message || "Invalid model JSON") };
  }
}

export function mockCheck(payload) {
  return {
    schemaVersion: SCHEMA_VERSION,
    status: "unclear",
    confidence: 0,
    provider: "mock",
    mock: true,
    model: null,
    recognizedQuestion: String(payload.promptText || ""),
    recognizedSteps: Array.isArray(payload.solutionSteps) ? payload.solutionSteps : [],
    stepAssessments: [],
    firstError: null,
    explanation: `Mock response for ${payload.questionId || "unknown"}. This is not a real check.`,
    hints: ["Mock hint only."],
    finalAnswer: null,
    fullSolution: [],
    errorCode: null,
    message: "MOCK",
    questionId: String(payload.questionId || ""),
    action: "check",
  };
}

export function mockSolve(payload) {
  return {
    ...mockCheck(payload),
    action: "solve",
    status: "unsupported",
    explanation: `Mock solve for ${payload.questionId || "unknown"}. This is not a real solution.`,
  };
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

export async function handle(req, res, deps = {}) {
  const fetchImpl = deps.fetchImpl || fetch;
  const url = new URL(req.url || "/", "http://localhost");
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }
  try {
    if (req.method === "GET" && url.pathname === "/health") {
      res.writeHead(200);
      res.end(JSON.stringify(healthBody()));
      return;
    }
    if (req.method === "GET" && url.pathname === "/health/gemini") {
      const cfg = config();
      if (!cfg.apiKey) {
        res.writeHead(200);
        res.end(JSON.stringify({ reachable: false, configured: false, model: cfg.model, errorCode: "MISSING_API_KEY" }));
        return;
      }
      try {
        const probe = await fetchImpl(
          `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(cfg.model)}`,
          { headers: { "x-goog-api-key": cfg.apiKey }, signal: AbortSignal.timeout(15000) },
        );
        res.writeHead(200);
        res.end(JSON.stringify({
          reachable: probe.ok,
          configured: true,
          model: cfg.model,
          upstreamStatus: probe.status,
          errorCode: probe.ok ? null : classifyUpstream(probe.status, ""),
        }));
      } catch (err) {
        res.writeHead(200);
        res.end(JSON.stringify({ reachable: false, configured: true, model: cfg.model, errorCode: "NETWORK", message: sanitize(err.message) }));
      }
      return;
    }
    if (req.method === "POST" && (url.pathname === "/v1/check" || url.pathname === "/v1/solve" || url.pathname === "/v1/recognize")) {
      const payload = await readJson(req);
      const action = url.pathname.endsWith("solve") ? "solve" : url.pathname.endsWith("recognize") ? "recognize" : "check";
      const prompt = action === "solve" ? buildSolvePrompt(payload) : action === "recognize" ? buildRecognizePrompt(payload) : buildCheckPrompt(payload);
      const result = await callGemini(prompt, fetchImpl);
      const body = result.body;
      body.action = action;
      body.questionId = String(payload.questionId || body.questionId || "");
      if (payload.localVerdict && result.ok && action === "check") {
        body.message = "Cloud explanation attached. On-device verdict is authoritative when the app merges results.";
      }
      res.writeHead(result.ok ? 200 : result.status);
      res.end(JSON.stringify(body));
      return;
    }
    if (req.method === "POST" && (url.pathname === "/v1/mock/check" || url.pathname === "/v1/mock/solve")) {
      const payload = await readJson(req);
      const body = url.pathname.endsWith("solve") ? mockSolve(payload) : mockCheck(payload);
      res.writeHead(200);
      res.end(JSON.stringify(body));
      return;
    }
    res.writeHead(404);
    res.end(JSON.stringify(errorBody("UPSTREAM", "Unknown endpoint")));
  } catch (err) {
    res.writeHead(400);
    res.end(JSON.stringify(errorBody("INVALID_RESPONSE", err.message || "Bad request")));
  }
}

export function start(port = 0, host = "127.0.0.1") {
  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      res.writeHead(500);
      res.end(JSON.stringify(errorBody("UPSTREAM", err.message || "Internal error")));
    });
  });
  return new Promise((resolve) => server.listen(port, host, () => resolve(server)));
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const cfg = config();
  start(cfg.port, "0.0.0.0").then((server) => {
    const address = server.address();
    console.log(`InkProof backend listening on 0.0.0.0:${address.port}`);
    console.log(`Gemini configured: ${Boolean(cfg.apiKey)} model: ${cfg.model}`);
  });
}
