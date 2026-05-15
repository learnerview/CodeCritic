"""Agent tool wrappers and review pipeline for CodeCritic.

Module responsibilities:
- Define small Pydantic DTOs used for HTTP transport with the Java tool server
- Provide thin HTTP wrappers (`get_complexity`, `find_bugs`, `generate_test`) that are easy to
  mock and test in isolation
- Provide `run_review_pipeline` which orchestrates deterministic steps and then asks the LLM
  to synthesize a final human-friendly review

Security and keys:
- This module reads `GROQ_API_KEY` and `OPENAI_API_KEY` from environment variables. Use
  `.env` for local development (checked into `.gitignore`) or CI secrets for automation.
  See `.env.example` for the variable names.
"""

import os
import re
import tempfile
import time
import zipfile
from pathlib import Path
from urllib.parse import urlparse
import requests
from pydantic import BaseModel
from typing import Any, List, Optional
import logging
from pythonjsonlogger import jsonlogger
from dotenv import load_dotenv
from groq_llm import GroqLLM

# Setup Structured Logging for standalone runs
logger = logging.getLogger("codecritic-agent")
logHandler = logging.StreamHandler()
formatter = jsonlogger.JsonFormatter('%(asctime)s %(levelname)s %(name)s %(message)s')
logHandler.setFormatter(formatter)
logger.addHandler(logHandler)
logger.setLevel(logging.INFO)

load_dotenv()

# Configuration via environment variables (do not hard-code secrets)
GROQ_API_KEY = os.getenv("GROQ_API_KEY")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
LLM = GroqLLM(groq_key=GROQ_API_KEY, openai_key=OPENAI_API_KEY)
JAVA_SERVER = os.getenv("JAVA_SERVER_URL", "http://localhost:8080/api")


class ComplexityRequest(BaseModel):
    code: str


class ComplexityResponse(BaseModel):
    cyclomaticComplexity: int
    cognitiveComplexity: int


class BugFinding(BaseModel):
    type: str
    line: int
    message: str
    suggestion: str


class BugReport(BaseModel):
    bugs: List[BugFinding]


class TestGenerationRequest(BaseModel):
    className: str
    methodName: str
    parameters: Optional[str] = None
    code: Optional[str] = None


class TestGenerationResponse(BaseModel):
    junitCode: str


def groq_complete(prompt: str) -> str:
    """Synthesize a completion using the configured LLM provider.

    Rationale:
    - Keep the wrapper thin and provider-agnostic; the LLM prompt and how tools are
      used should be controlled by the agent (higher-level) code.
    - The wrapper handles retries and provider fallback transparently.
    """
    return LLM.complete(prompt, max_tokens=768)


def get_complexity(code: str) -> ComplexityResponse:
    """Call the Java analysis service to compute complexity metrics.

    The wrapper returns typed `ComplexityResponse` objects and raises for HTTP errors.
    Keeping this small makes it easy to substitute a mocked implementation in tests.
    """
    resp = requests.post(f"{JAVA_SERVER}/complexity", json={"code": code}, timeout=10)
    resp.raise_for_status()
    return ComplexityResponse(**resp.json())


def find_bugs(code: str) -> BugReport:
    """Call the Java analysis service to find potential bugs.

    Returns a `BugReport` DTO. The Java side combines pattern-based detectors and a
    best-effort SpotBugs run if SpotBugs is available in the runtime environment.
    """
    resp = requests.post(f"{JAVA_SERVER}/bugs", json={"code": code}, timeout=10)
    resp.raise_for_status()
    return BugReport(**resp.json())


def generate_test(className: str, methodName: str, parameters: Optional[str], code: Optional[str]) -> TestGenerationResponse:
    """Request a JUnit test template from the Java service.

    The Java service will attempt to parse `code` for method signatures and produce
    a minimal, compilable JUnit 5 test template. The wrapper returns the typed DTO.
    """
    payload = {"className": className, "methodName": methodName, "parameters": parameters, "code": code}
    resp = requests.post(f"{JAVA_SERVER}/generate-test", json=payload, timeout=10)
    resp.raise_for_status()
    return TestGenerationResponse(**resp.json())


def _extract_java_code_block(text: str) -> str:
    if not text:
        return ""
    match = re.search(r"```(?:java)?\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)
    if match:
        return match.group(1).strip()
    return text.strip()


def _extract_markdown_code_block(text: str) -> str:
    if not text:
        return ""
    match = re.search(r"```(?:\w+)?\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)
    return match.group(1).strip() if match else text.strip()


def _extract_method_names(code: str) -> List[str]:
    method_names = re.findall(
        r"\b(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\], ?]+\s+(\w+)\s*\(",
        code or "",
    )
    unique: List[str] = []
    for name in method_names:
        if name not in unique:
            unique.append(name)
    return unique


def _build_deterministic_summary(code: str) -> tuple[ComplexityResponse | None, BugReport | None, List[tuple[str, str]], List[str]]:
    parts: List[str] = []
    complexity = None
    bug_report = None
    templates: List[tuple[str, str]] = []

    try:
        complexity = get_complexity(code)
        parts.append(
            f"Complexity: cyclomatic={complexity.cyclomaticComplexity}, cognitive={complexity.cognitiveComplexity}"
        )
    except Exception as exc:
        parts.append(f"Complexity call failed: {exc}")

    try:
        bug_report = find_bugs(code)
        if not bug_report.bugs:
            parts.append("Bugs: none detected by tool")
        else:
            parts.append("Bugs:")
            for bug in bug_report.bugs:
                parts.append(f"- {bug.type} at line {bug.line}: {bug.message} -> {bug.suggestion}")
    except Exception as exc:
        parts.append(f"Bug detector call failed: {exc}")

    for method_name in _extract_method_names(code):
        try:
            generated = generate_test("ClassUnderTest", method_name, None, code)
            templates.append((method_name, generated.junitCode))
        except Exception as exc:
            parts.append(f"Template generation failed for {method_name}: {exc}")

    return complexity, bug_report, templates, parts


def generate_full_test_suite(code: str) -> str:
    """Generate a full JUnit test suite from source code.
    
    Uses deterministic analysis (complexity, bugs) plus LLM to synthesize complete tests.
    Note: _build_deterministic_summary is called once to reuse bug findings.
    """
    _, bugs, templates, _ = _build_deterministic_summary(code)
    template_context = "\n\n".join(
        f"Method: {name}\nTemplate:\n{template}" for name, template in templates
    )
    bug_context = "No known bug findings."
    if bugs and bugs.bugs:
        bug_context = "\n".join(
            f"- {bug.type} (line {bug.line}): {bug.message}. Suggestion: {bug.suggestion}"
            for bug in bugs.bugs
        )

    prompt = (
        "You are a senior Java test engineer.\n"
        "Generate a complete JUnit 5 test class for the Java source below.\n"
        "Requirements:\n"
        "1) Return only Java code for one compilable test class.\n"
        "2) Include meaningful assertions, not placeholder comments.\n"
        "3) Cover happy path, edge cases, and error cases when relevant.\n"
        "4) Keep the tests deterministic and readable.\n"
        "5) Do not use pseudo-code or TODOs.\n\n"
        "Source Java code:\n"
        f"{code}\n\n"
        "Known bug findings:\n"
        f"{bug_context}\n\n"
        "Reference deterministic templates (improve them into full tests):\n"
        f"{template_context or 'No templates available.'}\n"
    )
    raw = LLM.complete(prompt, max_tokens=2200)
    java_code = _extract_java_code_block(raw)
    if "class " not in java_code or "@Test" not in java_code:
        raise RuntimeError("LLM did not return a valid JUnit test class")
    return java_code


def analyze_debug_issue(code: str, error_log: str, language: str = "java") -> str:
    parts: List[str] = [f"Language: {language}"]
    if language.lower() == "java":
        try:
            comp = get_complexity(code)
            parts.append(
                f"Complexity: cyclomatic={comp.cyclomaticComplexity}, cognitive={comp.cognitiveComplexity}"
            )
        except Exception as exc:
            parts.append(f"Complexity unavailable: {exc}")
        try:
            bugs = find_bugs(code)
            if bugs.bugs:
                parts.append("Static findings:")
                for bug in bugs.bugs:
                    parts.append(f"- {bug.type} line {bug.line}: {bug.message}")
        except Exception as exc:
            parts.append(f"Bug findings unavailable: {exc}")

    prompt = (
        "You are a senior debugging engineer.\n"
        "Given source code and runtime/build errors, provide:\n"
        "1) Probable root cause\n"
        "2) Concrete fix steps\n"
        "3) Minimal patch-style code changes\n"
        "4) Validation steps\n\n"
        f"CODE:\n{code}\n\n"
        f"ERROR LOG:\n{error_log}\n\n"
        f"ANALYSIS CONTEXT:\n{chr(10).join(parts)}\n"
    )
    return LLM.complete(prompt, max_tokens=1300)


def build_repository_context(
    repo_url: str,
    branch: Optional[str] = None,
    max_files: int = 15,
    github_token: Optional[str] = None,
) -> dict[str, Any]:
    owner, repo = _parse_github_repo(repo_url)
    token = github_token or os.getenv("GITHUB_TOKEN")
    use_branch = branch or _resolve_default_branch(owner, repo, token)
    root = _download_github_archive(owner, repo, use_branch, token)
    files = _collect_code_files(root, max(1, min(max_files, 30)))
    if not files:
        raise RuntimeError("No supported source files found in repository")

    documents: List[dict[str, Any]] = []
    for file_path in files:
        rel = str(file_path.relative_to(root))
        text = file_path.read_text(encoding="utf-8", errors="ignore")
        documents.append(
            {
                "path": rel,
                "language": file_path.suffix.lower(),
                "size": len(text),
                "content": text,
            }
        )
    return {
        "repository": {"url": repo_url, "owner": owner, "name": repo, "branch": use_branch},
        "documents": documents,
    }


def retrieve_repository_context(documents: list[dict[str, Any]], query: str, top_k: int = 5) -> list[dict[str, Any]]:
    query_terms = {term for term in re.findall(r"[a-zA-Z_]{3,}", (query or "").lower())}
    if not query_terms:
        return documents[:top_k]

    def score(doc: dict[str, Any]) -> tuple[int, int]:
        content = str(doc.get("content", "")).lower()
        path = str(doc.get("path", "")).lower()
        hits = sum(1 for term in query_terms if term in content or term in path)
        return (hits, int(doc.get("size", 0)))

    ranked = sorted(documents, key=score, reverse=True)
    selected = [doc for doc in ranked if score(doc)[0] > 0]
    return (selected or ranked)[:top_k]


def _parse_github_repo(repo_url: str) -> tuple[str, str]:
    parsed = urlparse(repo_url.strip())
    if parsed.netloc.lower() not in {"github.com", "www.github.com"}:
        raise ValueError("Only github.com repositories are supported")
    chunks = [chunk for chunk in parsed.path.strip("/").split("/") if chunk]
    if len(chunks) < 2:
        raise ValueError("Invalid GitHub repository URL")
    owner = chunks[0]
    repo = chunks[1].replace(".git", "")
    return owner, repo


def _resolve_default_branch(owner: str, repo: str, token: Optional[str]) -> str:
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = requests.get(f"https://api.github.com/repos/{owner}/{repo}", headers=headers, timeout=30)
    response.raise_for_status()
    data = response.json()
    return data.get("default_branch") or "main"


def _download_github_archive(owner: str, repo: str, branch: str, token: Optional[str]) -> Path:
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    archive_url = f"https://api.github.com/repos/{owner}/{repo}/zipball/{branch}"
    response = requests.get(archive_url, headers=headers, timeout=60)
    response.raise_for_status()

    temp_dir = Path(tempfile.mkdtemp(prefix="codecritic-repo-"))
    zip_path = temp_dir / "repo.zip"
    zip_path.write_bytes(response.content)
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(temp_dir)

    extracted_dirs = [path for path in temp_dir.iterdir() if path.is_dir()]
    if not extracted_dirs:
        raise RuntimeError("Repository archive extraction failed")
    return extracted_dirs[0]


def _collect_code_files(root: Path, max_files: int) -> List[Path]:
    allowed = {".java", ".py", ".js", ".ts", ".tsx"}
    ignored_dirs = {"node_modules", ".git", "target", "dist", "build", ".venv", "venv"}
    candidates: List[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in ignored_dirs for part in path.parts):
            continue
        if path.suffix.lower() in allowed:
            candidates.append(path)
    candidates.sort(key=lambda p: p.stat().st_size, reverse=True)
    return candidates[:max_files]


def analyze_github_repository(
    repo_url: str,
    branch: Optional[str] = None,
    max_files: int = 15,
    github_token: Optional[str] = None,
) -> dict:
    context = build_repository_context(
        repo_url=repo_url,
        branch=branch,
        max_files=max_files,
        github_token=github_token,
    )
    repo_meta = context["repository"]
    docs = context["documents"]

    per_file = [{"path": d["path"], "language": d["language"], "size": d["size"]} for d in docs]
    java_findings = []
    combined_source = []
    remaining_budget = 32000
    for doc in docs:
        rel = str(doc["path"])
        text = str(doc["content"])
        language = str(doc["language"])
        snippet = text[: min(4000, max(0, remaining_budget))]
        if snippet:
            rendered = f"FILE: {rel}\n{snippet}"
            combined_source.append(rendered)
            remaining_budget -= len(rendered)
        if language == ".java":
            try:
                complexity = get_complexity(text)
                bugs = find_bugs(text)
                java_findings.append(
                    {
                        "path": rel,
                        "cyclomatic": complexity.cyclomaticComplexity,
                        "cognitive": complexity.cognitiveComplexity,
                        "bugCount": len(bugs.bugs),
                        "bugs": [f"{bug.type} line {bug.line}: {bug.message}" for bug in bugs.bugs[:6]],
                    }
                )
            except Exception as exc:
                java_findings.append({"path": rel, "error": str(exc)})
        if remaining_budget <= 0:
            break

    summary_prompt = (
        "You are a staff engineer assessing production readiness of a code repository.\n"
        "Return concise markdown with:\n"
        "1) Architecture understanding\n"
        "2) Top risks (security/reliability/performance)\n"
        "3) Debuggability gaps\n"
        "4) Practical roadmap (smallest high-impact next steps)\n\n"
        f"Repository: {repo_url} (branch: {repo_meta['branch']})\n"
        f"Inspected files: {len(per_file)}\n\n"
        f"Java static findings: {java_findings}\n\n"
        "Source excerpts:\n"
        + "\n\n".join(combined_source)
    )
    ai_summary = LLM.complete(summary_prompt, max_tokens=1800)
    return {
        "repository": repo_meta,
        "filesAnalyzed": per_file,
        "javaStaticFindings": java_findings,
        "aiSummary": ai_summary,
    }


def propose_fix_and_verify(code: str, error_log: str, language: str = "java") -> dict[str, Any]:
    started_at = time.time()
    diagnosis = analyze_debug_issue(code=code, error_log=error_log, language=language)
    patch_prompt = (
        "Return only fixed source code in a single markdown code block.\n"
        "Fix the issue from the error log while preserving behavior.\n\n"
        f"LANGUAGE: {language}\n"
        f"ORIGINAL CODE:\n{code}\n\n"
        f"ERROR LOG:\n{error_log}\n\n"
        f"DIAGNOSIS:\n{diagnosis}\n"
    )
    patch_raw = LLM.complete(patch_prompt, max_tokens=2200)
    fixed_code = _extract_markdown_code_block(patch_raw)
    if not fixed_code:
        raise RuntimeError("Failed to generate fixed code")

    verification: dict[str, Any] = {"language": language, "checks": []}
    if language.lower() == "java":
        try:
            before_bugs = find_bugs(code)
            after_bugs = find_bugs(fixed_code)
            verification["checks"].append(
                {
                    "name": "bug-count",
                    "before": len(before_bugs.bugs),
                    "after": len(after_bugs.bugs),
                    "passed": len(after_bugs.bugs) <= len(before_bugs.bugs),
                }
            )
        except Exception as exc:
            verification["checks"].append({"name": "bug-count", "passed": False, "error": str(exc)})
        try:
            before_complexity = get_complexity(code)
            after_complexity = get_complexity(fixed_code)
            verification["checks"].append(
                {
                    "name": "complexity-regression",
                    "beforeCyclomatic": before_complexity.cyclomaticComplexity,
                    "afterCyclomatic": after_complexity.cyclomaticComplexity,
                    "passed": after_complexity.cyclomaticComplexity <= before_complexity.cyclomaticComplexity + 2,
                }
            )
        except Exception as exc:
            verification["checks"].append({"name": "complexity-regression", "passed": False, "error": str(exc)})

    passed_count = sum(1 for check in verification["checks"] if check.get("passed"))
    verification["passed"] = bool(verification["checks"]) and passed_count == len(verification["checks"])
    verification["durationMs"] = int((time.time() - started_at) * 1000)

    return {
        "diagnosis": diagnosis,
        "fixedCode": fixed_code,
        "verification": verification,
    }


def run_eval_suite() -> dict[str, Any]:
    dataset_path = Path(__file__).resolve().parent / "evals_dataset.json"
    if not dataset_path.exists():
        raise RuntimeError("Missing evals dataset file: python-agent/evals_dataset.json")

    import json

    cases = json.loads(dataset_path.read_text(encoding="utf-8"))
    if not isinstance(cases, list):
        raise RuntimeError("Invalid evals dataset format")

    results: list[dict[str, Any]] = []
    for case in cases:
        case_id = str(case.get("id", "unknown"))
        code = str(case.get("code", ""))
        expected_bug = str(case.get("expectedBugType", "")).strip()
        has_llm = bool(LLM.provider_status().get("groqConfigured") or LLM.provider_status().get("openaiConfigured"))
        case_result: dict[str, Any] = {"id": case_id, "expectedBugType": expected_bug}
        try:
            bugs = find_bugs(code)
            types = {bug.type for bug in bugs.bugs}
            bug_hit = expected_bug in types if expected_bug else True
            case_result["bugDetectionPassed"] = bug_hit
            case_result["detectedBugTypes"] = sorted(types)
        except Exception as exc:
            case_result["bugDetectionPassed"] = False
            case_result["bugError"] = str(exc)

        if has_llm:
            try:
                suite = generate_full_test_suite(code)
                case_result["testGenerationPassed"] = "@Test" in suite and "class " in suite
            except Exception as exc:
                case_result["testGenerationPassed"] = False
                case_result["testGenError"] = str(exc)
        else:
            case_result["testGenerationPassed"] = False
            case_result["testGenError"] = "No LLM key configured"

        results.append(case_result)

    bug_passed = sum(1 for r in results if r.get("bugDetectionPassed"))
    test_passed = sum(1 for r in results if r.get("testGenerationPassed"))
    total = len(results)
    return {
        "summary": {
            "totalCases": total,
            "bugDetectionPassRate": (bug_passed / total) if total else 0.0,
            "testGenerationPassRate": (test_passed / total) if total else 0.0,
        },
        "results": results,
    }


if __name__ == "__main__":
    logger.info("CodeCritic agent skeleton. This file contains models and HTTP tool wrappers.")
    sample = '''public class Calculator {
    public int divide(int a, int b) {
        return a / b;
    }
}'''
    logger.info("Calling Java server for complexity (if running)...")
    try:
        c = get_complexity(sample)
        logger.info("Complexity result", extra={"complexity": c.dict()})
    except Exception as e:
        logger.error("Complexity call failed", extra={"error": str(e)})


def run_review_pipeline(code: str) -> str:
    """Run a deterministic pipeline:

    Steps:
    1. Query the Java server for complexity metrics.
    2. Query the Java server for pattern-based and SpotBugs findings.
    3. Attempt to automatically generate JUnit tests for detected methods.
    4. Synthesize a final review summary using the configured LLM.

    Rationale and best practices:
    - Each external call is isolated and wrapped so failures can be handled gracefully.
    - The synthesized LLM prompt contains both the original code and a concise machine-produced
      summary; this reduces token usage and keeps the LLM focused on high-level reasoning.
    - Prefer actionable, small suggestions in the final output to help developers iterate quickly.
    """
    _, _, method_tests, parts = _build_deterministic_summary(code)

    try:
        full_tests = generate_full_test_suite(code)
        parts.append("--- AI GENERATED COMPLETE TEST SUITE ---")
        parts.append(full_tests)
    except Exception as exc:
        parts.append(f"AI full test generation failed: {exc}")
        if method_tests:
            parts.append("Fallback deterministic templates:")
            for method_name, template in method_tests:
                parts.append(f"--- Template for {method_name} ---\n{template}")

    # Synthesize final review with Groq
    try:
        prompt = "You are a senior Java reviewer. Produce a concise review given the following sections:\n"
        prompt += "\nCODE:\n" + code + "\n\n"
        prompt += "\nSUMMARY:\n" + "\n".join(parts) + "\n\n"
        prompt += "Provide: 1) Short summary, 2) Actionable suggestions, 3) Risk hotspots.\n"
        review = groq_complete(prompt)
    except Exception as exc:
        review = "LLM synthesis failed: " + str(exc)

    return "\n\n".join(parts) + "\n\n--- GROQ REVIEW ---\n" + (review or "(no review)")

