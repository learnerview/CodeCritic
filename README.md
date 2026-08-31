# CodeCritic

CodeCritic is our hybrid agentic code-review system. We pair a **Java analysis engine** with a **Python LLM orchestrator**: the Java layer does the deterministic work (complexity, bug heuristics, JUnit scaffolds, best-effort SpotBugs), and the Python layer plans and synthesizes the human-readable review with an LLM (Groq primary, OpenAI fallback). Putting the deterministic analysis on real ASTs and keeping the LLM for synthesis is exactly the split we want — the machine does what machines are good at, and the LLM only writes the review.

## Live demo

We run the Java server on Render's free tier:

- **Dashboard:** <https://codecritic-java.onrender.com/> — register an account and sign in.
- The Python agent and LLM (Groq) are wired in, so you can register, paste a class, and see a review end-to-end.

> Note: Render's free tier sleeps after ~15 minutes of inactivity, so the first load after a break can take 30–60 seconds to wake up.

## What we built

- **Hybrid, not black-box** — static analysis runs deterministically on real ASTs (JavaParser) and finds obvious risks with a fast pattern detector; the LLM only turns findings into a review, it never invents them.
- **Concurrent and thread-safe** — every SpotBugs run gets a unique temp directory and results are cached by source hash behind an LRU, so memory stays bounded and we never collide under parallel reviews.
- **Distributed-ready** — Java service and Python agent talk over HTTP; we can add an async job queue backed by Redis/SimplyDone4J whenever we need it.
- **Async jobs with idempotency** — job submissions are keyed deterministically (SHA-256 of type + payload), so retries never duplicate work.
- **Stateless JWT auth** — secrets come from the environment, tokens are stateless, and the whole thing scales horizontally.

## Quick start (Docker)

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/` (register an account on first visit)

- See [docs/deployment.md](docs/deployment.md) for local (non-Docker) and Render deployment.

## Architecture at a glance

```
Browser (CodeMirror UI)
   │  login → JWT
   ▼
Java Server (:8080)          Python Agent (:8000)
  JWT filter                    FastAPI orchestrator
  AnalysisController            ├─ calls Java REST (with token)
  ├─ /complexity                ├─ collects findings
  ├─ /bugs                      └─ LLM synthesis (Groq→OpenAI)
  ├─ /generate-test                 │
  └─ /jobs/* (Redis queue)          ▼
                                Natural-language review / tests
```

We document the full design and data flow in [docs/architecture.md](docs/architecture.md).

## Repo layout

- `java-server/` – Spring Boot analysis microservice (AST complexity, bug detection, test generation, JWT security, optional Redis jobs)
- `python-agent/` – FastAPI LLM orchestrator + HTTP wrappers
- `docs/` – our design, API reference, algorithms, security, deployment, and known limitations
- `docker-compose.yml`, `render.yaml` – deployment
- `.env.example` – environment variables

## Quick API overview

All Java `/api/**` endpoints require a JWT (except auth/config/health). Use the live demo URL below, or `http://localhost:8080` locally.

```bash
# create your account (no preset users) — reuse these creds in the login below
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'

# get a token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'

# deterministic analysis
curl -X POST http://localhost:8080/api/complexity -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"code":"public class A { int f(){ if(true){return 1;} return 0; } }"}'
curl -X POST http://localhost:8080/api/bugs -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"code":"public class A { int f(){ return 10/0; } }"}'
curl -X POST http://localhost:8080/api/generate-test -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"className":"Calc","methodName":"add","parameters":"int,int","code":"..."}'

# Python agent
curl -X POST http://localhost:8000/review -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int divide(int a,int b){ return a/b; } }"}'
curl -X POST http://localhost:8000/generate-tests -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int add(int a,int b){ return a+b; } }"}'
curl -X POST http://localhost:8000/analyze-repository -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","maxFiles":10}'
curl -X POST http://localhost:8000/debug -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"ArithmeticException: / by zero"}'
```

Full reference and examples: [docs/api.md](docs/api.md)

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/architecture.md](docs/architecture.md) | System design, flow diagram, module responsibilities |
| [docs/api.md](docs/api.md) | Every endpoint, request/response shapes, cURL examples |
| [docs/analysis-algorithms.md](docs/analysis-algorithms.md) | Complexity, bug-detection, and test-generation internals |
| [docs/security.md](docs/security.md) | JWT model, threat model, secrets guidance |
| [docs/deployment.md](docs/deployment.md) | Docker, local, and Render deploy steps |
| [docs/known-limitations.md](docs/known-limitations.md) | Trade-offs we accept & our roadmap |
| [docs/sample-analysis-report.md](docs/sample-analysis-report.md) | A real repository-analysis output |

## Environment variables

The core ones: `GROQ_API_KEY` (required), `OPENAI_API_KEY` (fallback), `JAVA_SERVER_URL`, `AUTH_USERNAME`/`AUTH_PASSWORD`, `JWT_SECRET`, `CORS_ALLOW_ORIGINS`. See [docs/deployment.md](docs/deployment.md) and `.env.example` for the full list.

## Testing

```bash
cd java-server && mvn test   # core suite: auth/JWT, analysis, cache, jobs, metrics
cd python-agent && python integration_test.py
```

## License

MIT