# Security

This is our security model and how we think about exposure. We treat CodeCritic as a learning/demo project that runs small workloads on our own infrastructure — the guidance below is what we follow, and what we recommend if you host it yourself.

## Authentication model

The Java API is protected by **stateless JWT** (HS256 via jjwt):

1. `POST /api/auth/register` creates a user (BCrypt-hashed password, stored in MongoDB). There are no preset accounts.
2. `POST /api/auth/login` validates credentials against MongoDB and returns a signed token valid for `JWT_EXPIRATION_MS` (default 24h).
3. Every other `/api/**` request requires `Authorization: Bearer <token>`.
4. `JwtAuthFilter` parses and verifies the token on each request and populates the Spring security context. Invalid/expired tokens are logged (not silently swallowed) and leave the context unauthenticated, which yields a `401`.
5. The signing secret comes from `JWT_SECRET` (min 32 bytes for HS256). The published demo default exists only under the explicit local-development `dev`/`test` profiles — any other profile, or none, refuses to start with it.

**Public (permit-all) endpoints** — the roots below are configurable via `JwtProperties.permitAllPaths` and default to:
- `/error`, `/health`
- `/api/auth/login`, `/api/auth/register`, `/api/config`

Static assets (`/css/**`, `/js/**`, `/templates/**`) and the dashboard pages (`/`, `/index.html`, `/review`, `/repository`, `/debug` and their `.html` variants) are additionally permitted in `WebSecurityConfig`, since the browser must load the UI before any token exists.

`AUTH_USERNAME` / `AUTH_PASSWORD` are **not** the dashboard login — they are the service-account the Python agent uses to call the Java API (`python-agent` logs in with them to get a token). On your own instance, register a dedicated user and point these at it. The `admin`/`admin` default exists only under the explicit `dev`/`test` profiles; on any other profile, or with no profile set, the server **fails to start** when `AUTH_PASSWORD` is unset or a well-known default, so a reachable deployment is never left guarding `/api/**` with a published password.

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
- CORS is locked down to configured origins. A wildcard origin (`CORS_ALLOW_ORIGINS=*`)
  is permitted **only** without credentials: `*` in `allowedOriginPatterns` makes Spring
  echo the caller's `Origin` back, so pairing it with `allowCredentials(true)` would let
  any website make a credentialed request against `/api/**` and read the response. Setting
  an explicit allowlist re-enables credentials; the server refuses the unsafe combination.
- The default service account (`AUTH_USERNAME`/`AUTH_PASSWORD`) is **not** provisioned and the
  app **fails to start** on any profile other than the explicit `dev`/`test` profiles when the
  password is unset or a well-known default such as `admin`. The same default-deny guard covers
  `JWT_SECRET` (blank, too short, or the committed demo secret). A deployment that simply
  forgets to set a profile is treated as production, so the safe behavior does not depend on
  remembering to opt in. Set real secrets before the first deploy, or run with
  `SPRING_PROFILES_ACTIVE=dev` for local development; the demo defaults live only in
  `application-dev.yml`.
- Stateless design scales horizontally: no server-side session store.
- **Prompt-injection hardening on every LLM entry point** (`agent.py`): all user-supplied
  code, logs, and repository content is wrapped in unambiguous `UNTRUSTED DATA` fences
  with a standing instruction to treat anything inside them as data, never as commands.
  Fenced content that echoes the closing marker is neutralized so injected text cannot
  "break out" and impersonate the prompt (see `_fence_untrusted` and
  `_PROMPT_INJECTION_GUARD` in `python-agent/agent.py`).

## What you should change on your own instance

| Variable | Purpose | Req? |
|----------|---------|------|
| `GROQ_API_KEY` | Primary LLM | Required |
| `OPENAI_API_KEY` | Fallback LLM | Optional |
| `JWT_SECRET` | Token signing (≥32 bytes) | Required (outside dev/test) |
| `GITHUB_TOKEN` | Private repos / higher API limits | Optional |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | Service account the Python agent logs in with | Optional (create a dedicated user) |

Rotate `JWT_SECRET` and any service-account credentials before any real deployment, and never use the demo defaults outside local development. If you tighten `permitAllPaths` or the rate limits (`RATE_LIMIT_REQUESTS` / `MAX_REQUEST_BYTES`), you can find all of those knobs in [docs/deployment.md](deployment.md).

## Live secrets on a local machine (act now)

A working copy of this repo contains an **untracked `.env` with a live `GROQ_API_KEY`** (plus the local `AUTH_*` demo values). `.env` is listed in `.gitignore`, so it is not committed — keep it that way. If you have been handed this checkout, or you are the operator:

1. **Rotate the `GROQ_API_KEY`** in the Groq console immediately; treat the value currently in `.env` as compromised.
2. **Never commit `.env`** (or any file containing a real key). Commit only `.env.example`, which ships placeholders.
3. If a key is ever pushed, rotate it *and* purge it from history — deleting the file in a later commit is not enough.
4. Set `OPENAI_API_KEY` only if you want the fallback provider; leave it blank otherwise.

The same applies to any `GITHUB_TOKEN`, `JWT_SECRET`, or database URI you paste locally.