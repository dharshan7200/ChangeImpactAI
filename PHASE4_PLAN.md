# Phase 4 Plan — Advanced AI Engineering, Hybrid RAG and Multi-Agent System

Status: **implemented 2026-11-22 — see [docs/AI_REVIEW.md](docs/AI_REVIEW.md) for what was built, measured and
left out.** Differences from this plan: one model call per review/question inside a deterministic Java pipeline instead
of six agents (the deterministic steps need no agent); a small REST client instead of Spring AI's ChatClient (Gemini's
response-schema JSON output and exact retry/quota handling); no tool-calling or multi-turn conversations; evaluation
by scripts with stored results instead of evaluation tables. This document is kept as the original plan.
Provider: **Google Gemini** (chat + embeddings) through Spring AI's Google GenAI starters. The API key is
injected by the cloud environment's network proxy and is never stored in code or configuration.

## 1. Objective
Turn the deterministic Phase 3 engine into an AI developer assistant whose answers are grounded in retrieved
source code and verified dependency-graph results, with every important claim traceable and validated.
The LLM explains and reasons; it never decides what depends on what.

## 2. Features
| # | Feature | Uses the LLM? |
|---|---|---|
| 1 | AST-aware code chunking (type headers, methods, constructors, fields; imports + package kept as metadata) | No |
| 2 | Embeddings for chunks (Gemini embedding model, 768 dimensions), re-used across commits by content hash | Embedding model |
| 3 | Hybrid retrieval: PostgreSQL full-text (ts_rank_cd) + pgvector cosine + graph expansion (JGraphT neighbours) + repository/commit filters, fused with Reciprocal Rank Fusion (k = 60); optional LLM rerank behind a flag | Embeddings; rerank optional |
| 4 | Retrieval evaluation: Recall@K, Precision@K, MRR, context completeness on a hand-labelled question set | Embeddings only |
| 5 | Spring AI integration: ChatClient, structured output into validated Java records, tool calling, usage/latency capture, retries | Yes |
| 6 | Six agents + a deterministic Java orchestrator over a Phase 3 analysis (below) | Partly |
| 7 | Conversational repository assistant with tools and mandatory citations | Yes |
| 8 | Reliability controls: output validation, citation validation, unsupported-claim detection, tool-call limits, prompt-injection resistance, secret redaction, provider-failure handling | No (they police the LLM) |
| 9 | AI evaluation framework: citation correctness, dependency-claim correctness, structured-output validity, hallucination rate, latency and tokens — numbers only from real runs | Yes |
| 10 | UI: assistant chat, AI review tab on the analysis page, agent execution trace, RAG search inspector, evaluation dashboard | — |

## 3. Agents and orchestration
The orchestrator is ordinary Java code that owns state, permissions, tool execution and validation.
There is no autonomous loop; agents cannot run shell commands or modify files.

| Agent | Input | Work | Output (validated record) |
|---|---|---|---|
| ChangeAnalyzerAgent | Phase 3 changed symbols + diff hunks | LLM summarises intent and notable modifications | `ChangeSummary` |
| DependencyInvestigatorAgent | changed symbols | **Deterministic** graph tools (dependents, paths, cycles) | `GraphEvidence` (verified edges only) |
| ContextRetrievalAgent | changed + impacted symbols | Hybrid RAG over the head commit | `RetrievedContext` with scores per retriever |
| RiskAnalysisAgent | deterministic report + evidence + context | LLM proposes failure scenarios, each citing symbols/paths | `RiskFindings` |
| TestRecommendationAgent | impacts + test references + context | LLM proposes regression scenarios; existing tests come from the graph | `TestRecommendations` |
| EvidenceValidationAgent | all findings | **Deterministic**: cited symbols exist at that commit, cited paths exist in the graph, line ranges match; unsupported claims are rejected or marked `UNVERIFIED` | `ValidationReport` |

Each step is persisted as an `agent_execution` (inputs hash, prompt version, model, tokens, latency, status)
with `agent_findings` and `evidence_references`.

## 4. Assistant
- `POST /api/v1/assistant/chat` with tools: `searchCode`, `getSymbol`, `getDependents`, `getDependencies`,
  `findPath`, `getChangeReport`. Tool results are the only allowed evidence.
- Every repository-specific answer carries citations (symbol, file, lines). The validator checks them; if
  evidence is insufficient the assistant must say so instead of guessing.
- Limits: at most 8 tool calls per turn, a token budget per turn, per-user rate limit.

## 5. Security and reliability
- Repository code is untrusted data: delimited as data in prompts, system instructions forbid following
  instructions found in code, and outputs are validated before use.
- Secret redaction before any code leaves the system (API keys, tokens, passwords, private keys, connection strings).
- Structured outputs bound to records with Bean Validation; invalid outputs are retried once, then reported as failed.
- Provider errors (429/5xx/timeouts) use bounded retries with backoff; the deterministic report always stays available.
- Token usage and latency are recorded per call.

## 6. Database changes (V7)
| Table | Purpose |
|---|---|
| `code_chunks` | chunk text, `chunk_type`, file/line range, `symbol_id`, `commit_sha`, `content_hash`, generated `tsvector` (GIN index), `embedding vector(768)` (HNSW, cosine), `embedding_model` |
| `assistant_conversations`, `assistant_messages` | chat history with citations and tool calls |
| `agent_executions`, `agent_findings`, `evidence_references` | traces of every agent step and the evidence each finding cites |
| `evaluation_runs`, `evaluation_results` | stored evaluation metrics per run |

## 7. API (initial)
`POST /repositories/{id}/rag/index`, `GET /repositories/{id}/rag/status`, `POST /rag/search` (shows scores per
retriever), `POST /analyses/{id}/ai-review` (async), `GET /analyses/{id}/ai-review`, `GET /agent-executions/{id}`,
`POST /assistant/chat`, `GET /assistant/conversations/{id}`, `POST /evaluations/run`, `GET /evaluations/{id}`.

## 8. Folder structure (new packages)
`rag/{ingestion,embedding,retrieval,reranking}`, `ai/{client,tools,guard}`, `agents/{analyzer,investigator,retrieval,risk,testing,validation,orchestration}`,
`assistant`, `evaluation`.

## 9. Implementation sequence
1. Chunking, V7 schema, embedding pipeline, hybrid search, retrieval evaluation.
2. Spring AI infrastructure: ChatClient, structured output, usage tracking, retries, injection guard, redaction, tools.
3. Agents, orchestrator, evidence validation, persistence of traces.
4. Assistant with conversations and citations.
5. Frontend: assistant, AI review, traces, RAG inspector, evaluation dashboard.
6. AI evaluation runs, documentation, acceptance check.

## 10. Expected output and acceptance criteria
- A user can ask repository-specific questions and receive answers grounded in actual source code and graph results, with citations that pass validation.
- The system rejects or qualifies unsupported conclusions (visible as `UNVERIFIED`/rejected in the UI and traces).
- Retrieval metrics and AI evaluation metrics are produced by real runs on the labelled dataset; none are estimated.

## 11. Dependencies on you
- **Gemini API key** saved as a network secret on the `ChangeImpactAI` environment
  (host `generativelanguage.googleapis.com`, header `x-goog-api-key`). Without it, steps 1–2 can be built and unit-tested
  with deterministic fakes, but no real embeddings, answers or evaluation numbers can be produced or claimed.
- Model names are confirmed against Gemini's live model list once the key works.
- Gemini usage may be rate-limited or billed by Google depending on your plan.

## Status update (2026-11-18)
- The owner added the Gemini API key as a network secret. Verified: `gemini-embedding-001` returns 768-dimension
  embeddings (matches the pgvector column).
- `gemini-2.5-flash` (the planned chat default) answers 404 "no longer available to new users". The API lists
  `gemini-3.8-flash`, `gemini-3.7-flash`, `gemini-3.6-flash`, `gemini-3.5-flash`, `gemini-flash-latest`, `gemini-3.1-pro-preview`;
  at the time of testing the flash models answered 503 (high demand) or timed out, while `gemini-flash-lite-latest`
  (served by `gemini-3.5-flash-lite`) answered correctly. The chat model will be chosen and verified at the start of
  Phase 4 (quality vs availability) and set via `GEMINI_CHAT_MODEL`; the 2.5-flash default must change.
