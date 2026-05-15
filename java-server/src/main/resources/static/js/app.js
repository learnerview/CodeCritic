const PYTHON_AGENT_BASE = window.CODECRITIC_PYTHON_API || `${window.location.protocol}//${window.location.hostname}:8000`;

const byId = (id) => document.getElementById(id);
const toText = (value) => (typeof value === 'string' ? value : JSON.stringify(value, null, 2));

const withSpinner = (text) => `<span class="loading-spinner"></span>${text}`;
const withoutSpinner = (text) => text.replace(/<span class="loading-spinner"><\/span>/g, '');

console.log("CodeCritic: app.js v1.0.4 final polish...");

// Helper for CodeMirror initialization
function createEditor(id, mode = "text/x-java") {
    const el = byId(id);
    if (!el) return null;
    
    if (typeof CodeMirror === 'undefined') {
        console.error("CodeCritic: CodeMirror is NOT defined!");
        return null;
    }

    try {
        const editor = CodeMirror.fromTextArea(el, {
            mode: mode,
            theme: "dracula",
            lineNumbers: true,
            matchBrackets: true,
            indentUnit: 4,
            viewportMargin: Infinity,
            lineWrapping: true
        });
        
        el.style.display = 'none';
        return editor;
    } catch (e) {
        console.error(`CodeCritic: Failed to initialize editor for #${id}:`, e);
        return null;
    }
}

async function postJson(url, body) {
    const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });

    const text = await response.text();
    let parsed;
    try {
        parsed = text ? JSON.parse(text) : {};
    } catch {
        parsed = { raw: text };
    }

    if (!response.ok) {
        throw new Error(parsed.message || parsed.detail || `Request failed with ${response.status}`);
    }

    return parsed;
}

function initReviewPage() {
    const editor = createEditor('codeInput');
    const classNameInput = byId('className');
    const methodNameInput = byId('methodName');
    const parametersInput = byId('parameters');
    const output = byId('output');
    const statusText = byId('statusText');
    const analyzeBtn = byId('analyzeBtn');
    
    const setStatus = (text) => { if (statusText) statusText.textContent = text; };
    const showOutput = (value) => { if (output) output.textContent = toText(value); };
    const getCode = () => (editor ? editor.getValue() : (byId('codeInput')?.value || ""));

    const runComplexity = async () => {
        setStatus(withSpinner('Calculating complexity...'));
        if (statusText) statusText.classList.remove('error');
        if (output) output.classList.remove('error');
        const data = await postJson('/api/complexity', { code: getCode() });
        const cyclomatic = byId('cyclomaticMetric');
        const cognitive = byId('cognitiveMetric');
        if (cyclomatic) cyclomatic.textContent = data.cyclomaticComplexity ?? 0;
        if (cognitive) cognitive.textContent = data.cognitiveComplexity ?? 0;
        showOutput(data);
        setStatus('Complexity analysis complete');
    };

    const runBugs = async () => {
        setStatus(withSpinner('Searching for bugs...'));
        if (statusText) statusText.classList.remove('error');
        if (output) output.classList.remove('error');
        const data = await postJson('/api/bugs', { code: getCode() });
        const bugs = data.bugs ?? [];
        const bugMetric = byId('bugMetric');
        if (bugMetric) bugMetric.textContent = bugs.length;
        showOutput({ bugs });
        setStatus(bugs.length ? 'Bug findings ready' : 'No bugs detected');
    };

    const runTestGeneration = async () => {
        setStatus(withSpinner('Generating test template...'));
        if (statusText) statusText.classList.remove('error');
        if (output) output.classList.remove('error');
        const data = await postJson('/api/generate-test', {
            className: classNameInput?.value || '',
            methodName: methodNameInput?.value || '',
            parameters: parametersInput?.value || '',
            code: getCode()
        });
        showOutput(data.junitCode ?? data);
        setStatus('Test template generated');
    };

    const runFullTestGeneration = async () => {
        setStatus(withSpinner('Generating complete AI test suite...'));
        if (statusText) statusText.classList.remove('error');
        if (output) output.classList.remove('error');
        const data = await postJson(`${PYTHON_AGENT_BASE}/generate-tests`, {
            code: getCode()
        });
        showOutput(data.tests ?? data);
        setStatus('Complete AI test suite generated');
    };

    const runAll = async () => {
        if (!analyzeBtn) return;
        analyzeBtn.disabled = true;
        try {
            await runComplexity();
            await runBugs();
            await runFullTestGeneration();
        } catch (error) {
            setStatus('Analysis failed');
            if (statusText) statusText.classList.add('error');
            if (output) output.classList.add('error');
            showOutput(`Error: ${error?.message || error}`);
        } finally {
            analyzeBtn.disabled = false;
        }
    };

    document.querySelectorAll('button[data-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            if (analyzeBtn) analyzeBtn.disabled = true;
            try {
                if (button.dataset.action === 'complexity') await runComplexity();
                if (button.dataset.action === 'bugs') await runBugs();
                if (button.dataset.action === 'test') await runTestGeneration();
                if (button.dataset.action === 'full-test') await runFullTestGeneration();
            } catch (error) {
                setStatus('Analysis failed');
                if (statusText) statusText.classList.add('error');
                if (output) output.classList.add('error');
                showOutput(`Error: ${error?.message || error}`);
            } finally {
                if (analyzeBtn) analyzeBtn.disabled = false;
            }
        });
    });

    if (analyzeBtn) analyzeBtn.addEventListener('click', runAll);
}

function initRepositoryPage() {
    const repoAnalyzeBtn = byId('repoAnalyzeBtn');
    if (!repoAnalyzeBtn) return;

    const repoUrl = byId('repoUrl');
    const repoBranch = byId('repoBranch');
    const repoMaxFiles = byId('repoMaxFiles');
    const repoToken = byId('repoToken');
    const repoStatusText = byId('repoStatusText');
    const repoOutput = byId('repoOutput');

    repoAnalyzeBtn.addEventListener('click', async () => {
        repoAnalyzeBtn.disabled = true;
        if (repoStatusText) {
            repoStatusText.innerHTML = withSpinner('Analyzing repository...');
            repoStatusText.classList.remove('error');
        }
        if (repoOutput) repoOutput.classList.remove('error');
        try {
            const data = await postJson(`${PYTHON_AGENT_BASE}/analyze-repository`, {
                repoUrl: repoUrl.value,
                branch: repoBranch?.value || null,
                maxFiles: Number(repoMaxFiles?.value || 15),
                githubToken: repoToken?.value || null
            });
            if (repoOutput) {
                repoOutput.textContent = [
                    `Repository: ${data.repository?.url || ''}`,
                    `Branch: ${data.repository?.branch || ''}`,
                    `Files analyzed: ${data.filesAnalyzed?.length || 0}`,
                    '',
                    '=== AI SUMMARY ===',
                    data.aiSummary || '',
                    '',
                    '=== JAVA STATIC FINDINGS ===',
                    toText(data.javaStaticFindings || [])
                ].join('\n');
            }
            if (repoStatusText) repoStatusText.textContent = 'Repository analysis complete';
        } catch (error) {
            if (repoStatusText) {
                repoStatusText.textContent = 'Repository analysis failed';
                repoStatusText.classList.add('error');
            }
            if (repoOutput) {
                repoOutput.classList.add('error');
                repoOutput.textContent = String(error?.message || error);
            }
        } finally {
            repoAnalyzeBtn.disabled = false;
        }
    });
}

function initDebugPage() {
    const debugCodeTextarea = byId('debugCodeInput');
    if (!debugCodeTextarea) return;
    
    const editor = createEditor('debugCodeInput');
    const debugAnalyzeBtn = byId('debugAnalyzeBtn');
    const debugErrorInput = byId('debugErrorInput');
    const debugLanguage = byId('debugLanguage');
    const debugStatusText = byId('debugStatusText');
    const debugOutput = byId('debugOutput');

    if (debugAnalyzeBtn) {
        debugAnalyzeBtn.addEventListener('click', async () => {
            debugAnalyzeBtn.disabled = true;
            if (debugStatusText) {
                debugStatusText.innerHTML = withSpinner('Diagnosing issue...');
                debugStatusText.classList.remove('error');
            }
            if (debugOutput) debugOutput.classList.remove('error');
            try {
                const code = editor ? editor.getValue() : debugCodeTextarea.value;
                const data = await postJson(`${PYTHON_AGENT_BASE}/debug`, {
                    code: code,
                    errorLog: debugErrorInput?.value || '',
                    language: debugLanguage?.value || 'java'
                });
                debugOutput.textContent = data.diagnosis || toText(data);
                if (debugStatusText) debugStatusText.textContent = 'Debug analysis complete';
            } catch (error) {
                if (debugStatusText) {
                    debugStatusText.textContent = 'Debug analysis failed';
                    debugStatusText.classList.add('error');
                }
                if (debugOutput) {
                    debugOutput.classList.add('error');
                    debugOutput.textContent = String(error?.message || error);
                }
            } finally {
                debugAnalyzeBtn.disabled = false;
            }
        });
    }
}

// Global initialization
function bootstrap() {
    initReviewPage();
    initRepositoryPage();
    initDebugPage();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrap);
} else {
    bootstrap();
}
