# CodeCritic

CodeCritic is a hybrid agentic code-review system that combines a Python LLM orchestrator with Java-based analysis tools.

The design goal is practical, not decorative: the Python layer plans and synthesizes reviews, while the Java layer performs deterministic analysis on Java source code using AST parsing, static bug heuristics, and best-effort SpotBugs integration.

## Why this architecture

- Python is used for orchestration because the LLM ecosystem is strongest there.
- Java is used for the analysis engine because JavaParser and SpotBugs are JVM-native and a natural fit for Java source analysis.
- HTTP is used between services to keep the system simple, debuggable, and language-agnostic.
- DTOs are kept separate from implementation logic so transport contracts remain stable and easy to reason about.

## High-level flow

1. The user submits Java code to the Python agent.
2. The Python agent calls the Java analysis service endpoints.
3. The Java service returns complexity metrics, bug findings, and deterministic JUnit scaffolds.
4. The Python agent sends the collected analysis to the LLM backend.
5. The LLM produces a final human-readable code review and a full JUnit test suite.
6. Optional repository and debugging workflows run on the Python layer:
   - GitHub repository ingestion + architecture/risk roadmap
   - Error-log + source-code debugging diagnosis

## Premium Code Editor

CodeCritic features a "sandboxed editor" experience using **CodeMirror**.
- **Syntax Highlighting**: Real-time coloring for Java source.
- **Line Numbering**: Essential for mapping static analysis findings to code.
- **IDE Feel**: Bracket matching, indentation, and responsive code editing within the browser.

## Algorithms and analysis methods

### 1. Complexity analysis

The `/api/complexity` endpoint uses JavaParser to parse the submitted source into an AST.

Cyclomatic complexity is estimated by counting decision points in the method tree:
- `if`
- `for`
- `foreach`
- `while`
- `do`
- `catch`
- `switch` cases
- ternary expressions
- boolean `&&` and `||`

The implementation tracks the maximum method complexity found in the file. Cognitive complexity is derived as a simple scaled estimate from cyclomatic complexity. If parsing fails, the service falls back to a lightweight token-based heuristic so it still returns a useful answer.

### 2. Bug detection

The `/api/bugs` endpoint combines two layers:

- Pattern-based detection for obvious risks such as:
  - division by zero
  - potentially unsafe `.toString()` calls
- Best-effort SpotBugs execution on compiled temporary classes if the environment has:
  - a JDK available
  - `spotbugs` on PATH

This gives a useful baseline even when SpotBugs is unavailable, while still allowing richer analysis in a proper dev environment.

### 3. Test generation

The `/api/generate-test` endpoint builds a deterministic JUnit 5 scaffold from the requested method metadata.

If the full source is provided, the Java service parses the class and tries to extract the real method signature and parameter types. Primitive types get simple placeholder values:
- `int`, `long`, `short` -> `1`
- `double`, `float` -> `1.0`
- `boolean` -> `true`
- everything else -> `null`

The Python endpoint `POST /generate-tests` uses the LLM to generate a complete JUnit 5 test suite with assertions and edge-case coverage, using Java analysis findings as context.

### 4. LLM synthesis

The Python side collects the tool outputs and sends a concise summary to the configured LLM provider.

Primary backend:
- Groq API via `GROQ_API_KEY`

Fallback:
- OpenAI via `OPENAI_API_KEY`

The LLM is used for synthesis and explanation, not for performing the actual static analysis.

### 5. Repository analysis and debugging

- `POST /analyze-repository` downloads a GitHub repository archive, inspects source files, runs Java static checks where applicable, and uses the LLM for a production-readiness summary.
- `POST /debug` combines source code, error logs, and static findings into root-cause diagnosis plus concrete fix guidance.

## Repository layout

- `java-server/` - Spring Boot analysis microservice
- `python-agent/` - LLM orchestrator and HTTP tool wrappers
- `docker-compose.yml` - local multi-container setup
- `.env.example` - environment variable template
- `LICENSE` - MIT license for portfolio use

## Environment variables

Create a local `.env` file from `.env.example` and fill in real values locally.

Required:
- `GROQ_API_KEY` - Groq API key

Optional:
- `OPENAI_API_KEY` - fallback LLM key
- `GROQ_MODEL` - Groq model id (default `llama-3.3-70b-versatile`)
- `GROQ_USE_LANGCHAIN` - enable LangChain orchestration for Groq calls (default `true`)
- `OPENAI_MODEL` - OpenAI fallback model id
- `JAVA_SERVER_URL` - defaults to `http://localhost:8080/api`
- `GITHUB_TOKEN` - optional token for private repos and higher API limits
- `CORS_ALLOW_ORIGINS` - allowed origins for browser access to Python endpoints
- `API_AUTH_TOKEN` - optional API token required in `x-api-token` header
- `RATE_LIMIT_REQUESTS` - requests allowed per window per IP
- `RATE_LIMIT_WINDOW_SECONDS` - rate-limit window length
- `MAX_REQUEST_BYTES` - max inbound payload size in bytes

Example:

```bash
GROQ_API_KEY=your_groq_key
OPENAI_API_KEY=your_openai_key
JAVA_SERVER_URL=http://localhost:8080/api
GROQ_USE_LANGCHAIN=true
GITHUB_TOKEN=your_github_token_optional
CORS_ALLOW_ORIGINS=*
API_AUTH_TOKEN=your_internal_api_token_optional
RATE_LIMIT_REQUESTS=60
RATE_LIMIT_WINDOW_SECONDS=60
MAX_REQUEST_BYTES=1500000
```

## Real-World Usable (v1.0)

CodeCritic has been hardened to transition from a demo-quality project to a genuinely usable tool for small teams and staging environments.

### Core Reliability Features
- **Concurrency Safety**: The Java server now uses `UUID`-based temporary directory isolation for every SpotBugs analysis, preventing resource collisions during parallel reviews.
- **Startup Dependency Checks**: The Python agent includes an asynchronous startup task that pings the Java server's `/health` endpoint with a retry policy. It ensures all downstream dependencies are UP before accepting traffic.
- **Input Validation**: Added early-reject heuristics to validate Java source code structure, preventing LLM token waste on invalid inputs.
- **LLM Hardening**: Configurable timeouts (`LLM_TIMEOUT_SECONDS`) and exponential backoff retries for all AI provider calls.

### Observability (Structured Logging)
Both the Python Agent and Java Server are now configured for **Structured JSON Logging**:
- **Python**: Uses `python-json-logger` with `request_id` correlation.
- **Java**: Uses `logback-spring.xml` with the `logstash-logback-encoder`.
This allows logs to be easily ingested and filtered in tools like ELK, Datadog, or simple `jq` searches.

---

## License
MIT

## Run locally without Docker

### Java server

```bash
cd java-server
./mvnw package
java -jar target/java-server-0.1.0.jar
```

### Python agent

```bash
cd python-agent
python -m venv venv
source venv/bin/activate  # Windows: .\venv\Scripts\activate
pip install -r requirements.txt
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

## Run with Docker

```bash
docker compose up --build
```

Then open:
- Python agent health: `http://localhost:8000/health`
- Python metrics endpoint: `GET http://localhost:8000/metrics`
- Python review API: `POST http://localhost:8000/review`
- Python full test generation API: `POST http://localhost:8000/generate-tests`
- Python repository analysis API: `POST http://localhost:8000/analyze-repository`
- Python debug assistant API: `POST http://localhost:8000/debug`
- Python repository context retrieval API: `POST http://localhost:8000/repository-context`
- Python fix proposal + verification API: `POST http://localhost:8000/propose-fix`
- Python eval suite API: `POST http://localhost:8000/run-evals`
- Python readiness API (LLM connectivity): `GET http://localhost:8000/ready`
- Java health: `http://localhost:8080/health`
- Java complexity API: `POST http://localhost:8080/api/complexity`
- UI home: `http://localhost:8080/`
- UI snippet review: `http://localhost:8080/review`
- UI repository analysis: `http://localhost:8080/repository`
- UI debug assistant: `http://localhost:8080/debug`

## API examples

### Java: complexity

```bash
curl -X POST http://localhost:8080/api/complexity \
  -H "Content-Type: application/json" \
  -d '{"code":"public class A { public int f(int x){ if(x>0){ return x; } return 0; } }"}'
```

### Java: bugs

```bash
curl -X POST http://localhost:8080/api/bugs \
  -H "Content-Type: application/json" \
  -d '{"code":"public class A { public int f(int x){ return 10 / x; } }"}'
```

### Java: generate test

```bash
curl -X POST http://localhost:8080/api/generate-test \
  -H "Content-Type: application/json" \
  -d '{"className":"Calculator","methodName":"add","parameters":"int a, int b","code":"public class Calculator { public int add(int a, int b){ return a + b; } }"}'
```

### Python: review pipeline

```bash
curl -X POST http://localhost:8000/review \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int divide(int a, int b){ return a / b; } }"}'
```

### Python: full test generation

```bash
curl -X POST http://localhost:8000/generate-tests \
  -H "Content-Type: application/json" \
  -d '{"code":"public class Calculator { public int add(int a, int b){ return a + b; } }"}'
```

### Python: analyze GitHub repository

```bash
curl -X POST http://localhost:8000/analyze-repository \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","maxFiles":10}'
```

### Python: debug assistant

```bash
curl -X POST http://localhost:8000/debug \
  -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"java.lang.ArithmeticException: / by zero"}'
```

### Python: repository context retrieval (RAG-style)

```bash
curl -X POST http://localhost:8000/repository-context \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/octocat/Hello-World","query":"auth token validation flow","maxFiles":15,"topK":5}'
```

### Python: propose fix + verify

```bash
curl -X POST http://localhost:8000/propose-fix \
  -H "Content-Type: application/json" \
  -d '{"language":"java","code":"public class A { int f(int x){ return 10/x; } }","errorLog":"java.lang.ArithmeticException: / by zero"}'
```

### Python: run eval suite

```bash
curl -X POST http://localhost:8000/run-evals
```

## Testing and smoke checks

### Python integration test

```bash
cd python-agent
python integration_test.py
```

### Java smoke check

```bash
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"ok","service":"java-server"}
```

### Java endpoint test

The repository includes a focused `MockMvc` test at `java-server/src/test/java/com/codecritic/controller/AnalysisControllerTest.java`.
It verifies that `/api/complexity` returns JSON and that the controller delegates to the service boundary correctly.

## Docker notes

- The Java image is built in a multi-stage Dockerfile so the jar is created inside the container build.
- The Python image starts a FastAPI server with Uvicorn so the container stays alive and exposes HTTP endpoints.
- The compose file wires `JAVA_SERVER_URL=http://java-server:8080/api` so the Python container talks to the Java container over the Docker network.

## Troubleshooting

### Java health returns 404

That means the service was running but the root health endpoint was missing. This repo now includes `GET /health` on the Java server.

### SpotBugs is unavailable

The `/api/bugs` endpoint still works. It returns pattern-based findings and includes a best-effort SpotBugs message if the CLI is not present.

### Groq key missing

Set `GROQ_API_KEY` in your `.env` or shell environment. If Groq is unavailable, the Python wrapper can fall back to `OPENAI_API_KEY` if configured.

### Python review fails but health works

This usually means the agent is running, but the review path could not reach the Java service or the LLM key is missing. Check `JAVA_SERVER_URL`, `GROQ_API_KEY`, and the container logs.

## Best practices used

- DTOs for transport, not persistence.
- Small, focused services and controllers.
- Fail-soft analysis where external tooling is optional.
- Environment-based secrets, never hard-coded keys.
- Multi-stage Docker builds for smaller runtime images.
- Minimal health endpoints for reliable smoke testing.

## Security model

This project is a local-development and demo tool, not a public internet service.

- The Java backend accepts raw Java source over HTTP.
- The bug-analysis path may compile that source in a temporary directory.
- SpotBugs is invoked as an external best-effort process when available.
- That design is fine for local demos and interviews, but it is not hardened against malicious input.

See [SECURITY.md](SECURITY.md) for the full threat model and secrets guidance.

## Demo flow

1. Start the stack with `docker compose up --build`.
2. Submit a small Java class to `POST /review` on the Python service.
3. Observe:
   - complexity metrics from the Java parser
   - bug warnings from heuristics and SpotBugs fallback
   - a generated full JUnit test suite from the Python LLM layer
   - a final LLM-written review

Example input:

```java
public class Calculator {
  public int divide(int a, int b) {
    return a / b;
  }
}
```

Expected behavior:
- `/api/complexity` reports a cyclomatic complexity greater than 1.
- `/api/bugs` flags the division risk or a SpotBugs-style finding.
- `/api/generate-test` returns a deterministic JUnit scaffold.
- `/generate-tests` returns a complete LLM-generated JUnit suite.
- `/review` returns a synthesized human-readable code review with generated tests embedded.
