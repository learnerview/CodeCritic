# API Reference

These are the public Java endpoints exposed by CodeCritic. The Python agent is
an internal implementation detail and is never called directly by the browser.

## Java server (`:8080`)

All `/api/**` endpoints require a JWT except `/api/auth/*` and `/api/config`, which are public. `GET /health` and `/error` are also public.

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

Jobs are idempotent: the idempotency key is a **SHA-256 of the job type plus a canonical JSON encoding of the full payload** (map keys sorted at every nesting level, list order preserved), so identical submissions always map to the same key and distinct payloads cannot collide. Async jobs need the scheduler/Redis enabled on the instance; the sync endpoints above work without it. If the queue backend is unreachable, the request is transparently executed on a bounded in-memory fallback pool so the submit → poll contract still holds (see [docs/architecture.md](architecture.md)).

### Metrics

```bash
curl http://localhost:8080/api/metrics -H "Authorization: Bearer $TOKEN"
```

### Health / config

```bash
curl http://localhost:8080/health       # public
curl http://localhost:8080/api/config   # public UI-safe feature configuration
```

## Advisory review routes

These routes are handled by Java and then forwarded to the internal Python agent
with the service credential. Clients must use the Java base URL and a JWT.

```bash
# Review pipeline (deterministic analysis + LLM review)
curl -X POST http://localhost:8080/api/review \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int divide(int a,int b){ return a/b; } }"}'

# Full LLM JUnit test suite
curl -X POST http://localhost:8080/api/generate-tests \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int add(int a,int b){ return a+b; } }"}'

# GitHub repository analysis
curl -X POST http://localhost:8080/api/analyze-repository \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","branch":null,"maxFiles":15,"githubToken":null}'

# Debug/diagnosis
curl -X POST http://localhost:8080/api/debug \
  -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"ArithmeticException: / by zero"}'

The Python health and metrics endpoints are internal diagnostics, not part of
the public browser API.
```

A worked example of the `/analyze-repository` response format (deterministic metrics plus the LLM summary) is in [docs/sample-analysis-report.md](sample-analysis-report.md).

## Limits you can tune on your own instance

- `MAX_REQUEST_BYTES` (default `1500000`) — payloads larger than this get `413`. This ceiling applies to every request body on the Python agent.
- Repository analysis accepts `maxFiles` from `1` through `MAX_REPO_FILES` (**default `30`**), defaulting to `15` when omitted; larger or invalid values are rejected with `422` before any GitHub work starts. Inside `build_repository_context` the value is clamped again with `max(1, min(maxFiles, MAX_REPO_FILES))`, and the response reports `maxFilesRequested` / `maxFilesApplied` / `filesDiscovered` / `analyzedFileCount` so the effective limit is never silent. The dashboard input mirrors this with `min="1" max="30" value="15"`.
- Rate limiting per IP: `RATE_LIMIT_REQUESTS` (default `60`) per `RATE_LIMIT_WINDOW_SECONDS` (default `60`) → `429` when exceeded.
- Which endpoints are public (`permitAllPaths` in `JwtProperties`) and how long tokens live (`JWT_EXPIRATION_MS`).

See the source configuration classes and [docs/known-limitations.md](known-limitations.md)
for request limits and intentional behavior.