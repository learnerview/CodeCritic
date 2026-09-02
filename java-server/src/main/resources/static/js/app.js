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

console.log('CodeCritic: dashboard v2.1.0 (JWT + unified workspace + async jobs)');

const SAMPLES = {
    simple: `public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, CodeCritic!");
    }
}`,
    buggy: `public class Calculator {
    public int divide(int a, int b) {
        return a / b;
    }
    public String getName(Object obj) {
        return obj.toString();
    }
}`,
    complex: `public class Router {
    public String route(int status, boolean retry, String body) {
        if (status >= 200 && status < 300) {
            return "ok";
        } else if (status == 404) {
            return "not found";
        } else if (status == 500) {
            if (retry) {
                return "retry";
            }
            return "error";
        } else if (body != null && body.contains("fatal")) {
            return "fatal";
        }
        return "unknown";
    }
}`,
    legacy: `public class Legacy {
    public void process(int strategy) {
        int result = 0;
        switch (strategy) {
            case 1:
                result = compute(1);
                break;
            case 2:
                result = compute(2);
                break;
            case 3:
                result = compute(3);
                break;
            default:
                result = -1;
        }
        System.out.println(result);
    }
    private int compute(int x) {
        return x * 10;
    }
}`,
    empty: `public class Empty {
    // Add a method to analyze
}`
};

function toast(message, type = 'info') {
    const container = byId('toastContainer');
    if (!container) return;
    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => el.classList.add('show'), 10);
    setTimeout(() => {
        el.classList.remove('show');
        setTimeout(() => el.remove(), 300);
    }, 4000);
}

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

    const text = await response.text();
    let parsed;
    try {
        parsed = text ? JSON.parse(text) : {};
    } catch {
        parsed = { raw: text };
    }

    if (response.status === 401) {
        clearToken();
        showLogin();
    }

    if (!response.ok) {
        throw new Error(parsed.message || parsed.detail || `Request failed with ${response.status}`);
    }
    return parsed;
}

async function getJson(url, timeoutMs = 30000) {
    const headers = {};
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response;
    try {
        response = await fetch(url, {
            method: 'GET',
            headers,
            signal: controller.signal
        });
    } catch (error) {
        clearTimeout(timer);
        if (error.name === 'AbortError') {
            throw new Error('Request timed out.');
        }
        throw new Error('Network error: could not reach the service.');
    }
    clearTimeout(timer);

    const text = await response.text();
    let parsed;
    try {
        parsed = text ? JSON.parse(text) : {};
    } catch {
        parsed = { raw: text };
    }

    if (response.status === 401) {
        clearToken();
        showLogin();
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

function showSignupForm() {
    const signinForm = byId('signinForm');
    const signupForm = byId('signupForm');
    const tabs = document.querySelectorAll('.auth-tab');
    const title = byId('authTitle');
    const lede = byId('authLede');
    if (signinForm) signinForm.style.display = 'none';
    if (signupForm) signupForm.style.display = 'flex';
    tabs.forEach((t) => t.classList.remove('active'));
    const signupTab = document.querySelector('.auth-tab[data-auth="signup"]');
    if (signupTab) signupTab.classList.add('active');
    if (title) title.textContent = 'Create account';
    if (lede) lede.textContent = 'Sign up to start analyzing code.';
}

function showSigninForm() {
    const signinForm = byId('signinForm');
    const signupForm = byId('signupForm');
    const tabs = document.querySelectorAll('.auth-tab');
    const title = byId('authTitle');
    const lede = byId('authLede');
    if (signinForm) signinForm.style.display = 'flex';
    if (signupForm) signupForm.style.display = 'none';
    tabs.forEach((t) => t.classList.remove('active'));
    const signinTab = document.querySelector('.auth-tab[data-auth="signin"]');
    if (signinTab) signinTab.classList.add('active');
    if (title) title.textContent = 'Welcome back';
    if (lede) lede.textContent = 'Use your credentials to access the workspace.';
}

function initAuth() {
    const loginBtn = byId('loginBtn');
    const registerBtn = byId('registerBtn');
    const logoutBtn = byId('logoutBtn');
    const loginError = byId('loginError');
    const registerError = byId('registerError');
    const goToRegister = byId('goToRegister');
    const goToSignin = byId('goToSignin');

    if (goToRegister) {
        goToRegister.addEventListener('click', (e) => {
            e.preventDefault();
            showSignupForm();
        });
    }

    if (goToSignin) {
        goToSignin.addEventListener('click', (e) => {
            e.preventDefault();
            showSigninForm();
        });
    }

    document.querySelectorAll('.auth-tab').forEach((tab) => {
        tab.addEventListener('click', () => {
            if (tab.dataset.auth === 'signup') {
                showSignupForm();
            } else {
                showSigninForm();
            }
        });
    });

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

    if (registerBtn) {
        registerBtn.addEventListener('click', async () => {
            if (registerError) registerError.textContent = '';
            const password = byId('registerPassword').value;
            const confirmPassword = byId('registerConfirmPassword').value;
            if (password !== confirmPassword) {
                if (registerError) registerError.textContent = 'Passwords do not match';
                return;
            }
            registerBtn.disabled = true;
            registerBtn.innerHTML = withSpinner('Creating account...');
            try {
                const data = await postJson('/api/auth/register', {
                    username: byId('registerUsername').value.trim(),
                    password: password
                });
                setToken(data.token, data.username);
                hideLogin();
                updateUserChip();
            } catch (error) {
                if (registerError) registerError.textContent = String(error.message || error);
            } finally {
                registerBtn.disabled = false;
                registerBtn.innerHTML = withoutSpinner('Sign up');
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
        showSigninForm();
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
                if (view.id === `tab-${tab}`) {
                    view.classList.add('active');
                    // Fix CodeMirror shrinking to a few lines when initialized in a hidden tab
                    setTimeout(() => {
                        view.querySelectorAll('.CodeMirror').forEach((cmNode) => {
                            if (cmNode.CodeMirror) cmNode.CodeMirror.refresh();
                        });
                    }, 10);
                }
            });
        });
    });
}

function initReviewPage() {
    const editor = createEditor('codeInput');
    if (editor) window.reviewEditor = editor;
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
        setStructuredView('complexity', data, data);
        setStatus('Complexity analysis complete');
        toast('Complexity computed', 'success');
    };

    const runBugs = async () => {
        setStatus(withSpinner('Searching for bugs...'));
        clearError();
        const data = await postJson('/api/bugs', { code: getCode() });
        const bugs = data.bugs ?? [];
        const bugMetric = byId('bugMetric');
        if (bugMetric) bugMetric.textContent = bugs.length;
        setStructuredView('bugs', data, data);
        setStatus(bugs.length ? `${bugs.length} bug finding(s) ready` : 'No bugs detected');
        toast(bugs.length ? `${bugs.length} finding(s)` : 'No bugs', bugs.length ? 'warn' : 'success');
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
        setStructuredView('test', data, data.junitCode ?? data);
        setStatus('Test template generated');
        toast('Test template generated', 'success');
    };

    const runFullTestGeneration = async () => {
        setStatus(withSpinner('Generating complete AI test suite...'));
        clearError();
        if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
        const data = await postJson(`${PYTHON_AGENT_BASE}/generate-tests`, { code: getCode() });
        setStructuredView('ai', data.tests ?? data, data.tests ?? data);
        setStatus('Complete AI test suite generated');
        toast('AI test suite ready', 'success');
    };

    const runReview = async () => {
        setStatus(withSpinner('Running AI review...'));
        clearError();
        if (!PYTHON_AGENT_BASE) throw new Error('Python agent URL is not loaded yet - wait a moment and try again.');
        const data = await postJson(`${PYTHON_AGENT_BASE}/review`, { code: getCode() });
        setStructuredView('ai', data.review ?? data, data.review ?? data);
        setStatus('AI review complete');
        toast('AI review complete', 'success');
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
            toast(`Analysis failed: ${error?.message || error}`, 'error');
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
                toast(`Analysis failed: ${error?.message || error}`, 'error');
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

function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

function initSamples() {
    document.querySelectorAll('[data-sample]').forEach((btn) => {
        btn.addEventListener('click', () => {
            const name = btn.dataset.sample;
            const code = SAMPLES[name];
            if (!code) return;
            if (window.reviewEditor) {
                window.reviewEditor.setValue(code);
                if (window.reviewEditor.setCursor) window.reviewEditor.setCursor({ line: 0, ch: 0 });
                window.reviewEditor.focus();
            } else {
                const ta = byId('codeInput');
                if (ta) ta.value = code;
            }
            toast(`Loaded sample: ${btn.textContent.trim()}`, 'info');
        });
    });
}

function initResultTabs() {
    document.querySelectorAll('.result-tab').forEach((tab) => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.result-tab').forEach((t) => t.classList.remove('active'));
            tab.classList.add('active');
            const view = tab.dataset.result;
            const structured = byId('structuredResult');
            const output = byId('output');
            if (structured) structured.style.display = view === 'structured' ? 'block' : 'none';
            if (output) output.style.display = view === 'raw' ? 'block' : 'none';
        });
    });
}

let lastVerdict = {
    kind: null,
    data: null,
    raw: null
};

function setStructuredView(kind, data, raw) {
    lastVerdict = { kind, data, raw };
    const container = byId('structuredResult');
    const output = byId('output');
    if (!container) return;
    container.innerHTML = renderStructured(kind, data);
    if (output) {
        output.textContent = toText(raw != null ? raw : data);
        output.classList.remove('error');
    }
    // Keep a non-structured raw string if the raw view is active
    if (output && output.style.display !== 'none') {
        // already shown above
    }
}

function renderStructured(kind, data) {
    if (kind === 'complexity') {
        return `
            <div class="stat-grid">
                <div class="stat-card"><span class="stat-num">${esc(data.cyclomaticComplexity ?? 0)}</span><label>Cyclomatic</label></div>
                <div class="stat-card"><span class="stat-num">${esc(data.cognitiveComplexity ?? 0)}</span><label>Cognitive</label></div>
            </div>`;
    }
    if (kind === 'bugs') {
        const bugs = data?.bugs || [];
        if (!bugs.length) {
            return `<div class="ok-banner">No bugs detected — the snippet looks clean.</div>`;
        }
        return `
            <div class="findings">
                <div class="findings-note">
                    ⚠️ These are <em>likely</em> bugs, not confirmed ones — review each before acting.
                    Findings from <a href="https://spotbugs.github.io/" target="_blank" rel="noopener">SpotBugs</a>
                    are static-analysis heuristics and can include false positives.
                </div>
                ${bugs.map((b) => {
                    const isCompile = b.type === 'COMPILATION_ERROR';
                    const isPattern = b.type === 'NullPointerRisk';
                    return `
                    <div class="finding ${isCompile ? 'finding-warn' : ''}">
                        <div class="finding-head">
                            <span class="badge badge-${isCompile || isPattern ? 'warn' : 'danger'}">${esc(b.type || 'Issue')}</span>
                            ${b.line ? `<span class="finding-line">Line ${esc(b.line)}</span>` : ''}
                            ${isCompile ? '' : `<span class="badge badge-info">${isPattern ? 'pattern' : 'SpotBugs'}</span>`}
                        </div>
                        <p class="finding-msg">${esc(b.message || '')}</p>
                        <p class="finding-sugg"><strong>Suggestion:</strong> ${esc(b.suggestion || '—')}</p>
                    </div>`;
                }).join('')}
            </div>`;
    }
    if (kind === 'test') {
        const code = data?.junitCode || (typeof data === 'string' ? data : '');
        return `
            <div class="test-block">
                <div class="test-block-head"><strong>Generated JUnit test</strong></div>
                <pre class="test-code">${esc(code || '(empty)')}</pre>
            </div>`;
    }
    if (kind === 'ai') {
        const text = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
        return `<div class="ai-review"><span class="ai-badge">AI</span>${esc(text)}</div>`;
    }
    return `<pre class="fallback">${esc(toText(data))}</pre>`;
}

/* ================= Async Jobs tab ================= */
let jobHistory = [];

function initJobsPage() {
    const jobsBtns = document.querySelectorAll('[data-job]');
    jobsBtns.forEach((btn) => {
        btn.addEventListener('click', async () => await submitJob(btn, btn.dataset.job));
    });
    const submitAll = byId('submitJobsBtn');
    if (submitAll) submitAll.addEventListener('click', submitAllJobs);
    const refreshJobs = byId('refreshJobsBtn');
    if (refreshJobs) refreshJobs.addEventListener('click', refreshJobHistory);
    const refreshMetrics = byId('refreshMetricsBtn');
    if (refreshMetrics) refreshMetrics.addEventListener('click', refreshMetrics);
    refreshJobHistory();
    refreshMetrics();
}

function jobPayloadFor(jobType) {
    const code = byId('jobCode')?.value.trim() || '';
    const className = byId('jobClassName')?.value.trim() || '';
    const methodName = byId('jobMethodName')?.value.trim() || '';
    const parameters = byId('jobParameters')?.value.trim() || '';
    if (!code && jobType !== 'test-generation') {
        throw new Error('Paste some Java code in the job code box first.');
    }
    const payload = { code };
    if (className || methodName || parameters) {
        payload.className = className || 'Sample';
        payload.methodName = methodName || 'main';
        payload.parameters = parameters || '';
    }
    return payload;
}

async function submitJob(btn, jobType) {
    if (!btn) return;
    const statusText = byId('jobStatusText');
    const original = btn.textContent;
    btn.disabled = true;
    try {
        const payload = jobPayloadFor(jobType);
        if (statusText) statusText.innerHTML = withSpinner(`Submitting ${jobType}...`);
        const data = await postJson(`/api/jobs/${endpointFor(jobType)}`, payload);
        jobHistory.unshift({ jobId: data.jobId, status: 'QUEUED', jobType, submitted: Date.now() });
        renderJobHistory();
        if (statusText) statusText.textContent = `Queued ${jobType} -> ${data.jobId}`;
        toast(`Job queued: ${data.jobId.slice(0, 8)}…`, 'success');
        pollJob(data.jobId, jobType);
    } catch (error) {
        if (statusText) { statusText.textContent = 'Submission failed'; statusText.classList.add('error'); }
        toast(`Submission failed: ${error?.message || error}`, 'error');
    } finally {
        btn.disabled = false;
        if (btn) btn.textContent = original;
    }
}

function endpointFor(jobType) {
    if (jobType === 'complexity-analysis') return 'complexity';
    if (jobType === 'bug-detection') return 'bugs';
    return 'generate-test';
}

async function submitAllJobs() {
    const statusText = byId('jobStatusText');
    const btn = byId('submitJobsBtn');
    if (!btn) return;
    btn.disabled = true;
    try {
        if (statusText) statusText.innerHTML = withSpinner('Enqueuing all three jobs...');
        await submitJobQuiet('complexity-analysis');
        await submitJobQuiet('bug-detection');
        await submitJobQuiet('test-generation');
        if (statusText) statusText.textContent = 'All three jobs enqueued';
        toast('Enqueued 3 background jobs', 'success');
    } catch (error) {
        if (statusText) { statusText.textContent = 'Failed to enqueue'; statusText.classList.add('error'); }
        toast(`Enqueue failed: ${error?.message || error}`, 'error');
    } finally {
        btn.disabled = false;
    }
}

async function submitJobQuiet(jobType) {
    const payload = jobPayloadFor(jobType);
    const data = await postJson(`/api/jobs/${endpointFor(jobType)}`, payload);
    jobHistory.unshift({ jobId: data.jobId, status: 'QUEUED', jobType, submitted: Date.now() });
    renderJobHistory();
    pollJob(data.jobId, jobType);
}

async function pollJob(jobId, jobType) {
    for (let i = 0; i < 60; i++) {
        await sleep(2000);
        try {
            const jr = await getJson(`/api/jobs/${jobId}`);
            const entry = jobHistory.find((j) => j.jobId === jobId);
            if (entry) {
                entry.status = jr.status || 'RUNNING';
                entry.result = jr.result;
                entry.producer = jr.producer;
            }
            renderJobHistory();
            if (jr.status === 'SUCCESS' || jr.status === 'FAILED' || jr.status === 'FAILEDWithError') {
                toast(`${jobType} ${jr.status.toLowerCase() === 'success' ? 'completed' : 'failed'}`, jr.status === 'SUCCESS' ? 'success' : 'error');
                return;
            }
        } catch (e) {
            // job not ready yet -> keep polling
        }
    }
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function renderJobHistory() {
    const container = byId('jobHistory');
    if (!container) return;
    if (!jobHistory.length) {
        container.innerHTML = `<div class="structured-empty">No jobs submitted yet.</div>`;
        return;
    }
    container.innerHTML = jobHistory.map((j) => `
        <div class="job-row">
            <div class="job-id" title="${esc(j.jobId)}">${esc(j.jobId.slice(0, 8))}…</div>
            <div class="job-type">${esc(j.jobType)}</div>
            <div class="job-status status-${statusClass(j.status)}">${esc(j.status || 'QUEUED')}</div>
            <button class="ghost small job-detail" data-jobid="${esc(j.jobId)}">Inspect</button>
        </div>`).join('');
    container.querySelectorAll('.job-detail').forEach((btn) => {
        btn.addEventListener('click', () => {
            const entry = jobHistory.find((x) => x.jobId === btn.dataset.jobid);
            showJobDetail(entry);
        });
    });
}

function statusClass(status) {
    const s = (status || '').toUpperCase();
    if (s.startsWith('SUCCESS')) return 'ok';
    if (s.startsWith('FAIL')) return 'err';
    if (s.startsWith('RUN') || s.startsWith('QUEUED')) return 'run';
    return '';
}

function showJobDetail(entry) {
    const container = byId('structuredResult');
    const output = byId('output');
    // Show the job detail in the metrics panel instead to avoid disturbing review
    const target = byId('metricsView');
    if (target && entry.result) {
        target.innerHTML = `
            <div class="job-detail-view">
                <p class="job-meta"><strong>Job:</strong> ${esc(entry.jobId)} &middot; <strong>Type:</strong> ${esc(entry.jobType)} &middot; <strong>Status:</strong> ${esc(entry.status || '')}</p>
                <pre class="test-code">${esc(toText(entry.result))}</pre>
            </div>`;
        toast('Showing job result', 'info');
    } else if (container && entry.result) {
        container.innerHTML = `<div class="test-block"><div class="test-block-head"><strong>${esc(entry.jobType)} result</strong></div><pre class="test-code">${esc(toText(entry.result))}</pre></div>`;
        if (output) output.textContent = toText(entry.result);
    } else {
        toast('Job still processing — no result yet', 'info');
    }
}

async function refreshJobHistory() {
    // Without a listing endpoint, we surface what we've tracked + queue metrics
    renderJobHistory();
    await refreshMetrics();
}

async function refreshMetrics() {
    const view = byId('metricsView');
    const status = byId('metricsStatus');
    try {
        const data = await getJson('/api/metrics');
        if (view) view.innerHTML = renderMetrics(data);
        if (status) status.textContent = 'Updated';
        updateJobCounters(data);
    } catch (error) {
        if (status) status.textContent = 'Error';
        if (view) view.innerHTML = `<div class="structured-empty">Could not load metrics: ${esc(error?.message || error)}</div>`;
    }
}

function updateJobCounters(metrics) {
    const analysisTypes = Object.keys(metrics?.analysis || {});
    let completed = 0;
    analysisTypes.forEach((t) => { completed += (metrics.analysis[t]?.requests || 0); });
    const queueDepth = byId('jobQueueDepth');
    if (queueDepth) queueDepth.textContent = jobHistory.filter((j) => (j.status || '').startsWith('QUEUED')).length;
    const running = byId('jobRunning');
    if (running) running.textContent = jobHistory.filter((j) => (j.status || '').startsWith('RUN')).length;
    const compEl = byId('jobCompleted');
    if (compEl) compEl.textContent = jobHistory.filter((j) => (j.status || '').startsWith('SUCCESS')).length;
}

function renderMetrics(data) {
    const analysis = data?.analysis || {};
    const types = Object.keys(analysis);
    if (!types.length) {
        return `<div class="structured-empty">No metrics recorded yet — run some analyses first.</div>`;
    }
    const rows = types.map((t) => {
        const m = analysis[t] || {};
        const req = m.requests || 0;
        const avg = m.avgLatencyMs != null ? `${m.avgLatencyMs} ms` : '—';
        const samples = m.samples || 0;
        const pct = req > 0 ? Math.min(100, Math.max(8, Math.round((samples / (typesReduceMax(analysis))) * 100))) : 0;
        return `
            <div class="metric-row">
                <span class="metric-label">${esc(t)}</span>
                <div class="bar"><div class="bar-fill" style="width:${pct}%"></div></div>
                <span class="metric-val">${req} req</span>
                <span class="metric-val muted">${avg}</span>
            </div>`;
    }).join('');
    const spotbugs = data?.spotbugs || {};
    return `
        <div class="metric-table">
            ${rows}
            <div class="metric-legend">
                <span>Requests by analysis type &middot; avg latency</span>
                <span>SpotBugs: ${spotbugs.runs || 0} runs / ${spotbugs.avgMs || 0} ms avg</span>
            </div>
        </div>`;
}

function typesReduceMax(analysis) {
    let max = 1;
    Object.values(analysis).forEach((m) => { const r = m?.requests || 0; if (r > max) max = r; });
    return max;
}

function bootstrap() {
    initAuth();
    initTabs();
    initCopyButtons();
    initReviewPage();
    initRepositoryPage();
    initDebugPage();
    initSamples();
    initResultTabs();
    initJobsPage();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrap);
} else {
    bootstrap();
}
