# API Reference

These are the endpoints we expose for CodeCritic. On our live demo the Java server is at `https://codecritic-java.onrender.com`; when you host your own instance, the base URLs are just wherever you run the two services (`JAVA_SERVER_URL` / port assignments — see [docs/deployment.md](docs/deployment.md)).

## Java server (`:8080`)

All `/api/**` endpoints require a JWT except `/api/auth/*` and `/api/config`, which are public. `GET /health`, `GET /ready`, and `/error` are also public.

### Auth

There are no preset accounts — everyone registers their own. Then you log in with those same credentials:

```bash
# Create your account (returns a token too)
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'

# Login → returns {"token":"<jwt>", ...}
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"me","password":"secret"}'

export TOKEN="<jwt>"
```

### Deterministic analysis

```bash
# Cyclomatic + cognitive complexity
curl -X POST http://localhost:8080/api/complexity \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"public class A { public int f(int x){ if(x>0){ return x; } return 0; } }"}'
# → {"cyclomaticComplexity":2,"cognitiveComplexity":1}

# Bug findings (pattern detector, best-effort SpotBugs when installed)
curl -X POST http://localhost:8080/api/bugs \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"public class A { public int f(int x){ return 10 / x; } }"}'
# → {"bugs":[{"type":"DivisionByZeroRisk","line":1,"message":"...","suggestion":"..."}]}

# JUnit 5 scaffold
curl -X POST http://localhost:8080/api/generate-test \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"className":"Calculator","methodName":"add","parameters":"int a, int b","code":"public class Calculator { public int add(int a, int b){ return a + b; } }"}'
```

### Async jobs (Redis/SimplyDone4J)

Each analysis endpoint has a `POST /api/jobs/*` variant returning `{"jobId":"<id>","status":"QUEUED"}`. Poll `GET /api/jobs/{id}` for the result.

```bash
curl -X POST http://localhost:8080/api/jobs/complexity \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"public class A { public int f(int x){ return x; } }"}'
curl http://localhost:8080/api/jobs/<id> -H "Authorization: Bearer $TOKEN"
```

Jobs are idempotent: retrying the same type + payload yields the same idempotency key (SHA-256), so the queue does not create duplicates. Async jobs need the scheduler/Redis enabled on your instance (`REDIS_URL` + `SIMPLYDONE4J_SCHEDULER_ENABLED`) — on Render we enable them via the managed Redis service, but the sync endpoints above work without them.

### Metrics

```bash
curl http://localhost:8080/api/metrics -H "Authorization: Bearer $TOKEN"
```

### Health / config

```bash
curl http://localhost:8080/health       # public
curl http://localhost:8080/api/config   # public; tells the browser where the agent lives
```

## Python agent (`:8000`)

No auth — intended for trusted/internal use between our own services (see [docs/security.md](docs/security.md)).

```bash
# Review pipeline (deterministic analysis + LLM review)
curl -X POST http://localhost:8000/review \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int divide(int a,int b){ return a/b; } }"}'

# Full LLM JUnit test suite
curl -X POST http://localhost:8000/generate-tests \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int add(int a,int b){ return a+b; } }"}'

# GitHub repository analysis
curl -X POST http://localhost:8000/analyze-repository \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","branch":null,"maxFiles":15,"githubToken":null}'

# Debug/diagnosis
curl -X POST http://localhost:8000/debug \
  -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"ArithmeticException: / by zero"}'

curl http://localhost:8000/health   # liveness
curl http://localhost:8000/ready    # readiness incl. LLM connectivity
curl http://localhost:8000/metrics  # request/status/latency metrics
```

## Limits you can tune on your own instance

- `MAX_REQUEST_BYTES` (default `1500000`) — payloads larger than this get `413`.
- Rate limiting per IP: `RATE_LIMIT_REQUESTS` (default `60`) per `RATE_LIMIT_WINDOW_SECONDS` (default `60`) → `429` when exceeded.
- Which endpoints are public (`permitAllPaths` in `JwtProperties`) and how long tokens live (`JWT_EXPIRATION_MS`).

See [docs/deployment.md](docs/deployment.md) for the full list of variables.