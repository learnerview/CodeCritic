# Analysis Algorithms

## 1. Complexity analysis (`/api/complexity`)

`JavaParserComplexityAnalyzer` parses the submitted source into an AST and walks it with a `VoidVisitorAdapter` that tracks the maximum cyclomatic complexity across all methods.

**Decision points counted (per-method):**
- `if`
- `for` / `foreach`
- `while` / `do`
- `catch`
- `switch` cases (`label` each)
- ternary `?:`
- boolean `&&` / `||`

For each `MethodDeclaration`, the visitor resets a `current` counter to 1 and records the running max after visiting the method body. **Cognitive complexity** is a simple scaled estimate (`max(1, cyclomatic / 2)`).

**Fallback (graceful degradation):** if JavaParser cannot parse the source, `fallbackHeuristic` runs. It first strips `// line`, `/* block */` comments, `"..."` string literals, and `'...'` char literals so tokens inside comments or strings are not miscounted, then counts `if(`, `for(`, `while(`, `case `, `&&`, `||`, `catch(`, `?:`, and `default:`. The stripping is escape-aware: a backslash in a string/char literal (e.g. `\\"` or `'\''`) skips the escaped character so an escaped quote does not prematurely end the literal.

## 2. Bug detection (`/api/bugs`)

`CompositeBugDetector` combines multiple detectors:

### Pattern detector (`PatternBugDetector`)
Line-scanning heuristics:
- **Division by zero**: a line matching `\d+\s*/\s*0` (digits divided by zero) → `DivisionByZeroRisk`. Requiring digits around the `/` and `0` reduces false positives from comments or unrelated `/ 0` text.
- **Unsafe `.toString()`**: a line with `.toString()` that has no `!= null` guard on it → `NullPointerRisk`.

### SpotBugs (`SpotBugsBugDetector` + `SpotBugsRunner`)
SpotBugs compiles the submitted source in a unique temp directory (named `codecritic-<requestId>`) and runs `spotbugs -textui` over the compiled classes. Each finding line is parsed into a `BugFinding` with the SpotBugs bug type (e.g. `NP_NULL_ON_SOME_PATH`, `IL_INFINITE_LOOP`) extracted from the output; `SpotBugsFinding` is the placeholder type used only when a finding carries no parseable type, or when the tool is unavailable. The request is never blocked on: if the tool is missing or errors the detector returns no findings rather than failing the call, **except** that source which does not compile yields a single `COMPILATION_ERROR` finding.

### Caching (`CachedSpotBugsBugDetector`)
SpotBugs is expensive (compile + subprocess), so results are cached:
- **Key** = SHA-256(source + `DETECTOR_VERSION`). Bumping the version invalidates everything.
- **Bounded LRU**: a synchronized, access-ordered `LinkedHashMap` limited to 256 entries evicts the least-recently-used entry instead of clearing the whole cache.

## 3. Test generation (`/api/generate-test`)

`JavaParserTestGenerator` parses the source to extract the real class name, method name, return type, and parameter types, then emits a deterministic JUnit 5 scaffold:

```java
package <package>;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class <Class>Test {
    @Test
    void test<Method>() {
        <Class> obj = new <Class>();
        var result = assertDoesNotThrow(() -> obj.<Method>(<args>));
        assertNotNull(result);   // or assertDoesNotThrow for void
    }
}
```

**Placeholder literals** for parameters map by *simple* type name (so fully-qualified names like `java.lang.Integer` also match):

| Type | Literal |
|------|---------|
| `int`, `long`, `short`, `byte` (and boxed) | `1` |
| `float`, `double` (and boxed) | `1.0` |
| `boolean` | `true` |
| `char` | `'a'` |
| `String` | `"sample"` |
| other | `null` |

For non-void return types it emits `assertNotNull(result)`, except for `boolean`/`Boolean` returns where it emits an `assertTrue(result == Boolean.TRUE || result == Boolean.FALSE)` check; void methods use `assertDoesNotThrow`.

### LLM full suite (`/generate-tests`)
`generate_full_test_suite` reuses the deterministic findings and asks the LLM for a complete JUnit 5 class with meaningful assertions and happy-path/edge/error coverage, rejecting output that has no `class` or `@Test`.
