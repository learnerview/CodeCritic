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

**Fallback (graceful degradation):** if JavaParser cannot parse the source, `fallbackHeuristic` runs. It first strips `// line`, `/* block */` comments, `"..."` string literals, and `'...'` char literals so tokens inside comments or strings are not miscounted, then counts `if(`, `for(`, `while(`, `case `, `&&`, `||`, `catch(`, `?:`, and `default:`.

## 2. Bug detection (`/api/bugs`)

`CompositeBugDetector` combines multiple detectors:

### Pattern detector (`PatternBugDetector`)
Line-scanning heuristics:
- **Division by zero**: a line matching `\d+\s*/\s*0` (digits divided by zero) → `DivisionByZeroRisk`. Requiring digits around the `/` and `0` reduces false positives from comments or unrelated `/ 0` text.
- **Unsafe `.toString()`**: a line with `.toString()` that has no nearby `!= null` or `Objects.toString` guard → `NullPointerRisk`.

### SpotBugs (`SpotBugsBugDetector` + `SpotBugsRunner`)
Best-effort: if a JDK and `spotbugs` CLI are available, the source is compiled in a unique `UUID` temp directory and SpotBugs is run over it. Findings are wrapped as `SpotBugsFinding`. Blocking is never done — failures or absence of the tool return no findings and never fail the request.

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

For non-void return types it emits `assertNotNull(result)` (and a `Boolean`-specific assertion when applicable); void methods use `assertDoesNotThrow`.

### LLM full suite (`/generate-tests`)
`generate_full_test_suite` reuses the deterministic findings and asks the LLM for a complete JUnit 5 class with meaningful assertions and happy-path/edge/error coverage, rejecting output that has no `class` or `@Test`.
