# Security Policy

CodeCritic is a learning/demo project that runs small workloads on its own infrastructure. This file is the security entry point; the detailed model and threat analysis live in [docs/security.md](docs/security.md).

## Reporting a vulnerability

Please report suspected vulnerabilities privately (do **not** open a public issue with exploit details). Use the repository's private security-advisory channel if available; otherwise contact the maintainers directly. Include a description, reproduction steps, and impact. There is no formal SLA — this is a demo project — but reports will be acknowledged as soon as possible.

## ⚠️ Live secret in this working copy — rotate it

A working copy of this repository contains an **untracked `.env` file with a real, live `GROQ_API_KEY`** (alongside the local `AUTH_USERNAME`/`AUTH_PASSWORD` demo values). `.env` is listed in `.gitignore` and must never be committed.

If you maintain or have received this checkout:

1. **Rotate the `GROQ_API_KEY` immediately** in the provider console; treat the current value as compromised.
2. **Never commit `.env`** or any file containing a real key. Commit only `.env.example`, which ships placeholders.
3. If a key has ever been pushed, rotate it *and* purge it from git history — deleting it in a later commit is not sufficient.
4. The same applies to `OPENAI_API_KEY`, `GITHUB_TOKEN`, `JWT_SECRET`, and any database URI.

No secret is required to run the deterministic analysis and dashboard; LLM features degrade gracefully to deterministic output when no key is configured.

## Security model (summary)

- **Authentication:** stateless JWT (HS256) for the Java `/api/**` surface; BCrypt-hashed users in MongoDB; no preset accounts.
- **Default-deny startup:** outside the explicit `dev`/`test` profiles, the server **refuses to start** if `AUTH_PASSWORD` is unset/well-known or `JWT_SECRET` is the committed demo value. Demo defaults exist only in `java-server/src/main/resources/application-dev.yml`; `docker-compose.yml` opts into `dev` for local use.
- **CORS:** wildcard origins are allowed only without credentials; an explicit allowlist re-enables credentialed requests.
- **Input hardening:** Python request bodies are size-capped (`MAX_REQUEST_BYTES`) and per-IP rate-limited; all user/repository content sent to an LLM is wrapped in untrusted-data fences with a prompt-injection guard, and fenced content that echoes the closing marker is neutralized.
- **Job access control:** a job's payload/result is only readable by the producer that owns it (per-job-type owner check).

## Known caveats (read before exposing an instance)

- The Java service **accepts and compiles raw Java source** (for SpotBugs) in a temp directory. It is **not** hardened against malicious input (compile bombs, resource exhaustion). Run it behind a sandbox if exposed publicly.
- The **Python agent has no authentication**; it is meant for trusted internal/browser traffic only. Add auth if you expose it.
- Tokens are **not revocable** before expiry (stateless JWT); logout is client-side.
- The default `CORS_ALLOW_ORIGINS=*` is fine for local development but should be an explicit allowlist in any reachable deployment.

## Handling secrets

- Secrets are read from **environment variables**, never hard-coded.
- `.gitignore` excludes `.env`; commit `.env.example` only.
- On Render, the blueprint generates `JWT_SECRET` and `AUTH_PASSWORD` (`generateValue: true`) rather than committing values.
- Rotate `JWT_SECRET` and service-account credentials before any real deployment.

See [docs/security.md](docs/security.md) for the full threat model and [docs/deployment.md](docs/deployment.md) for every tunable variable.

## Secret Manager Integration

For production deployments, secrets should not be distributed as plain environment variables.
Instead, integrate a secret manager and inject values at startup:

- **HashiCorp Vault.** Use the Vault Agent sidecar or the Spring Cloud Vault starter to read
  `JWT_SECRET`, `EXTERNAL_AUTH_JWT_SECRET`, database credentials, and webhook signing keys from a
  mounted secret path at startup. The application continues to read from `System.getenv()`, so the
  sidecar can write values to a `.env` file or inject them directly into the container environment
  before the JVM starts.
- **AWS Secrets Manager.** Store secrets as JSON secret values. A small init container or entrypoint
  script fetches the secret via the AWS CLI/SDK and exports the individual keys as environment
  variables before launching the application. Never embed the secret value in the Docker image.
- **Azure Key Vault / GCP Secret Manager.** Equivalent pattern: init container fetches the named
  secret versions and exports them as environment variables; the application reads them via Spring's
  `${ENV_VAR:default}` property resolution.

**Rotation.** Rotate `JWT_SECRET` and webhook signing keys on a schedule. After rotation, restart
the application so the new key material is loaded. Old JWTs signed with the previous secret will
fail verification, which is the intended behavior for compromised-key rotation.

**Do not.** Do not commit `.env`, `.env.production`, `secrets.json`, or any file containing key
material to version control. `.env.example` may contain placeholder names only.

