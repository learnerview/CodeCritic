# CodeCritic

Hybrid agentic code review: a deterministic Java analysis engine paired with a Python LLM orchestrator that turns grounded findings into human-readable reviews and tests.

## Overview

CodeCritic is a two-service system. A **Java Spring Boot server** does the deterministic work — cyclomatic/cognitive complexity on real ASTs (JavaParser), pattern-based and SpotBugs bug detection, and JUnit 5 scaffold generation — and a **Python FastAPI agent** orchestrates those findings and asks an LLM (Groq primary, OpenAI fallback) to synthesize the review, a full test suite, repository summaries, and root-cause diagnosis. A single-page dashboard (CodeMirror, served by the Java service) puts all of it in a browser.

The design intent is a clean split: the machine finds and measures what can be found deterministically, and the LLM only writes prose *on top of* those findings. LLM output is advisory and may be wrong — see [Groundedness & Verification](#groundedness--verification).

- Full design and data flow: [docs/architecture.md](docs/architecture.md)
- Every endpoint and request/response shape: [docs/api.md](docs/api.md)
- Algorithm internals: [docs/analysis-algorithms.md](docs/analysis-algorithms.md)

## Features

- **Hybrid, not black-box** — static analysis runs deterministically on real ASTs; the LLM synthesizes a review from the findings and never invents them.
- **Complexity analysis** — cyclomatic complexity per method (AST visitor) plus a simple cognitive estimate; escape-aware token fallback if parsing fails.
- **Bug detection** — a fast pattern detector (division-by-zero, unguarded `.toString()`) composed with a best-effort SpotBugs pass that compiles the submitted source in an isolated temp dir.
- **Test generation** — deterministic JUnit 5 scaffolds from real signatures, plus an LLM-generated full suite with happy-path/edge/error coverage.
- **Repository analysis** — fetch a GitHub repo archive, analyze Java files, compute deterministic metrics (hotspots, bug density, package breakdown), then summarize.
- **Debug assistant** — combine source, an error log, and static findings into an LLM root-cause diagnosis.
- **Async job queue** — optional Redis-backed queue (SimplyDone4J) with a bounded in-memory fallback when the backend is down.
- **Idempotent jobs** — idempotency keys are a SHA-256 of the job type plus a canonical JSON encoding of the full payload (map keys sorted at every nesting level, list order preserved), so retries dedupe and distinct payloads never collide.
- **Concurrency-safe** — unique temp dir per SpotBugs run, an LRU result cache with in-flight de-duplication, and bounded thread pools.
- **Stateless JWT auth** — BCrypt-hashed users in MongoDB, stateless HS256 tokens, horizontal scaling.
- **Fail-safe configuration** — default-deny startup: the app refuses to boot with the repo's demo credentials or the committed demo JWT secret unless a `dev`/`test` profile is explicitly active.

## Architecture

```
Browser (CodeMirror SPA)
   │
   ├── deterministic analysis ──→ Java Server (:8080)      JWT filter · AnalysisController
   │                                ├─ /api/complexity       (AST)
   │                                ├─ /api/bugs             (pattern + SpotBugs)
   │                                ├─ /api/generate-test    (JUnit scaffold)
   │                                └─ /api/jobs/*           (Redis queue, in-memory fallback)
   │
   └── AI features ─────────────→ Python Agent (:8000)      FastAPI
                                    ├─ calls Java REST with a service-account token
                                    ├─ collects deterministic findings
                                    └─ LLM synthesis (Groq → OpenAI)
```

Repo layout: `java-server/` (Spring Boot analysis engine + dashboard), `python-agent/` (FastAPI orchestrator), `docs/` (design/API/security/readiness), `docker-compose.yml` + `render.yaml` (deployment), `vendor/` (locally-built SimplyDone4J jar). See [docs/architecture.md](docs/architecture.md) for the full design, module responsibilities, and thread-safety notes.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Java server | Java 21, Spring Boot 3.2.0, Spring Security, Spring Data MongoDB, Tomcat |
| Analysis | JavaParser 3.25.8, SpotBugs (library 4.7.3; CLI 4.10.4 in the Docker image) |
| Auth | jjwt 0.12.3 (HS256), BCrypt |
| Async jobs | SimplyDone4J 2.1.0 (vendored), Redis |
| Logging | logstash-logback-encoder 7.4 (structured JSON) |
| Python agent | Python 3.11, FastAPI, Pydantic, httpx, requests, tenacity, python-dotenv, python-json-logger (unpinned in `requirements.txt`) |
| LLM providers | Groq (`llama-3.3-70b-versatile`), OpenAI (`gpt-4o-mini`, fallback) |
| Frontend | Single-page HTML + CodeMirror (vendored) + vanilla JS/CSS, served by the Java service |
| Packaging | Docker, Docker Compose, Render blueprint |

## Prerequisites

- **Docker path (recommended):** Docker + Docker Compose.
- **Local path:** JDK 21, Maven 3.9+, Python 3.11+ (3.11 is what the Docker image and CI use).
- **For full local functionality:** a reachable MongoDB (user store) and Redis (job queue). Both are optional to *start* — see the resilience notes in [docs/architecture.md](docs/architecture.md).
- **LLM features:** a `GROQ_API_KEY` (or `OPENAI_API_KEY`).

## Quick Start

**Docker (both services + Redis):**

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/` — register an account on first visit.
- The Python agent is at `http://localhost:8000/`.
- Compose sets `SPRING_PROFILES_ACTIVE=dev`, so the demo credentials in `java-server/src/main/resources/application-dev.yml` apply. Any other profile (or none) requires real `AUTH_PASSWORD`/`JWT_SECRET` or the server refuses to start.

**Local (no Docker):** start the Python agent first (it pings Java health on boot), then the Java server:

```bash
cd python-agent && python -m venv venv && source venv/bin/activate   # Windows: .\venv\Scripts\activate
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8000

cd java-server && mvn package
java -jar target/java-server-0.1.0.jar --spring.profiles.active=dev
```

**Hosted demo:** the Java dashboard runs on Render's free tier at <https://codecritic-java.onrender.com/>. The free tier sleeps after ~15 minutes idle, so the first load after a break can take 30–60 seconds.

Full setup, Render deployment, and free-tier caveats: [docs/deployment.md](docs/deployment.md).

## Configuration

Environment variables (see `.env.example`). Defaults shown are the **code defaults**; `dev` supplies the demo credentials, and `prod` (Render) sets its own.

| Name | Purpose | Default | Required |
|------|---------|---------|----------|
| `GROQ_API_KEY` | Primary LLM provider key | — | Yes for AI features |
| `OPENAI_API_KEY` | Fallback LLM provider key | — | No |
| `GROQ_MODEL` | Groq model id | `llama-3.3-70b-versatile` | No |
| `OPENAI_MODEL` | OpenAI model id | `gpt-4o-mini` | No |
| `GROQ_BASE_URL` | Groq chat-completions endpoint | `https://api.groq.com/openai/v1/chat/completions` | No |
| `LLM_TIMEOUT_SECONDS` | LLM HTTP timeout (seconds) | `60` | No |
| `TEST_MAX_TOKENS` | Token cap for generated test suites | `4096` | No |
| `REVIEW_MAX_TOKENS` | Token cap for review/debug output | `2048` | No |
| `JAVA_SERVER_URL` | Java base URL used by the Python agent | `http://localhost:8080/api` | No |
| `PYTHON_AGENT_URL` | Agent URL handed to the dashboard via `/api/config` | `http://localhost:8000` | No |
| `AUTH_USERNAME` | Service account the Python agent logs in with | `admin` | Yes (a dedicated user) outside `dev`/`test` |
| `AUTH_PASSWORD` | Service-account password | blank in base config; `admin` under `dev` | Yes outside `dev`/`test` |
| `JWT_SECRET` | JWT signing secret, ≥32 bytes | blank in base config; demo value under `dev` | Yes outside `dev`/`test` |
| `JWT_EXPIRATION_MS` | Token lifetime | `86400000` (24h) | No |
| `CORS_ALLOW_ORIGINS` | Comma-separated allowed browser origins (wildcard drops credentials) | `*` | No (set an explicit list in production) |
| `GITHUB_TOKEN` | Private repos / higher GitHub API limits | — | No |
| `MAX_REQUEST_BYTES` | Python request-body size cap | `1500000` | No |
| `MAX_REPO_FILES` | Max repository files analyzed per request (also the `maxFiles` upper bound) | `30` | No |
| `RATE_LIMIT_REQUESTS` | Python per-IP requests per window | `60` | No |
| `RATE_LIMIT_WINDOW_SECONDS` | Python rate-limit window (seconds) | `60` | No |
| `RATE_LIMIT_MAX_IPS` | Max distinct client IPs tracked | `10000` | No |
| `SPRING_PROFILES_ACTIVE` | Config profile (`dev` for demo defaults; `prod` on Render) | none (production-like) | Set `dev` for local demos, or supply real secrets |
| `SPRING_DATA_MONGODB_URI` | MongoDB user store | `mongodb://localhost:27017/codecritic` | Needed for auth |
| `REDIS_URL` | Job-queue Redis connection | `redis://localhost:6379` | No |
| `SIMPLYDONE4J_SCHEDULER_ENABLED` | Scheduler + worker maintenance + queue processing | `false` | No |
| `SIMPLYDONE4J_MONITORING_ENABLED` | Job monitoring endpoints in the dashboard | `false` | No |
| `SIMPLYDONE4J_EXECUTOR_CORE_POOL_SIZE` / `_MAX_POOL_SIZE` / `_QUEUE_CAPACITY` | Async worker pool sizing | `2` / `4` / `50` | No |
| `SIMPLYDONE4J_QUEUE_MAX_DEPTH` | Queue depth cap | `5000` | No |
| `PORT` | Java server HTTP port | `8080` | No |
| `TOMCAT_MAX_THREADS` | Tomcat max worker threads | `100` | No |

## Usage

All Java `/api/**` endpoints require a JWT (except `/api/auth/*`, `/api/config`, `/health`, `/error`); the Python agent has no auth and is intended for trusted internal use.

```bash
# register + login (Java, :8080)
curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'

# deterministic analysis (Java)
curl -X POST http://localhost:8080/api/complexity -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"code":"public class A { int f(){ if(true){return 1;} return 0; } }"}'
curl -X POST http://localhost:8080/api/bugs -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"code":"public class A { int f(){ return 10/0; } }"}'
curl -X POST http://localhost:8080/api/generate-test -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"className":"Calc","methodName":"add","parameters":"int,int","code":"..."}'

# AI features (Python, :8000)
curl -X POST http://localhost:8000/review -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int divide(int a,int b){ return a/b; } }"}'
curl -X POST http://localhost:8000/generate-tests -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int add(int a,int b){ return a+b; } }"}'
curl -X POST http://localhost:8000/analyze-repository -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","maxFiles":15}'
curl -X POST http://localhost:8000/debug -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"ArithmeticException: / by zero"}'
```

Full reference, async `/api/jobs/*` polling, metrics/health endpoints, and tunable limits (`maxFiles` is `1..MAX_REPO_FILES`, default `15`): [docs/api.md](docs/api.md). A worked repository-analysis example: [docs/sample-analysis-report.md](docs/sample-analysis-report.md).

## Testing

**Java server** (JUnit 5 + Spring test + Mockito). The `SecurityIntegrationTest` needs a live MongoDB; the CI workflow provides one as a service.

```bash
cd java-server
mvn -B test
```

**Python agent** — there is currently **no automated unit-test suite**; `pytest` collects no tests. `integration_test.py` is a manual end-to-end script that requires a running Java server (default `http://localhost:8080/api`) and, for the LLM assertions, a configured provider key:

```bash
cd python-agent
python -m pytest          # collects 0 items (no unit tests yet)
python integration_test.py # manual: needs the Java server + LLM key running
```

CI (`.github/workflows/ci.yml`) runs `mvn -B test` against a `mongo:7` service for the Java job, and installs dependencies plus `python -m py_compile` for the Python job.

## Deployment

```bash
docker compose up --build          # local stack (Java + Python + Redis)
```

Production uses the included Render blueprint (`render.yaml`), which deploys both services plus managed Redis; `JWT_SECRET`/`AUTH_PASSWORD` are auto-generated and the free tier caps the JVM at `-Xmx256m`. Dockerfiles, environment tuning, and free-tier caveats are documented in [docs/deployment.md](docs/deployment.md).

## Limitations & Known Issues

Honest, current limitations — see [docs/known-limitations.md](docs/known-limitations.md) for the full list.

- **AI/NLP analysis is approximate.** Deterministic bug detection is line-based and complexity includes a crude cognitive estimate (`max(1, cyclomatic/2)`); generated tests are templates or unverified LLM output and may not compile for complex types. False positives/negatives are expected.
- **Language coverage is Java-centric.** Complexity, bug detection, and test generation are Java-only; `/review` rejects non-Java input. Repository analysis collects `.java`, `.py`, `.js`, `.ts`, `.tsx` but only statically analyzes Java.
- **External LLM dependency.** AI features rely on Groq (fallback OpenAI) and inherit its rate limits and outages; transient failures are retried (3 attempts, exponential backoff) and then fall back, and with no key configured the agent degrades to deterministic output. LLM output is non-deterministic.
- **Repo-size caps.** Repository analysis is bounded by `MAX_REPO_FILES` (default 30), selects files largest-first, and feeds the LLM a finite (~32k character) excerpt budget; request bodies are capped by `MAX_REQUEST_BYTES` (default 1.5 MB).
- **Default-deny configuration.** Outside the explicit `dev`/`test` profiles the server refuses to start with the committed demo password or JWT secret. A bare `java -jar` with no profile and no env vars will not start by design.
- **Known gaps:** no sandbox for compiled input, no tracing/cross-service correlation id, stateless tokens are not revocable, no persistent review history, and the Python agent is unauthenticated. See [docs/known-limitations.md](docs/known-limitations.md).

A production-readiness rubric with per-dimension scores and evidence is in [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md).

## Groundedness & Verification

### Verification status

Real numbers from a run of this working tree (Windows, JDK 21.0.7, Maven 3.9.6, Python 3.14 locally / 3.11 in CI):

- **Java server — 118 tests, 0 failures, 0 errors, 0 skipped** (`mvn -B test`, BUILD SUCCESS). The suite covers JWT/auth, the default-deny startup guard, CORS policy, the analysis processors, the SpotBugs cache, the job coordinators (including idempotency-key canonicalization), metrics, controllers, and an end-to-end security integration test against MongoDB.
- **Python agent — 0 automated tests.** `python -m pytest` collects no tests; the only Python check is `integration_test.py`, a **manual** script that needs a running Java server (and an LLM key for its LLM assertions). Python CI only compiles the modules (`py_compile`), it does not run tests. Infrastructure wiring and LLM calls are **not** covered by automated tests.
- **Not independently verified here:** end-to-end runs against live Groq/OpenAI, real GitHub repository downloads, Render deployment behavior, and load/performance characteristics.

### Data & evidence provenance

- **Analyzed inputs** are user-submitted code snippets (over HTTP) and, for repository analysis, GitHub repositories fetched as zip archives (`codeload.github.com`, with the GitHub API zipball as fallback for private repos via a token). Inputs are processed in-process/temp dirs and are not persisted as review history.
- **Deterministic evidence** (complexity, pattern/SpotBugs findings, repository metrics, test scaffolds) is computed locally by the Java/Python services. The sample report in [docs/sample-analysis-report.md](docs/sample-analysis-report.md) uses a **public** repository (`learnerview/simplydone`) and contains only aggregate metrics — no secrets or personal data.
- **External services:** LLM synthesis is the only third-party call (Groq, then OpenAI). It is **real**, not mocked, when a key is configured; when no key is present the agent returns deterministic output and labels it as such. No other external data source is used.

### AI-output grounding

CodeCritic uses LLMs, so its narrative output carries the usual risks:

- **Grounding:** prompts embed the deterministic findings (complexity, bug findings, templates, metrics) as fenced `UNTRUSTED DATA` and instruct the model to ground every claim in them; repository summaries are explicitly told not to invent findings. Findings are produced by the deterministic layer, and the LLM is asked to *explain* them.
- **Constraints:** prompt-injection guards fence all user/repo content and neutralize attempts to break out of the fence; LLM calls have token caps, timeouts, and provider fallback. These reduce — but cannot eliminate — hallucination or injected-content influence.
- **Risk:** LLM output can be wrong, incomplete, or plausible-but-false, especially for complex or unfamiliar code. **Findings and generated tests are advisory and may be incorrect — human review is required before acting on them.** CodeCritic is a decision aid, not an authority.

## Roadmap

- Sandboxed compilation/execution (container + `seccomp`) so the review path is safe for untrusted input.
- Precise Sonar-style cognitive complexity (replacing the `max(1, cyclomatic/2)` estimate).
- A real Python test suite and expanding Java coverage to the orchestration paths.
- Request correlation id across Java → Python → queue → LLM, and OpenTelemetry tracing.
- Persistent review history and per-user dashboards; revocable/rotating tokens.
- Multi-language static analysis and a Redis-backed distributed result cache.

## Contributing

Issues and pull requests are welcome. Before opening a PR:

1. Keep the deterministic/LLM split: new analysis must be grounded in code, not invented by the model.
2. Run the Java suite (`cd java-server && mvn -B test`) and at least `python -m py_compile` for the agent; ensure CI passes.
3. Update the relevant doc in `docs/` and, where behavior changes, this README. Do **not** commit real secrets — use `.env.example` placeholders.

## Security

Security-relevant behavior (stateless JWT, default-deny startup, CORS hardening, prompt-injection fences, rate/payload limits, job-ownership checks, and honest threat-model caveats) is summarised in [SECURITY.md](SECURITY.md) and detailed in [docs/security.md](docs/security.md). A live API key was found in an untracked local `.env` — see [SECURITY.md](SECURITY.md) for the rotation steps.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 CodeCritic.
