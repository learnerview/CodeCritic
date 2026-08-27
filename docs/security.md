# Security

## Authentication model

The Java API is protected by **stateless JWT** (HS256 via jjwt):

1. `POST /api/auth/login` validates `AUTH_USERNAME`/`AUTH_PASSWORD` (stored via BCrypt) and returns a signed token valid for `JWT_EXPIRATION_MS` (default 24h).
2. Every other `/api/**` request requires `Authorization: Bearer <token>`.
3. `JwtAuthFilter` parses and verifies the token on each request and populates the Spring security context. Invalid/expired tokens are logged (not silently swallowed) and leave the context unauthenticated, which yields a `401`.
4. The signing secret comes from `JWT_SECRET` (min 32 bytes for HS256). A demo default exists only for local dev — always override it in production.

**Public (permit-all) endpoints** are configurable via `JwtProperties.permitAllPaths` and default to:
- `/error`, `/health`, `/ready`
- `/api/auth/login`, `/api/auth/register`, `/api/config`
- static assets (`/css/**`, `/js/**`, `/templates/**`) and dashboard pages (`/`, `/index.html`, `/review`, `/repository`, `/debug`, + `.html` variants)

## Threat model & important caveats

This project is a **local-development, demo, and interview** tool. Treat it accordingly:

- The Java backend **accepts raw Java source over HTTP** and may **compile it** in a temp directory to run SpotBugs. This is not hardened against malicious input (e.g., pathological compile bombs, resource exhaustion). Do not expose it to the public internet without a strong sandbox.
- The Python agent endpoint **has no authentication** — it is meant for the trusted browser/Java service on an internal network. If exposed, add auth + per-user secrets.
- Tokens are **not revocable** until expiry (stateless JWT). Logout is a client-side discard of the token.
- Secrets are read from **environment variables**, never hard-coded. The repo `.gitignore` excludes `.env`.

## Good practices that are in place

- Passwords hashed with BCrypt; secrets injected via env, never committed.
- Structured JSON logging correlates requests with ids; auth failures are logged.
- Payload-size limits and per-IP rate limiting on the Python agent prevent trivial abuse.
- CORS is locked down to configured origins (or disabled/`*` only in dev).
- Stateless design scales horizontally: no server-side session store.

## Secrets guidance

| Variable | Purpose | Req? |
|----------|---------|------|
| `GROQ_API_KEY` | Primary LLM | Required |
| `OPENAI_API_KEY` | Fallback LLM | Optional |
| `JWT_SECRET` | Token signing (≥32 bytes) | Required (prod) |
| `GITHUB_TOKEN` | Private repos / higher API limits | Optional |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | Dashboard + agent credentials | Optional (default admin/admin) |

Rotate `JWT_SECRET` and shared credentials before any real deployment, and never use the demo defaults outside local development.
