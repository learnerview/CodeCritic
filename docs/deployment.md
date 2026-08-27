# Deployment

Prerequisites: JDK 21, Maven 3.9+, Python 3.11+ (or Docker).

## Run with Docker (recommended)

```bash
docker compose up --build
```

- Dashboard: `http://localhost:8080/` (login `admin` / `admin` by default)
- Python agent: `http://localhost:8000/`
- Compose wires `JAVA_SERVER_URL=http://java-server:8080/api` and a memory-capped Redis container for the job queue.

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

## Environment variables

Create a local `.env` from `.env.example`.

| Variable | Purpose | Default |
|----------|---------|---------|
| `GROQ_API_KEY` | Primary LLM | — |
| `OPENAI_API_KEY` | Fallback LLM | — |
| `GROQ_MODEL` / `OPENAI_MODEL` | Model ids | `llama-3.3-70b-versatile` / `gpt-4o-mini` |
| `JAVA_SERVER_URL` | Java base URL | `http://localhost:8080/api` |
| `GITHUB_TOKEN` | Private repos / API limits | — |
| `CORS_ALLOW_ORIGINS` | Allowed browser origins | `*` |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | Dashboard + agent creds | `admin` / `admin` |
| `JWT_SECRET` | Token signing (≥32 bytes) | demo default |
| `JWT_EXPIRATION_MS` | Token lifetime | `86400000` (24h) |
| `RATE_LIMIT_REQUESTS` / `RATE_LIMIT_WINDOW_SECONDS` | Python rate limit | `60` / `60` |
| `MAX_REQUEST_BYTES` | Python payload limit | `1500000` |
| `LLM_TIMEOUT_SECONDS` | LLM call timeout | `60` |
| `REDIS_URL` | Job-queue Redis | — |
| `SIMPLYDONE4J_SCHEDULER_ENABLED` | Async scheduler | `false` on Render |

## Deploy to Render

A ready `render.yaml` blueprint deploys both services on the free tier:

1. Push this repo to GitHub.
2. **New → Blueprint** and select the repo.
3. Set `GROQ_API_KEY` (and optionally `GITHUB_TOKEN`); `JWT_SECRET` is auto-generated.
4. Java = `https://codecritic-java.onrender.com`, Python = `https://codecritic-python.onrender.com`. The dashboard auto-detects the agent URL via `/api/config` (`PYTHON_AGENT_URL`).

Blueprint notes:
- Region `singapore` (closest to India; Render has no Mumbai region).
- **Free plan, hard memory caps**: JVM capped (`-Xmx256m`, metaspace 128m, code cache 64m, `UseSerialGC`, exit-on-OOM); executor pool capped (1 core / 2 max / 20 queue); `MAVEN_OPTS=-Xmx512m` keeps the build inside budget.
- **No Redis on Render by default** (`SIMPLYDONE4J_SCHEDULER_ENABLED=false`, `SIMPLYDONE4J_MONITORING_ENABLED=false`). Add an Upstash `REDIS_URL` and flip these on to enable async jobs.
- Health checks on `/health` for both services.

Free-tier caveats: instances sleep after ~15 min of inactivity (cold boot ~30–60s), and the 750 free hours/month are shared (~375h each). Upgrade to a paid plan for always-on.
