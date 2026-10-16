# Production Readiness

A conservative, evidence-based self-assessment of CodeCritic across 12 dimensions. Scores are out of 10 and reflect the state of the code as documented at the time of writing, not aspirations. "Evidence" points at code/CI/docs that exist; "Gaps" are the concrete things missing to raise the score.

**Overall: ~6.8 / 10.** The deterministic core is well-tested and defensively built; the Python agent, observability, and performance evidence are the main deficits.

| # | Dimension | Score | Evidence | Gaps |
|---|-----------|:-----:|----------|------|
| 1 | Build/CI reproducibility | 7 | GitHub Actions builds and tests the Java module (`mvn -B test` with a `mongo:7` service) and installs + `py_compile`s the Python agent (`.github/workflows/ci.yml`). Maven parent pinned to Spring Boot `3.2.0`, Java `21`; multi-stage `java-server/Dockerfile`; `docker-compose.yml` and `render.yaml` are declarative. | Python deps are **unpinned** (`requirements.txt` has no versions/lockfile); `simplydone4j-spring-boot-starter:2.1.0` is a **vendored jar** not on Maven Central; Docker base images are not digest-pinned; Python CI compiles but does not test. |
| 2 | Automated tests | 6 | **118 Java tests, 0 failures/errors/skipped** (`mvn -B test`). Coverage spans the default-deny startup guard, CORS policy, JWT, controllers/error handling, analyzers, the SpotBugs cache, and job-coordinator idempotency/status mapping. `SecurityIntegrationTest` exercises the real filter chain against MongoDB (CI provides the service). | **No Python automated tests** (`pytest` collects 0; only a manual `integration_test.py`); no frontend/JS tests; LLM calls, GitHub downloads, and infra wiring are untested; no coverage tooling or thresholds. |
| 3 | Security | 7 | Default-deny startup (`DeploymentProfile`, `DefaultUserInitializer`, `JwtTokenProvider`) refuses the demo password/secret outside `dev`/`test`; BCrypt passwords; HS256 JWT ≥32 bytes; CORS never pairs wildcard with credentials; prompt-injection fences on every LLM entry point; per-job ownership check (IDOR); sanitized `5xx`; payload + per-IP rate limits; unique username index. | Python agent is **unauthenticated**; submitted Java is **compiled without a sandbox**; tokens are **not revocable**; default `CORS_ALLOW_ORIGINS=*`; a **live `GROQ_API_KEY` sits in an untracked `.env`** and must be rotated (see `SECURITY.md`). |
| 4 | Resilience/failure handling | 7 | `ResilientJobCoordinator` bounded in-memory fallback keeps the submit→poll contract when Redis/Mongo are down; SpotBugs degrades to pattern-only findings instead of failing; LLM retries transient errors (tenacity, 3 attempts, backoff) then falls back Groq→OpenAI, and to deterministic output when no key is set; startup tolerates Mongo/Redis being unreachable. | Fallback results are **not durable** across restarts and are single-instance only; no circuit breaker/bulkhead beyond pool sizing; no dead-letter replay tooling; `RateLimitExceeded`/`QueueFull` propagate but have no client retry guidance. |
| 5 | Observability/logging | 6 | Structured JSON logs on both services (`logstash-logback-encoder`, `python-json-logger`); Python attaches a per-request id and `X-Request-Id`/`X-Process-Time-Ms` headers; Java `GET /api/metrics` (counts, latency, SpotBugs timing) and Python `GET /metrics` (status counts, avg/p95 latency, rate-limit config). | **No correlation id across Java ↔ Python ↔ queue ↔ LLM**; no tracing (OTel); Java does not propagate a request id; SpotBugs cache `hits()/misses()/size()` are not exposed; metrics are in-memory and reset on restart; no Prometheus/OTLP export or alerting. |
| 6 | Config & secrets management | 8 | Entirely env-var driven with a documented table; `.gitignore` excludes `.env`; `.env.example` ships placeholders; default-deny guards prevent shipping demo secrets; Render blueprint uses `generateValue` for `JWT_SECRET`/`AUTH_PASSWORD`. | No secret-manager/Vault integration; demo defaults are still committed (though guarded); the local `.env` holds a live key requiring operator rotation; no config validation beyond the startup guards. |
| 7 | Error handling/API contracts | 7 | `GlobalExceptionHandler` returns a consistent `ApiErrorResponse`; correct 404 vs 5xx separation (tests assert infra faults are not 404s); `400/401/403/413/422/429` paths covered; Pydantic request validation; internal messages/SQL/URIs are not leaked to callers. | No OpenAPI/Swagger spec or typed client; some sync endpoints return a **bodiless 400** on blank input; no API versioning; contract changes are not schema-checked. |
| 8 | Performance/scale | 6 | Bounded LRU caches (SpotBugs 256, fallback 256) with in-flight de-duplication; unique temp dir per SpotBugs run; bounded executor pools; per-IP rate limiting and a bounded IP map; JVM capped for a 512 MB container; both services are stateless and scale horizontally. | **No load/stress tests or benchmarks**; metrics/fallback state is per-instance; no distributed cache; repository analysis samples files largest-first with a finite prompt budget, so results vary with repo shape. |
| 9 | Code quality/static analysis | 6 | Clean, documented layering: Strategy (`ComplexityAnalyzer`/`TestGenerator`), Composite (`BugDetector`), Adapter + Decorator (`JobCoordinator`), constructor injection, purposeful Javadoc explaining rationale, small cohesive classes. | **No lint/format gate** (no Checkstyle/Spotless/PMD/ruff/black); SpotBugs is used in-product, not enforced as a build quality gate; no coverage thresholds; no dependency-vulnerability scan (Dependabot/OWASP). |
| 10 | Deployment/ops | 7 | Dockerfiles for both services, `docker-compose.yml` with Redis healthcheck, a complete `render.yaml` blueprint with health checks, managed Redis, generated secrets, and CORS allowlist; `noCache` headers for HTML/JS; thorough `docs/deployment.md`. | Free tier is single-region and sleeps; no blue/green or rollback strategy; no staging environment; no DB migration tooling (the unique index is created at runtime); limited runbooks/on-call guidance. |
| 11 | Legal/licensing | 6 | MIT `LICENSE`; dependencies are predominantly permissive (Spring/Apache-2.0, JavaParser, jjwt Apache-2.0, FastAPI MIT; SpotBugs is LGPL — used as a linked library, note the implication if redistributing). | **No third-party license inventory / NOTICE**; the vendored SimplyDone4J jar's license is not documented in-repo; no SPDX headers; no automated license check. |
| 12 | Documentation | 8 | Unified `README.md` (overview → features → architecture → tech stack → prerequisites → quick start → config table → usage → testing → deployment → limitations → groundedness → roadmap → contributing → security → license); `SECURITY.md`; detailed `docs/` (architecture, API, algorithms, security, deployment, known limitations, this rubric, sample report); claims are grounded in source and a fresh test run. | No generated OpenAPI; no screenshots and only one mermaid diagram; roadmap items are unscheduled; the sample report is illustrative rather than a reproducible benchmark. |

## Lowest-scoring dimensions

Honest call-outs — these are the reasons the project is not yet 10/10:

- **Automated tests (6)** — lopsided: a strong Java suite, but **zero** Python tests and no tests for LLM/GitHub/infra paths.
- **Observability/logging (6)** — good structured logs and local metrics, but no cross-service correlation id or tracing.
- **Performance/scale (6)** — bounded and stateless by design, but there are **no load tests or benchmarks** to prove it.
- **Code quality/static analysis (6)** — clean code, but no enforced lint/format/coverage/vulnerability gate.
- **Legal/licensing (6)** — MIT is in place, but there is no third-party license inventory and the vendored jar's license is undocumented.

## How to raise the score

1. Add a Python test suite (pytest) covering the agent wrappers and pipelines with mocked HTTP/LLM; wire it into CI.
2. Introduce a request/correlation id propagated Java → Python → queue → LLM, and expose SpotBugs cache counters and Prometheus/OTLP metrics.
3. Add load/benchmark tests and publish baseline numbers.
4. Add a lint/format gate (Spotless + ruff/black) plus a dependency-vulnerability scan; optionally a coverage threshold.
5. Add a `THIRD-PARTY-LICENSES`/NOTICE file and document the vendored SimplyDone4J license.
6. Sandbox compilation of untrusted source and add auth in front of the Python agent.

## Performance Baseline (to establish)

No load/soak/benchmark tests currently exist in this repository. To establish a production
baseline, run one of the following against a deployed instance and record p50/p95/p99 latency
plus throughput:

- **HTTP API.** `wrk -t4 -c100 -d30s http://host:port/endpoint` or `hey -z 30s -c 100`.
- **Measurement points.** Submit endpoint throughput, read endpoint latency, WebSocket message
  fanout rate, and webhook delivery lag.
- **Record.** Store results in `docs/performance-baseline.md` (not committed to this repo yet) and
  re-run on every release to detect regressions.

