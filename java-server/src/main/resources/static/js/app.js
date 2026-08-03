const IS_LOCAL = ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname);
let PYTHON_AGENT_BASE = window.CODECRITIC_PYTHON_API || (IS_LOCAL ? `${window.location.protocol}//${window.location.hostname}:8000` : '');
fetch('/api/config')
    .then((r) => (r.ok ? r.json() : null))
    .then((cfg) => {
        if (cfg && cfg.pythonAgentUrl) PYTHON_AGENT_BASE = cfg.pythonAgentUrl;
        console.log(`CodeCritic: Python agent at ${PYTHON_AGENT_BASE}`);
    })
    .catch(() => {
        console.warn('CodeCritic: /api/config unavailable, keeping default agent URL');
    });
const TOKEN_KEY = 'codecritic_token';
const USERNAME_KEY = 'codecritic_username';

const byId = (id) => document.getElementById(id);
const toText = (value) => (typeof value === 'string' ? value : JSON.stringify(value, null, 2));

const withSpinner = (text) => `<span class="loading-spinner"></span>${text}`;
const withoutSpinner = (text) => text.replace(/<span class="loading-spinner"><\/span>/g, '');

console.log('CodeCritic: dashboard v2.0.0 (JWT + unified workspace)');

function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

function setToken(token, username) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USERNAME_KEY, username || 'admin');
}

function clearToken() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USERNAME_KEY);
}

function createEditor(id, mode = 'text/x-java') {
    const el = byId(id);
    if (!el) return null;
    if (typeof CodeMirror === 'undefined') {
        console.error('CodeCritic: CodeMirror is NOT defined!');
        return null;
    }
    try {
        const editor = CodeMirror.fromTextArea(el, {
            mode,
            theme: 'dracula',
            lineNumbers: true,
            matchBrackets: true,
            autoCloseBrackets: true,
            autoCloseTags: true,
            styleActiveLine: true,
            indentUnit: 4,
            tabSize: 4,
            indentWithTabs: false,
            viewportMargin: Infinity,
            lineWrapping: true,
            foldGutter: true,
            gutters: ['CodeMirror-linenumbers', 'CodeMirror-foldgutter'],
            extraKeys: {
                'Ctrl-Space': 'autocomplete',
                'Ctrl-/': 'toggleComment'
            }
        });
        el.style.display = 'none';
        return editor;
    } catch (e) {
        console.error(`CodeCritic: Failed to initialize editor for #${id}:`, e);
        return null;
    }
}

async function postJson(url, body, timeoutMs = 150000) {
    const headers = { 'Content-Type': 'application/json' };
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response;
    try {
        response = await fetch(url, {
            method: 'POST',
            headers,
            body: JSON.stringify(body),
            signal: controller.signal
        });
    } catch (error) {
        clearTimeout(timer);
        if (error.name === 'AbortError') {
            throw new Error('Request timed out. The AI service may be busy or unreachable - try again.');
        }
        throw new Error('Network error: could not reach the service. Check that the backend is running and reachable.');
    }
    clearTimeout(timer);

    if (response.status === 401) {
        clearToken();
        showLogin();
        throw new Error('Session expired. Please sign in again.');
    }

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

async function copyText(text) {
    if (!text) return false;
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) {
            // Fall through to the legacy path
        }
    }
    try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        if (ok) return true;
    } catch (e) {
        // Fall through to selection
    }
    return false;
}

function initCopyButtons() {
    document.querySelectorAll('[data-copy-target]').forEach((btn) => {
        btn.addEventListener('click', async () => {
            const el = byId(btn.dataset.copyTarget);
            if (!el || !el.textContent) return;
            const original = btn.textContent;
            const ok = await copyText(el.textContent);
            btn.textContent = ok ? 'Copied!' : 'Copy failed';
            if (!ok) {
                const selection = window.getSelection();
                const range = document.createRange();
                range.selectNodeContents(el);
                selection.removeAllRanges();
                selection.addRange(range);
            }
            setTimeout(() => { btn.textContent = original; }, 1500);
        });
    });
}

function showLogin() {
    const overlay = byId('loginOverlay');
    if (overlay) overlay.style.display = 'flex';
}

function hideLogin() {
    const overlay = byId('loginOverlay');
    if (overlay) overlay.style.display = 'none';
}

function initAuth() {
    const loginBtn = byId('loginBtn');
    const logoutBtn = byId('logoutBtn');
    const loginError = byId('loginError');

    if (loginBtn) {
        loginBtn.addEventListener('click', async () => {
            if (loginError) loginError.textContent = '';
            loginBtn.disabled = true;
            loginBtn.innerHTML = withSpinner('Signing in...');
            try {
                const data = await postJson('/api/auth/login', {
                    username: byId('loginUsername').value.trim(),
                    password: byId('loginPassword').value
                });
                setToken(data.token, data.username);
                hideLogin();
                updateUserChip();
            } catch (error) {
                if (loginError) loginError.textContent = String(error.message || error);
            } finally {
                loginBtn.disabled = false;
                loginBtn.innerHTML = withoutSpinner('Sign in');
            }
        });
    }

    if (logoutBtn) {
        logoutBtn.addEventListener('click', () => {
            clearToken();
            showLogin();
        });
    }

    if (getToken()) {
        hideLogin();
        updateUserChip();
    } else {
        showLogin();
    }
}

function updateUserChip() {
    const name = byId('userName');
    if (name) name.textContent = localStorage.getItem(USERNAME_KEY) || 'admin';
}

function initTabs() {
    document.querySelectorAll('#mainTabs .tab').forEach((button) => {
        button.addEventListener('click', () => {
            document.querySelectorAll('#mainTabs .tab').forEach((b) => b.classList.remove('active'));
            button.classList.add('active');
            const tab = button.dataset.tab;
            document.querySelectorAll('.tab-view').forEach((view) => {
                view.classList.remove('active');
                if (view.id === `tab-${tab}`) view.classList.add('active');
            });
        });
    });
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
    const setError = () => {
        if (statusText) { statusText.textContent = 'Analysis failed'; statusText.classList.add('error'); }
        if (output) output.classList.add('error');
    };
    const clearError = () => {
        if (statusText) statusText.classList.remove('error');
        if (output) output.classList.remove('error');
    };
    const showOutput = (value) => { if (output) output.textContent = toText(value); };
    const getCode = () => (editor ? editor.getValue() : (byId('codeInput')?.value || ''));

    const runComplexity = async () => {
        setStatus(withSpinner('Calculating complexity...'));
        clearError();
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
        clearError();
        const data = await postJson('/api/bugs', { code: getCode() });
        const bugs = data.bugs ?? [];
        const bugMetric = byId('bugMetric');
        if (bugMetric) bugMetric.textContent = bugs.length;
        showOutput({ bugs });
        setStatus(bugs.length ? `${bugs.length} bug finding(s) ready` : 'No bugs detected');
    };

    const runTestGeneration = async () => {
        setStatus(withSpinner('Generating test template...'));
        clearError();
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
        clearError();
        if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
        const data = await postJson(`${PYTHON_AGENT_BASE}/generate-tests`, { code: getCode() });
        showOutput(data.tests ?? data);
        setStatus('Complete AI test suite generated');
    };

    const runReview = async () => {
        setStatus(withSpinner('Running AI review...'));
        clearError();
        if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
        const data = await postJson(`${PYTHON_AGENT_BASE}/review`, { code: getCode() });
        showOutput(data.review ?? data);
        setStatus('AI review complete');
    };

    const runAll = async () => {
        if (!analyzeBtn) return;
        analyzeBtn.disabled = true;
        try {
            await runComplexity();
            await runBugs();
            await runTestGeneration();
            await runFullTestGeneration();
            await runReview();
        } catch (error) {
            setError();
            showOutput(`Error: ${error?.message || error}`);
        } finally {
            analyzeBtn.disabled = false;
        }
    };

    document.querySelectorAll('#tab-review button[data-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            if (analyzeBtn) analyzeBtn.disabled = true;
            try {
                const action = button.dataset.action;
                if (action === 'complexity') await runComplexity();
                if (action === 'bugs') await runBugs();
                if (action === 'test') await runTestGeneration();
                if (action === 'full-test') await runFullTestGeneration();
                if (action === 'review') await runReview();
            } catch (error) {
                setError();
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
            if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
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
                    '=== REPOSITORY METRICS ===',
                    formatRepoMetrics(data.repoMetrics || {}),
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
                if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
                const code = editor ? editor.getValue() : debugCodeTextarea.value;
                const data = await postJson(`${PYTHON_AGENT_BASE}/debug`, {
                    code,
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

function formatRepoMetrics(m) {
    const lines = [];
    if (m.totalFiles != null) {
        lines.push(`Files inspected: ${m.totalFiles} (Java analyzed: ${m.javaFilesAnalyzed || 0})`);
    }
    if (m.classCount != null) {
        lines.push(`Classes: ${m.classCount} | Methods: ${m.methodCount || 0}`);
    }
    if (m.averageCyclomatic != null) {
        lines.push(`Average cyclomatic complexity: ${m.averageCyclomatic} | Bug density: ${m.bugDensityPerFile} per file`);
    }
    if (m.totalBugFindings != null) {
        lines.push(`Bug findings: ${m.totalBugFindings} in ${m.filesWithBugs || 0} files`);
    }
    if (m.complexityDistribution) {
        const d = m.complexityDistribution;
        lines.push(`Complexity distribution: simple ${d.simple_1_2 || 0} | moderate ${d.moderate_3_7 || 0} | complex ${d.complex_8_15 || 0} | very complex ${d.very_complex_16_plus || 0}`);
    }
    if (Array.isArray(m.complexityHotspots) && m.complexityHotspots.length) {
        lines.push('Complexity hotspots:');
        m.complexityHotspots.forEach((h) => lines.push(`  ${h.path} (cyclomatic ${h.cyclomatic})`));
    }
    if (Array.isArray(m.largestFiles) && m.largestFiles.length) {
        lines.push('Largest files:');
        m.largestFiles.forEach((f) => lines.push(`  ${f.path} (${(f.sizeBytes / 1024).toFixed(1)} KB)`));
    }
    if (m.topPackagesByFileCount && Object.keys(m.topPackagesByFileCount).length) {
        lines.push('Packages:');
        Object.entries(m.topPackagesByFileCount)
            .forEach(([pkg, count]) => lines.push(`  ${pkg}: ${count} file(s)`));
    }
    return lines.length ? lines.join('\n') : '(none)';
}

function bootstrap() {
    initAuth();
    initTabs();
    initCopyButtons();
    initReviewPage();
    initRepositoryPage();
    initDebugPage();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrap);
} else {
    bootstrap();
}
