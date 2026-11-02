# CodeCritic

CodeCritic is a deterministic Java change-review system. The product surface
is the hybrid Java server, while the strongest review capability is the
callable-level verification engine.

## What is included

1. Deterministic Java analysis through the Java server: complexity, bug
   detection, test scaffolding, dashboards, and optional LLM synthesis.
2. One authenticated Java orchestration boundary for the browser; the browser
   never calls or discovers the Python agent directly.
3. Callable-level Java diff and impact analysis.
4. Risk scoring with structured evidence.
5. Maven and Gradle project detection.
6. Differential tests against isolated base/head git worktrees.
7. Bounded Docker sandboxing with timeout, output, memory, CPU, capability,
   and filesystem limits.
8. HTML and JSON reports suitable for CI artifacts.

The deterministic-first model is the core. LLM output is advisory and is
grounded in deterministic findings.

The Java service calls the Python agent only through its internal
`PythonAgentClient`. Set `PYTHON_AGENT_TOKEN` to the same random secret in both
services; the Python review endpoints reject requests without that service
credential. User JWTs are accepted by Java, never forwarded to the browser or
used as Python service credentials.

## Build and run

```powershell
mvn -B test
mvn -B package
java -jar verification\target\codecritic-verification.jar `
  --repo . --base main --head HEAD --format html
```

For differential execution, add `--run-tests`. Docker is deliberately a hard
requirement for that mode; without it CodeCritic reports an explicit
infrastructure error and never falls back to running untrusted build scripts
on the host.
