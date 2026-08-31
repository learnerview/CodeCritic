# Security

This is our security model and how we think about exposure. We treat CodeCritic as a learning/demo project that runs small workloads on our own infrastructure — the guidance below is what we follow, and what we recommend if you host it yourself.

## Authentication model

The Java API is protected by **stateless JWT** (HS256 via jjwt):

1. `POST /api/auth/register` creates a user (BCrypt-hashed password, stored in MongoDB). There are no preset accounts.
2. `POST /api/auth/login` validates credentials against MongoDB and returns a signed token valid for `JWT_EXPIRATION_MS` (default 24h).
3. Every other `/api/**` request requires `Authorization: Bearer <token>`.
4. `JwtAuthFilter` parses and verifies the token on each request and populates the Spring security context. Invalid/expired tokens are logged (not silently swallowed) and leave the context unauthenticated, which yields a `401`.
5. The signing secret comes from `JWT_SECRET` (min 32 bytes for HS256). A demo default exists only for local dev — always override it in production.

**Public (permit-all) endpoints** are configurable via `JwtProperties.permitAllPaths` and default to:
- `/error`, `/health`, `/ready`
- `/api/auth/login`, `/api/auth/register`, `/api/config`
- static assets (`/css/**`, `/js/**`, `/templates/**`) and dashboard pages (`/`, `/index.html`, `/review`, `/repository`, `/debug`, + `.html` variants)

`AUTH_USERNAME` / `AUTH_PASSWORD` are **not** the dashboard login — they are the service-account the Python agent uses to call the Java API (`python-agent` logs in with them to get a token). On your own instance, register a dedicated user and point these at it.

## Our threat model & important caveats

We keep these in mind every time we deploy:

- The Java backend **accepts raw Java source over HTTP** and may **compile it** in a temp directory to run SpotBugs (when a JDK + `spotbugs` CLI are present). This is not hardened against malicious input (e.g., pathological compile bombs, resource exhaustion). If you host an instance somewhere public, put it behind a strong sandbox — we only run ours for our own use.
- The Python agent endpoint **has no authentication** — it's meant for the trusted browser/Java service on an internal network. If you expose it, add auth + per-user secrets.
- Tokens are **not revocable** until expiry (stateless JWT). Logout is a client-side discard of the token.
- Secrets are read from **environment variables**, never hard-coded. The repo `.gitignore` excludes `.env`.

## Controls we already have in place

- Passwords hashed with BCrypt; secrets injected via env, never committed.
- Structured JSON logging correlates requests with ids; auth failures are logged.
- Payload-size limits and per-IP rate limiting on the Python agent prevent trivial abuse.
- CORS is locked down to configured origins (or `*` only in dev).
- Stateless design scales horizontally: no server-side session store.

## What you should change on your own instance

| Variable | Purpose | Req? |
|----------|---------|------|
| `GROQ_API_KEY` | Primary LLM | Required |
| `OPENAI_API_KEY` | Fallback LLM | Optional |
| `JWT_SECRET` | Token signing (≥32 bytes) | Required (prod) |
| `GITHUB_TOKEN` | Private repos / higher API limits | Optional |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | Service account the Python agent logs in with | Optional (create a dedicated user) |

Rotate `JWT_SECRET` and any service-account credentials before any real deployment, and never use the demo defaults outside local development. If you tighten `permitAllPaths` or the rate limits (`RATE_LIMIT_REQUESTS` / `MAX_REQUEST_BYTES`), you can find all of those knobs in [docs/deployment.md](docs/deployment.md).