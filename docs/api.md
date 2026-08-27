# API Reference

## Java server (`:8080`)

All `/api/**` endpoints require a JWT except `/api/auth/*` and `/api/config`, which are public. `GET /health`, `GET /ready`, and `/error` are also public.

### Auth

```bash
# Login → returns {"token":"<jwt>", ...}
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# Register
curl -X POST http://localhost:8080/api/auth/register \
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

# Bug findings (pattern + cached SpotBugs)
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

Jobs are idempotent: retrying the same type + payload yields the same idempotency key (SHA-256), so the queue does not create duplicates.

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

No auth (intended for trusted/internal use; see `docs/security.md`).

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

## Request limits & rate limiting (Python)

- `MAX_REQUEST_BYTES` (default `1500000`) — payloads larger than this get `413`.
- Rate limiting per IP: `RATE_LIMIT_REQUESTS` (default `60`) per `RATE_LIMIT_WINDOW_SECONDS` (default `60`) → `429` when exceeded.
