# Deployment

Prerequisites: JDK 21, Maven 3.9+, Python 3.11+ (or Docker).

## Live demo

We run a public instance of the Java server on Render's free tier:

- **https://codecritic-java.onrender.com/** — live dashboard. There's no preset account; register one and sign in.

It sleeps after ~15 minutes of idle time, so give it a moment to wake up on first load.

## Run with Docker (recommended)

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/` — register an account on first visit (no preset login)
- Python agent: `http://localhost:8000/`
- Compose wires `JAVA_SERVER_URL=http://java-server:8080/api` plus Redis (for the job queue) and MongoDB (for auth users) containers; env vars fall back to dev defaults if unset.

## Run locally without Docker

Start the **Python agent first**, then the **Java server** (the agent pings Java health on startup). If the scheduler is enabled (`SIMPLYDONE4J_SCHEDULER_ENABLED=true`), Redis must be running; otherwise set it to `false`.

### Python agent

```bash
cd python-agent
python -m venv venv
# Windows: .\venv\Scripts\activate
source venv/bin/activate
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

Verify: `GET http://localhost:8000/ready` → `"llmConnectivity":{"ok":true}` (needs `GROQ_API_KEY`).

### Java server

```bash
cd java-server
mvn package
java -jar target/java-server-0.1.0.jar
```

Open `http://localhost:8080/` and sign in.

## Host it yourself — what you can change

Everything below lives behind an environment variable or a config file, so hosting your own instance is a matter of setting values, not editing code. The table in the next section is the complete switchboard.

**Service identity**

- Whether to run the dashboard + analysis on your own domain, and which `JAVA_SERVER_URL` / `PYTHON_AGENT_URL` the pieces use to find each other.
- Which port `java-server` listens on (`PORT`).

**LLM**

- Your own `GROQ_API_KEY` and `OPENAI_API_KEY`, provider fallback on/off, and the model ids (`GROQ_MODEL` / `OPENAI_MODEL`) — swap in any model you have access to.
- LLM call timeout (`LLM_TIMEOUT_SECONDS`).

**Auth & access**

- Who can register: registration is always on (self-service). `AUTH_USERNAME` / `AUTH_PASSWORD` are the service-account the Python agent uses to call the Java API — create a dedicated user for that, and keep it out of the dashboard flow.
- `JWT_SECRET` and token lifetime (`JWT_EXPIRATION_MS`) are yours to set; see [docs/security.md](docs/security.md).
- `CORS_ALLOW_ORIGINS` controls which browser origins are allowed.

**Scale & cost**

- How much memory the JVM may take (the ENTRYPOINT flags in `java-server/Dockerfile`, currently tuned for a 512 MB container), Tomcat thread count (`TOMCAT_MAX_THREADS`), and the SimplyDone4J executor sizes.
- Whether async jobs use Redis (`REDIS_URL`, `SIMPLYDONE4J_SCHEDULER_ENABLED`) and whether the Mongo persistence uses a local instance or a managed cluster (`SPRING_DATA_MONGODB_URI`).

**Abuse protection**

- Payload ceiling (`MAX_REQUEST_BYTES`) and the per-IP rate limit (`RATE_LIMIT_REQUESTS` / `RATE_LIMIT_WINDOW_SECONDS`).

## Environment variables

Create a local `.env` from `.env.example`.

| Variable | Purpose | Default |
|----------|---------|---------|
| `GROQ_API_KEY` | Primary LLM | — |
| `OPENAI_API_KEY` | Fallback LLM | — |
| `GROQ_MODEL` / `OPENAI_MODEL` | Model ids | `llama-3.3-70b-versatile` / `gpt-4o-mini` |
| `JAVA_SERVER_URL` | Java base URL used by the agent | `http://localhost:8080/api` |
| `GITHUB_TOKEN` | Private repos / API limits | — |
| `CORS_ALLOW_ORIGINS` | Allowed browser origins | `*` |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | Service account the Python agent logs in with | local dev fallback only |
| `JWT_SECRET` | Token signing (≥32 bytes) | demo default (override) |
| `JWT_EXPIRATION_MS` | Token lifetime | `86400000` (24h) |
| `RATE_LIMIT_REQUESTS` / `RATE_LIMIT_WINDOW_SECONDS` | Python rate limit | `60` / `60` |
| `MAX_REQUEST_BYTES` | Python payload limit | `1500000` |
| `LLM_TIMEOUT_SECONDS` | LLM call timeout | `60` |
| `REDIS_URL` | Job-queue Redis | — |
| `SIMPLYDONE4J_SCHEDULER_ENABLED` | Async scheduler (Java default `false`; the Render blueprint sets `true`) | `false` |

## Deploy to Render (as we do)

A ready `render.yaml` blueprint deploys both services on the free tier:

1. Push this repo to GitHub.
2. **New → Blueprint** and select the repo.
3. Set `GROQ_API_KEY` (and optionally `GITHUB_TOKEN`); `JWT_SECRET` is auto-generated.
4. Java = `https://codecritic-java.onrender.com`, Python = `https://codecritic-python.onrender.com`. The dashboard auto-detects the agent URL via `/api/config` (`PYTHON_AGENT_URL`).

Blueprint notes:

- Region `singapore` (closest to India; Render has no Mumbai region).
- **Free tier = 512 MB RAM**: the JVM is capped in `java-server/Dockerfile` (`-Xmx256m`, `-Xms64m`, metaspace 128m, code cache 64m, `UseSerialGC`, `ExitOnOutOfMemoryError`) so the container stays inside budget; `MAVEN_OPTS=-Xmx512m` keeps the Docker build inside budget too. On a bigger plan you're free to raise these.
- **Render managed Redis** is wired via the blueprint (`REDIS_URL` from the `codecritic-redis` service) and the SimplyDone4J scheduler/monitoring are enabled there, so async jobs work on the free tier.
- Health checks on `/health` for both services.

Free-tier caveats: instances sleep after ~15 min of inactivity (cold boot ~30–60s), and the 750 free hours/month are shared (~375h each). Upgrade to a paid plan for always-on.