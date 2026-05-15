"""FastAPI wrapper for the CodeCritic Python agent.

This app exists so the Python container can stay alive and expose a small HTTP surface
for Docker-based demos. The agent orchestration stays in `agent.py` so the app remains
thin and easy to reason about.
"""

import os
import time
import uuid
import asyncio
import logging
from pythonjsonlogger import jsonlogger
from collections import defaultdict, deque

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from agent import (
    run_review_pipeline,
    generate_full_test_suite,
    analyze_github_repository,
    analyze_debug_issue,
    build_repository_context,
    retrieve_repository_context,
    propose_fix_and_verify,
    run_eval_suite,
    LLM,
)


class ReviewRequest(BaseModel):
    """Request payload for submitting Java source to the agent."""

    code: str


class ReviewResponse(BaseModel):
    """Response payload with the agent's synthesized review."""

    review: str


class ReviewErrorResponse(BaseModel):
    """Structured error payload returned when review synthesis fails."""

    error: str


class TestGenerationRequest(BaseModel):
    code: str


class TestGenerationResponse(BaseModel):
    tests: str


class RepositoryAnalysisRequest(BaseModel):
    repoUrl: str
    branch: str | None = None
    maxFiles: int = 15
    githubToken: str | None = None


class RepositoryAnalysisResponse(BaseModel):
    repository: dict
    filesAnalyzed: list[dict]
    javaStaticFindings: list[dict]
    aiSummary: str


class DebugRequest(BaseModel):
    code: str
    errorLog: str
    language: str = "java"


class DebugResponse(BaseModel):
    diagnosis: str


class RepositoryContextRequest(BaseModel):
    repoUrl: str
    query: str
    branch: str | None = None
    maxFiles: int = 15
    topK: int = 5
    githubToken: str | None = None


class RepositoryContextResponse(BaseModel):
    repository: dict
    query: str
    contexts: list[dict]


class FixRequest(BaseModel):
    code: str
    errorLog: str
    language: str = "java"


class FixResponse(BaseModel):
    diagnosis: str
    fixedCode: str
    verification: dict


# Setup Structured Logging
logger = logging.getLogger("codecritic")
logHandler = logging.StreamHandler()
formatter = jsonlogger.JsonFormatter('%(asctime)s %(levelname)s %(name)s %(message)s %(requestId)s')
logHandler.setFormatter(formatter)
logger.addHandler(logHandler)
logger.setLevel(logging.INFO)

app = FastAPI(title="CodeCritic Agent", version="0.1.0")
cors_origins = [origin.strip() for origin in os.getenv("CORS_ALLOW_ORIGINS", "*").split(",") if origin.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins if cors_origins else ["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

MAX_REQUEST_BYTES = int(os.getenv("MAX_REQUEST_BYTES", "1500000"))
RATE_LIMIT_REQUESTS = int(os.getenv("RATE_LIMIT_REQUESTS", "60"))
RATE_LIMIT_WINDOW_SECONDS = int(os.getenv("RATE_LIMIT_WINDOW_SECONDS", "60"))
API_AUTH_TOKEN = os.getenv("API_AUTH_TOKEN", "").strip()

REQUEST_TIMES_BY_IP: dict[str, deque[float]] = defaultdict(deque)
METRICS: dict[str, object] = {
    "startedAtEpochMs": int(time.time() * 1000),
    "requestCount": 0,
    "statusCounts": defaultdict(int),
    "endpointCounts": defaultdict(int),
    "latencyByEndpointMs": defaultdict(list),
}


def _auth_required(path: str) -> bool:
    public = {"/health", "/ready", "/metrics"}
    return path not in public

def is_java_code(code: str) -> bool:
    """Basic heuristic to detect if the code is Java."""
    if len(code) < 10: return False
    indicators = ["class ", "package ", "import ", "void ", "public ", "private "]
    # Require at least two indicators to reduce false positives
    hits = sum(1 for ind in indicators if ind in code)
    return hits >= 2


@app.middleware("http")
async def production_middleware(request: Request, call_next):
    request_id = str(uuid.uuid4())
    request.state.request_id = request_id
    started = time.time()
    endpoint = request.url.path
    ip = request.client.host if request.client else "unknown"

    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > MAX_REQUEST_BYTES:
                return JSONResponse(
                    status_code=413,
                    content={"detail": "Payload too large", "requestId": request_id},
                    headers={"X-Request-Id": request_id},
                )
        except ValueError:
            return JSONResponse(
                status_code=400,
                content={"detail": "Invalid content-length header", "requestId": request_id},
                headers={"X-Request-Id": request_id},
            )

    now = time.time()
    queue = REQUEST_TIMES_BY_IP[ip]
    while queue and now - queue[0] > RATE_LIMIT_WINDOW_SECONDS:
        queue.popleft()
    if len(queue) >= RATE_LIMIT_REQUESTS:
        return JSONResponse(
            status_code=429,
            content={"detail": "Rate limit exceeded", "requestId": request_id},
            headers={"X-Request-Id": request_id},
        )
    queue.append(now)

    if API_AUTH_TOKEN and _auth_required(endpoint):
        token = request.headers.get("x-api-token", "")
        if token != API_AUTH_TOKEN:
            return JSONResponse(
                status_code=401,
                content={"detail": "Unauthorized", "requestId": request_id},
                headers={"X-Request-Id": request_id},
            )

    response = await call_next(request)
    latency_ms = int((time.time() - started) * 1000)
    response.headers["X-Request-Id"] = request_id
    response.headers["X-Process-Time-Ms"] = str(latency_ms)

    logger.info(
        "Request processed",
        extra={
            "requestId": request_id,
            "method": request.method,
            "path": endpoint,
            "status": response.status_code,
            "latencyMs": latency_ms,
            "ip": ip
        }
    )

    METRICS["requestCount"] = int(METRICS["requestCount"]) + 1
    status_counts = METRICS["statusCounts"]
    endpoint_counts = METRICS["endpointCounts"]
    latency_map = METRICS["latencyByEndpointMs"]
    status_counts[str(response.status_code)] += 1
    endpoint_counts[endpoint] += 1
    latency_map[endpoint].append(latency_ms)
    if len(latency_map[endpoint]) > 200:
        latency_map[endpoint] = latency_map[endpoint][-200:]
    return response


@app.get("/health")
def health() -> dict[str, str]:
    """Lightweight health check used by Docker and manual smoke tests."""

    return {"status": "ok"}


@app.get("/metrics")
def metrics() -> dict:
    latency_summary = {}
    for endpoint, values in METRICS["latencyByEndpointMs"].items():
        if not values:
            continue
        avg = sum(values) / len(values)
        p95 = sorted(values)[int(max(0, min(len(values) - 1, len(values) * 0.95 - 1)))]
        latency_summary[endpoint] = {"avgMs": round(avg, 2), "p95Ms": p95, "samples": len(values)}
    return {
        "startedAtEpochMs": METRICS["startedAtEpochMs"],
        "requestCount": METRICS["requestCount"],
        "statusCounts": dict(METRICS["statusCounts"]),
        "endpointCounts": dict(METRICS["endpointCounts"]),
        "latencyByEndpoint": latency_summary,
        "rateLimit": {"requests": RATE_LIMIT_REQUESTS, "windowSeconds": RATE_LIMIT_WINDOW_SECONDS},
        "maxRequestBytes": MAX_REQUEST_BYTES,
        "authEnabled": bool(API_AUTH_TOKEN),
    }


@app.get("/ready")
def ready() -> dict:
    java_server_url = os.getenv("JAVA_SERVER_URL", "http://localhost:8080/api")
    status = LLM.provider_status()
    llm_check = LLM.validate_connection()
    return {
        "status": "ok" if llm_check.get("ok") else "degraded",
        "javaServerUrl": java_server_url,
        "llm": status,
        "llmConnectivity": llm_check,
    }


@app.on_event("startup")
async def startup_check():
    import httpx
    java_url = os.getenv("JAVA_SERVER_URL", "http://localhost:8080/api")
    logger.info("Startup check: Waiting for Java server", extra={"javaUrl": java_url, "requestId": "system"})
    for i in range(10):
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get(f"{java_url}/health", timeout=2.0)
                if resp.status_code == 200:
                    logger.info("Java server is UP", extra={"requestId": "system"})
                    return
        except Exception:
            pass
        logger.warn("Java server not ready, retrying...", extra={"attempt": i + 1, "requestId": "system"})
        await asyncio.sleep(2)
    logger.error("Java server failed to become ready in time", extra={"requestId": "system"})

@app.post("/review", response_model=ReviewResponse | ReviewErrorResponse)
def review(request: ReviewRequest) -> ReviewResponse | ReviewErrorResponse:
    if not is_java_code(request.code):
        logger.warn("Rejected non-Java code submission", extra={"requestId": "none"})
        raise HTTPException(status_code=400, detail="Only Java code is supported for review")
    """Run the full review pipeline and return either a review or a structured error.

    Why this shape:
    - The agent is a demo tool, so it should fail gracefully instead of returning an opaque 500.
    - A structured error makes Docker demos and CLI calls easier to debug.
    """

    try:
        return ReviewResponse(review=run_review_pipeline(request.code))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/generate-tests", response_model=TestGenerationResponse | ReviewErrorResponse)
def generate_tests(request: TestGenerationRequest) -> TestGenerationResponse | ReviewErrorResponse:
    try:
        return TestGenerationResponse(tests=generate_full_test_suite(request.code))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/analyze-repository", response_model=RepositoryAnalysisResponse | ReviewErrorResponse)
def analyze_repository(request: RepositoryAnalysisRequest) -> RepositoryAnalysisResponse | ReviewErrorResponse:
    try:
        result = analyze_github_repository(
            repo_url=request.repoUrl,
            branch=request.branch,
            max_files=request.maxFiles,
            github_token=request.githubToken,
        )
        return RepositoryAnalysisResponse(**result)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/debug", response_model=DebugResponse | ReviewErrorResponse)
def debug_code(request: DebugRequest) -> DebugResponse | ReviewErrorResponse:
    try:
        diagnosis = analyze_debug_issue(
            code=request.code,
            error_log=request.errorLog,
            language=request.language,
        )
        return DebugResponse(diagnosis=diagnosis)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/repository-context", response_model=RepositoryContextResponse | ReviewErrorResponse)
def repository_context(request: RepositoryContextRequest) -> RepositoryContextResponse | ReviewErrorResponse:
    try:
        context = build_repository_context(
            repo_url=request.repoUrl,
            branch=request.branch,
            max_files=request.maxFiles,
            github_token=request.githubToken,
        )
        docs = retrieve_repository_context(context["documents"], request.query, top_k=max(1, min(request.topK, 10)))
        contexts = [
            {"path": doc["path"], "language": doc["language"], "snippet": str(doc["content"])[:1400]}
            for doc in docs
        ]
        return RepositoryContextResponse(repository=context["repository"], query=request.query, contexts=contexts)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/propose-fix", response_model=FixResponse | ReviewErrorResponse)
def propose_fix(request: FixRequest) -> FixResponse | ReviewErrorResponse:
    try:
        result = propose_fix_and_verify(code=request.code, error_log=request.errorLog, language=request.language)
        return FixResponse(**result)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/run-evals")
def run_evals() -> dict:
    try:
        return run_eval_suite()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
