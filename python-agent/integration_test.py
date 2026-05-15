from agent import get_complexity, find_bugs, generate_full_test_suite, run_eval_suite, LLM


SAMPLE = '''public class Calculator {
    public int divide(int a, int b) {
        int x = 1 / 0; // intentional division by zero for detector
        return a / b;  // Potential division by zero
    }

    public String getName(Object obj) {
        return obj.toString(); // Potential NullPointerException
    }
}
'''


def run():
    print('Running integration test (automated)')
    # Call Java server wrappers directly for deterministic assertions
    c = get_complexity(SAMPLE)
    print('Complexity response:', c)
    assert c.cyclomaticComplexity > 0, 'Expected positive cyclomatic complexity'

    bugs = find_bugs(SAMPLE)
    print('Bug report:', bugs)
    types = {b.type for b in bugs.bugs}
    assert 'DivisionByZeroRisk' in types, 'Expected DivisionByZeroRisk in findings'
    assert 'NullPointerRisk' in types or any('toString' in b.message for b in bugs.bugs), 'Expected NullPointerRisk or toString mention'

    if LLM.provider_status().get("groqConfigured") or LLM.provider_status().get("openaiConfigured"):
        generated_tests = generate_full_test_suite(SAMPLE)
        print('Generated full test suite:', generated_tests[:400], '...')
        assert '@Test' in generated_tests, 'Expected JUnit @Test methods in generated test suite'
    else:
        print('Skipping LLM full test generation check: no LLM key configured')

    eval_result = run_eval_suite()
    print('Eval suite summary:', eval_result.get('summary'))
    assert eval_result.get('summary', {}).get('bugDetectionPassRate', 0) >= 0.5, 'Expected bug detection pass rate >= 0.5'

    print('Integration test succeeded')


if __name__ == '__main__':
    try:
        run()
    except AssertionError as e:
        print('TEST FAILED:', e)
        raise SystemExit(1)
    except Exception as e:
        print('TEST ERROR:', e)
        raise SystemExit(2)
