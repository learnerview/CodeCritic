# CodeCritic

CodeCritic is a hybrid agentic code-review system that pairs a **Python LLM orchestrator** with a **Java analysis engine**. The Java layer performs deterministic analysis (complexity, bug heuristics, JUnit scaffolds, optional SpotBugs), while the Python layer plans, synthesizes, and orchestrates LLM reviews (Groq primary, OpenAI fallback).

## Highlights

- **Hybrid, not black-box**: static analysis runs deterministically on real ASTs; the LLM only synthesizes findings into a readable review.
- **Concurrent & thread-safe**: every SpotBugs run uses a unique temp directory; results are cached by source hash behind an LRU to stay bounded and collision-free.
- **Distributed-ready**: Java analysis service + Python agent talk over HTTP; optional async job queue via Redis/SimplyDone4J.
- **Async jobs with idempotency**: job submissions are keyed deterministically (SHA-256 of type + payload), so retries don't duplicate work.
- **Stateless JWT auth** with environment-injected secrets.

## Quick start (Docker)

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/` (default login `admin` / `admin`)
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

Detailed explanation and diagrams: [docs/architecture.md](docs/architecture.md)

## Repo layout

- `java-server/` – Spring Boot analysis microservice (AST complexity, bug detection, test generation, JWT security, optional Redis jobs)
- `python-agent/` – FastAPI LLM orchestrator + HTTP wrappers
- `docs/` – architecture, API reference, algorithms, security, deployment, and known limitations
- `docker-compose.yml`, `render.yaml` – deployment
- `.env.example` – environment variables

## Quick API overview

All Java `/api/**` endpoints require a JWT (except auth/config/health).

```bash
# get a token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

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
| [docs/known-limitations.md](docs/known-limitations.md) | Acknowledged limitations & future work |
| [docs/sample-analysis-report.md](docs/sample-analysis-report.md) | Real repository-analysis output |

## Environment variables

Core ones: `GROQ_API_KEY` (required), `OPENAI_API_KEY` (fallback), `JAVA_SERVER_URL`, `AUTH_USERNAME`/`AUTH_PASSWORD`, `JWT_SECRET`, `CORS_ALLOW_ORIGINS`. See [docs/deployment.md](docs/deployment.md) and `.env.example` for the full list.

## Testing

```bash
cd java-server && mvn test   # core suite: auth/JWT, analysis, cache, jobs, metrics
cd python-agent && python integration_test.py
```

## License

MIT
