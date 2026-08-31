# Known Limitations & Future Work

This document records acknowledged limitations. Most are intentional trade-offs rather than defects, but they are worth keeping in mind — especially if you are evaluating the project or being asked about them.

## Analysis accuracy

- **Heuristics are approximate by design.** The deterministic bug detector is line-based (`PatternBugDetector`) and the complexity fallback is token-based. They can produce false positives (e.g., a string or comment that looks like `/ 0`) and false negatives (e.g., a division by zero involving a variable that cannot be resolved statically). The comment/string stripping in the fallback reduces, but does not eliminate, false positives.
- **Cognitive complexity is a crude estimate** (`max(1, cyclomatic / 2)`), not Sonar-style cognitive complexity. Fine for a demo signal, not a precise quality gate.
- **Test scaffolds are templates, not proven tests.** `JavaParserTestGenerator` outputs a deterministic JUnit 5 skeleton with placeholder literals; parameters it cannot map default to `null`. The LLM-generated suite (`/generate-tests`) is richer but still not guaranteed to compile without edits for complex generics or dependencies.

## Type and code parsing edge cases

- **Fully-qualified generics / complex types** in test generation use primitive placeholder literals. Types like custom objects, arrays, or generics default to `null`, so generated tests may need manual argument values.
- **`is_java_code`** in the Python agent is a heuristic. It now accepts common Java tokens (`;`, `{`, `return`, `@Override`, `new`, …) so short snippets pass, but pathological inputs can still be misclassified.

## Concurrency & storage

- **LRU SpotBugs cache** evicts the least-recently-used entry at 256 entries. This bounds memory but means hot files churning through the cache will occasionally recompile. This is a deliberate trade-off, not a cap on correctness. The `size()/hits()/misses()` counters let you observe it.
- **No persistent storage for user data.** `User` documents persist in MongoDB, but no usage history or review storage exists between runs.

## Security posture

- **Not hardened against untrusted input.** The Java service accepts arbitrary Java source; when a JDK and `spotbugs` CLI are available it compiles the submitted source (which we disable in the deployed JRE image — see below), and the Python agent has no auth. See `docs/security.md`. Do not expose to the public internet without a sandbox.
- **Stateless JWT** means tokens are not revocable before expiry, and there is no refresh-token rotation.
- **SpotBugs is best-effort and absent in the deployed image.** The container runtime is a JRE (`eclipse-temurin:21-jre`) with no compiler or `spotbugs` CLI installed, so on Render `/api/bugs` relies on the pattern detector only; SpotBugs contributes findings only on local hosts where you install the toolchain. That is a deliberate trade-off to keep the free-tier footprint small, not a defect.

## Observability gaps

- Structured JSON logs and metrics exist (see `docs/architecture.md`), but there is no request correlation across the queue lifecycle yet, and no tracing (e.g., OpenTelemetry) across the Java → Python → LLM chain.

## Future work

- Sandboxed execution for compiled source (container/`seccomp`) to make the review path safe for untrusted input.
- Precise Sonar-style cognitive complexity.
- Persistent review history and per-user dashboards.
- Revocable/rotating tokens and optional PKCE if a browser login flow is added.
- LRU-aware cache metrics reporting and possibly a Redis-backed distributed cache.
