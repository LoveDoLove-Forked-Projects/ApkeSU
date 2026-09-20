(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const API_BASE = '/api/v2';
  const AUTH_DB = 'apkesu-web-auth-v1';
  const AUTH_STORE = 'credentials';
  const AUTH_RECORD = 'device-signing-key';
  const currentUrl = new URL(window.location.href);
  let initialPairingToken = '';
  if (currentUrl.hash.startsWith('#pair=')) {
    try { initialPairingToken = decodeURIComponent(currentUrl.hash.slice(6)); } catch (_) { /* invalid fragment */ }
    history.replaceState(null, '', `${currentUrl.pathname}${currentUrl.search}`);
  }
  let authCredential = null;
  const state = {
    view: 'home',
    status: null,
    modules: [],
    apps: [],
    settings: null,
    rebootModes: [],
    moduleQuery: '',
    moduleFilter: 'all',
    appQuery: '',
    appFilter: 'all',
    assets: { wallpapers: {}, navIcons: {}, moduleWallpapers: {} },
    assetCatalog: { wallpaperTargets: {}, navIconSlots: {} },
    assetPickTarget: null,
    assetAdjustTarget: null,
    features: [],
    seccomp: null,
    tools: {
      kpm: null,
      susfs: null,
      pathmask: null,
      builtinMount: null,
      kpatchNext: null,
    },
    apiMeta: null,
    pwaPrompt: null,
    toastTimer: 0,
    rebootPending: false,
    lateLoad: false,
    warningExpanded: false,
    expandedInfoRows: {},
    infoValues: {},
    dialogReturnFocus: null,
  };

  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[character]));
  const asBool = (value) => value === true || String(value).toLowerCase() === 'true';
  const errorMessage = (error) => error instanceof Error ? error.message : String(error || '未知错误');

  function versionedApiPath(path) {
    if (path === '/api') return API_BASE;
    if (path.startsWith(`${API_BASE}/`) || !path.startsWith('/api/')) return path;
    return `${API_BASE}${path.slice(4)}`;
  }

  const bytesToHex = (bytes) => Array.from(new Uint8Array(bytes),
    (byte) => byte.toString(16).padStart(2, '0')).join('');

  function bodyBytes(body) {
    if (body === undefined || body === null) return Promise.resolve(new Uint8Array());
    if (typeof body === 'string') return Promise.resolve(new TextEncoder().encode(body));
    if (body instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(body));
    if (ArrayBuffer.isView(body)) {
      return Promise.resolve(new Uint8Array(body.buffer, body.byteOffset, body.byteLength));
    }
    if (body instanceof Blob) return body.arrayBuffer().then((value) => new Uint8Array(value));
    return Promise.reject(new Error('不支持对此请求正文进行签名'));
  }

  function openAuthDatabase() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(AUTH_DB, 1);
      request.onupgradeneeded = () => {
        if (!request.result.objectStoreNames.contains(AUTH_STORE)) {
          request.result.createObjectStore(AUTH_STORE);
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('无法打开签名密钥存储'));
    });
  }

  async function readStoredCredential() {
    const database = await openAuthDatabase();
    try {
      return await new Promise((resolve, reject) => {
        const request = database.transaction(AUTH_STORE, 'readonly').objectStore(AUTH_STORE).get(AUTH_RECORD);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error || new Error('无法读取签名密钥'));
      });
    } finally {
      database.close();
    }
  }

  async function storeCredential(credential) {
    const database = await openAuthDatabase();
    try {
      await new Promise((resolve, reject) => {
        const transaction = database.transaction(AUTH_STORE, 'readwrite');
        transaction.objectStore(AUTH_STORE).put(credential, AUTH_RECORD);
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error || new Error('无法保存签名密钥'));
        transaction.onabort = () => reject(transaction.error || new Error('签名密钥保存已中止'));
      });
    } finally {
      database.close();
    }
  }

  async function clearStoredCredential() {
    const database = await openAuthDatabase();
    try {
      await new Promise((resolve, reject) => {
        const transaction = database.transaction(AUTH_STORE, 'readwrite');
        transaction.objectStore(AUTH_STORE).delete(AUTH_RECORD);
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error || new Error('无法清除旧签名密钥'));
      });
    } finally {
      database.close();
    }
  }

  async function generateCredential() {
    const generated = await crypto.subtle.generateKey(
      { name: 'ECDSA', namedCurve: 'P-256' },
      true,
      ['sign', 'verify'],
    );
    const publicKey = await crypto.subtle.exportKey('raw', generated.publicKey);
    const privateKeyBytes = await crypto.subtle.exportKey('pkcs8', generated.privateKey);
    const privateKey = await crypto.subtle.importKey(
      'pkcs8',
      privateKeyBytes,
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['sign'],
    );
    const keyId = bytesToHex(await crypto.subtle.digest('SHA-256', publicKey));
    return { keyId, publicKey: bytesToHex(publicKey), privateKey, createdAt: Date.now() };
  }

  async function publicApi(path, options = {}) {
    const response = await fetch(versionedApiPath(path), {
      ...options,
      cache: 'no-store',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error((data.error && data.error.message) || `认证请求失败 (${response.status})`);
    }
    return data;
  }

  async function signedHeaders(method, target, body) {
    if (!authCredential || !authCredential.privateKey) throw new Error('当前浏览器尚未完成设备签名配对');
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const nonceBytes = crypto.getRandomValues(new Uint8Array(16));
    const nonce = bytesToHex(nonceBytes);
    const digest = bytesToHex(await crypto.subtle.digest('SHA-256', await bodyBytes(body)));
    const payload = `APKESU-SIGN-V1\n${method}\n${target}\n${digest}\n${timestamp}\n${nonce}`;
    const signature = await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      authCredential.privateKey,
      new TextEncoder().encode(payload),
    );
    return {
      'X-ApkeSU-Key-Id': authCredential.keyId,
      'X-ApkeSU-Timestamp': timestamp,
      'X-ApkeSU-Nonce': nonce,
      'X-ApkeSU-Signature': bytesToHex(signature),
    };
  }

  async function api(path, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeout || 12000);
    try {
      const target = versionedApiPath(path);
      const method = String(options.method || 'GET').toUpperCase();
      const signatureHeaders = await signedHeaders(method, target, options.body);
      const response = await fetch(target, {
        ...options,
        method,
        signal: controller.signal,
        cache: 'no-store',
        credentials: 'same-origin',
        referrerPolicy: 'no-referrer',
        headers: {
          Accept: 'application/json',
          ...(typeof options.body === 'string' ? { 'Content-Type': 'application/json' } : {}),
          ...signatureHeaders,
          ...(options.headers || {}),
        },
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const message = data.error && data.error.message;
        if (response.status === 401) showAuthGate('签名验证失败', message || '请从 ApkeSU 重新打开并配对此浏览器。', true);
        throw new Error(message || `请求失败 (${response.status})`);
      }
      return data;
    } catch (error) {
      if (error && error.name === 'AbortError') throw new Error('请求超时，请重试');
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  function showAuthGate(title, detail, retry = false) {
    $('auth-title').textContent = title;
    $('auth-detail').textContent = detail;
    $('auth-retry').classList.toggle('hidden', !retry);
    $('auth-gate').classList.remove('hidden');
  }

  function hideAuthGate() {
    $('auth-gate').classList.add('hidden');
  }

  async function initializeAuthentication() {
    if (!window.isSecureContext || !window.crypto || !crypto.subtle || !window.indexedDB) {
      showAuthGate('浏览器不支持安全签名', '需要支持 Web Crypto 与 IndexedDB 的现代浏览器，并通过 127.0.0.1 本机地址访问。');
      return false;
    }
    showAuthGate(
      initialPairingToken ? '正在建立签名身份' : '正在验证设备签名',
      initialPairingToken ? '正在生成仅保存在本浏览器的 P-256 私钥。' : '正在检查此浏览器的签名密钥。',
    );
    try {
      const status = await publicApi('/api/auth/status');
      let credential = await readStoredCredential();
      if (initialPairingToken) {
        if (!credential || !credential.privateKey || !credential.publicKey || !credential.keyId) {
          credential = await generateCredential();
        }
        const paired = await publicApi('/api/auth/pair', {
          method: 'POST',
          body: JSON.stringify({
            pairingToken: initialPairingToken,
            publicKey: credential.publicKey,
            keyId: credential.keyId,
          }),
        });
        if (paired.keyId !== credential.keyId) throw new Error('服务端返回的签名身份不匹配');
        await storeCredential(credential);
        authCredential = credential;
        hideAuthGate();
        return true;
      }
      if (!status.paired) {
        showAuthGate('需要首次配对', '请从 ApkeSU 软件管理器的“网页管理器”入口打开本页面。', true);
        return false;
      }
      if (!credential || credential.keyId !== status.keyId || !credential.privateKey) {
        await clearStoredCredential().catch(() => {});
        showAuthGate('此浏览器未获授权', '签名密钥不存在或已被撤销，请从 ApkeSU 重新打开网页管理器完成配对。', true);
        return false;
      }
      authCredential = credential;
      hideAuthGate();
      return true;
    } catch (error) {
      showAuthGate('签名鉴权失败', errorMessage(error), true);
      return false;
    }
  }

  function notify(message, isError = false, duration = 2800) {
    const toast = $('toast');
    clearTimeout(state.toastTimer);
    toast.textContent = message;
    toast.classList.toggle('error', isError);
    toast.classList.remove('hidden');
    state.toastTimer = setTimeout(() => toast.classList.add('hidden'), duration);
  }

  function setBusy(button, busy) {
    if (!button) return;
    button.disabled = busy;
    button.setAttribute('aria-busy', busy ? 'true' : 'false');
  }

  function errorState(title, detail, retry) {
    return `<div class="state error"><b>${esc(title)}</b>${esc(detail)}${retry ? `<div class="state-actions"><button class="btn small" type="button" data-retry="${esc(retry)}">重试</button></div>` : ''}</div>`;
  }

  function setStatusPill(text, kind = '') {
    const pill = $('status-pill');
    if (!pill) return;
    pill.textContent = text;
    // 原生顶栏没有状态胶囊：始终保持隐藏，只作为脚本内部状态记录
    pill.className = `pill hidden${kind ? ` ${kind}` : ''}`;
  }

  function setView(view, updateHash = true) {
    if (!['home', 'superuser', 'modules', 'tools', 'settings'].includes(view)) view = 'home';
    state.view = view;
    document.body.dataset.currentView = view;
    document.querySelectorAll('[data-page]').forEach((page) => {
      const active = page.dataset.page === view;
      page.classList.toggle('active', active);
      page.hidden = !active;
    });
    document.querySelectorAll('[data-view]').forEach((button) => {
      button.classList.toggle('active', button.dataset.view === view);
    });
    closeRebootMenu();
    if (updateHash) history.replaceState(null, '', `#${view}`);
    window.scrollTo({ top: 0, behavior: 'auto' });
  }

  function effectiveDarkTheme(theme) {
    return theme === 'dark'
      || (theme === 'auto' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  function syncThemeChrome(theme) {
    const dark = effectiveDarkTheme(theme);
    const themeColor = dark ? '#101114' : '#f5f5f7';
    const meta = $('pwa-theme-color');
    if (meta) meta.setAttribute('content', themeColor);
    const appleStatusBar = $('apple-status-bar-style');
    if (appleStatusBar) appleStatusBar.setAttribute('content', dark ? 'black-translucent' : 'default');
  }

  function applyTheme(theme) {
    const normalized = ['auto', 'light', 'dark'].includes(theme) ? theme : 'auto';
    if (normalized === 'auto') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.dataset.theme = normalized;
    $('theme-select').value = normalized;
    syncThemeChrome(normalized);
    try { localStorage.setItem('apkesu.web.theme', normalized); } catch (_) { /* unavailable */ }
  }

  function storedTheme() {
    try { return localStorage.getItem('apkesu.web.theme') || 'auto'; } catch (_) { return 'auto'; }
  }

  function toggleTheme() {
    const current = storedTheme();
    const systemDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    const renderedDark = current === 'dark' || (current === 'auto' && systemDark);
    applyTheme(renderedDark ? 'light' : 'dark');
    notify(renderedDark ? '已切换为浅色主题' : '已切换为深色主题');
  }

  function isPwaStandalone() {
    return window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  }

  function updatePwaUi() {
    const standalone = isPwaStandalone();
    const supported = 'serviceWorker' in navigator;
    const installReady = Boolean(state.pwaPrompt);
    const detail = $('pwa-detail');
    const settingsButton = $('install-pwa-settings');
    if (detail) {
      detail.textContent = standalone
        ? '已作为独立应用运行 · 后端与网页资源均由 ksud 提供'
        : !supported
          ? '当前浏览器不支持 Service Worker，无法安装 PWA'
          : installReady
            ? '可安装到桌面 · 安装后自动启用 ksud 开机服务'
            : 'PWA 已就绪 · 可从浏览器菜单选择“安装应用”或“添加到主屏幕”';
    }
    if (settingsButton) {
      settingsButton.disabled = standalone || !supported;
      settingsButton.textContent = standalone ? '已安装' : '安装到桌面';
    }
  }

  async function enablePwaAutoStart() {
    const data = await api('/api/settings/auto-start', {
      method: 'POST',
      body: JSON.stringify({ enabled: true }),
    });
    if (state.settings) state.settings.enabled = Boolean(data.enabled);
  }

  async function installPwa() {
    if (isPwaStandalone()) {
      notify('网页管理器已经作为独立应用运行');
      return;
    }
    if (!state.pwaPrompt) {
      notify('请打开浏览器菜单，选择“安装应用”或“添加到主屏幕”', false, 5000);
      return;
    }
    const prompt = state.pwaPrompt;
    state.pwaPrompt = null;
    updatePwaUi();
    await prompt.prompt();
    const choice = await prompt.userChoice;
    if (choice.outcome !== 'accepted') {
      notify('已取消安装');
      return;
    }
    try {
      await enablePwaAutoStart();
      notify('PWA 已安装，ksud 开机服务已启用', false, 5000);
    } catch (error) {
      notify(`PWA 已安装，但开机服务启用失败：${errorMessage(error)}`, true, 6000);
    }
  }

  async function loadApiMeta() {
    try {
      const data = await api('/api');
      state.apiMeta = data;
      const detail = $('api-version-detail');
      if (detail) {
        detail.textContent = `REST v${data.apiVersion || '-'} · ${data.service || 'ksud'} · ${data.persistentBackend ? '独立持久后端' : '临时后端'}`;
      }
      return data;
    } catch (error) {
      const detail = $('api-version-detail');
      if (detail) detail.textContent = `接口检查失败：${errorMessage(error)}`;
      throw error;
    }
  }

  function registerPwa() {
    updatePwaUi();
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/sw.js', { scope: '/', updateViaCache: 'none' })
      .then((registration) => registration.update())
      .catch((error) => {
        const detail = $('pwa-detail');
        if (detail) detail.textContent = `PWA 注册失败：${errorMessage(error)}`;
      });
  }

  // ---- 主页：文案与状态判定对齐原生 HomeMiuix / HomeUiState ----
  const ROOT_STATES = {
    running: { label: '正常运行', tone: 'ok', glyph: '✓' },
    mismatch: { label: '版本不匹配', tone: 'warn', glyph: '⚠' },
    disconnected: { label: '驱动未连接', tone: 'err', glyph: '✕' },
  };
  const SELINUX_LABELS = {
    Enforcing: '强制执行',
    Permissive: '宽容模式',
    Disabled: '被禁用',
    Unknown: '未知',
  };
  const SELINUX_TONES = { Enforcing: 'ok', Permissive: 'warn', Disabled: 'muted', Unknown: 'muted' };

  function kernelStateOf(status) {
    const kernel = status.kernel || {};
    const userspace = status.userspace || {};
    const available = kernel.available !== false && Number(kernel.version || 0) > 0;
    if (!available) return { ...ROOT_STATES.disconnected, available: false, lateLoad: false, mode: String(kernel.mode || '') };
    const mode = String(kernel.mode || '').toLowerCase();
    const managerCode = String(userspace.versionCode || '').trim();
    const mismatch = managerCode !== '' && String(kernel.version) !== managerCode;
    return {
      ...(mismatch ? ROOT_STATES.mismatch : ROOT_STATES.running),
      available: true,
      lateLoad: mode === 'late-load',
      mode,
    };
  }

  function homeWarningMessages(status) {
    const kernel = status.kernel || {};
    const userspace = status.userspace || {};
    const state = kernelStateOf(status);
    const messages = [];
    if (!state.available) {
      messages.push('ApkeSU 内核驱动未连接，超级用户与模块操作可能不可用。请检查内核安装状态。');
      return messages;
    }
    if (state.tone === 'warn') {
      messages.push(`管理器版本 (${String(userspace.versionCode || '').trim()}) 与 ApkeSU 驱动版本 (${kernel.version}) 不匹配。`);
    }
    if (state.mode === 'gki') {
      messages.push('自 v3.0.0 起 GKI 工作模式将仅用于测试环境，我们不建议用于日常使用，也不再提供镜像文件');
    }
    return messages;
  }

  function renderWarning(messages) {
    const card = $('home-warning');
    const detail = $('home-warning-detail');
    const extra = $('home-warning-extra');
    const toggle = $('home-warning-toggle');
    if (!messages.length) {
      card.classList.add('hidden');
      extra.classList.add('hidden');
      extra.innerHTML = '';
      toggle.classList.add('hidden');
      state.warningExpanded = false;
      return;
    }
    card.classList.remove('hidden');
    detail.textContent = messages[0];
    const rest = messages.slice(1);
    extra.innerHTML = rest.map((message) => `<div>${esc(message)}</div>`).join('');
    const expanded = Boolean(state.warningExpanded) && rest.length > 0;
    extra.classList.toggle('hidden', !expanded);
    toggle.classList.toggle('hidden', rest.length === 0);
    toggle.textContent = expanded ? '收起' : `还有 ${rest.length} 条`;
  }

  function renderMonitor(status, available) {
    const system = status.system || {};
    const selinux = SELINUX_LABELS[system.selinux] ? system.selinux : 'Unknown';
    const seccomp = system.seccomp || {};
    const selinuxLabel = SELINUX_LABELS[selinux];
    const seccompLabel = !seccomp.supported
      ? '内核未开启'
      : seccomp.enabled ? '过滤模式' : '未启用';
    const hasSystem = Boolean(status.system);
    $('status-monitor').classList.toggle('hidden', !hasSystem);
    $('selinux-value').textContent = hasSystem ? selinuxLabel : '-';
    $('seccomp-value').textContent = hasSystem ? seccompLabel : '-';
    const seccompTone = !hasSystem || !seccomp.supported ? 'muted' : seccomp.enabled ? 'ok' : 'warn';
    const tones = { ok: 'ok', warn: 'warn', muted: 'muted' };
    document.querySelector('#status-monitor .monitor-line[data-tone]').dataset.tone = hasSystem ? SELINUX_TONES[selinux] : 'muted';
    document.querySelectorAll('#status-monitor .monitor-line')[1].dataset.tone = tones[seccompTone];
    $('selinux-dot').dataset.tone = SELINUX_TONES[selinux];
    $('seccomp-dot').dataset.tone = seccompTone;
    return hasSystem;
  }

  function infoRow(label, value, options = {}) {
    const key = options.key || label;
    const clamped = options.clamp && String(value).length > 10;
    const display = clamped && !state.expandedInfoRows[key] ? `${String(value).slice(0, 10)}...` : value;
    const expand = options.clamp
      ? `<button class="info-action" type="button" data-info-expand="${esc(key)}" aria-label="${state.expandedInfoRows[key] ? '收起' : '展开'}">${state.expandedInfoRows[key] ? '⌃' : '⌄'}</button>`
      : '';
    return `<div class="info-row${clamped ? ' clamped' : ''}"><div class="info-main"><div class="info-label">${esc(label)}</div><div class="info-value">${esc(display)}</div></div>${expand}<button class="info-action" type="button" data-info-copy="${esc(key)}" title="复制" aria-label="复制${esc(label)}">⧉</button></div>`;
  }

  function renderInfoRows(status, state_) {
    const kernel = status.kernel || {};
    const device = status.device || {};
    const userspace = status.userspace || {};
    const system = status.system || {};
    const managerCode = String(userspace.versionCode || '').trim();
    const managerName = String(userspace.versionName || '').trim();
    const manager = managerName ? `${managerName} (${managerCode})` : managerCode;
    const model = `${device.brand || ''} ${device.model || ''}`.trim() || '未知设备';
    state.infoValues = {
      管理器版本: manager || '-',
      设备型号: model,
      内核版本: kernel.release || '未知',
      钩子类型: state_.available ? 'Tracepoint 钩子' : '--',
      系统指纹: device.fingerprint || '未知',
    };
    if (system.kpm) state.infoValues.KPM = system.kpm;
    if (system.susfs) state.infoValues.SUSFS = system.susfs;
    $('info-rows').innerHTML = Object.keys(state.infoValues)
      .map((label) => infoRow(label, state.infoValues[label], { key: label, clamp: label === '系统指纹' }))
      .join('');
  }

  function renderStatus(data) {
    const kernel = data.kernel || {};
    const device = data.device || {};
    const server = data.server || {};
    const runtime = kernelStateOf(data);

    setStatusPill(runtime.label, runtime.tone === 'ok' ? 'ok' : runtime.tone === 'err' ? 'err' : '');
    const brandSub = $('brand-sub');
    if (brandSub) brandSub.textContent = `${device.model || '本机控制台'} · 127.0.0.1:${server.port || '-'}`;
    state.lateLoad = runtime.lateLoad;

    const card = $('lkm-card');
    card.dataset.tone = runtime.tone;
    $('lkm-bubble').textContent = runtime.glyph;
    $('lkm-watermark').textContent = runtime.glyph;
    $('kernel-state').textContent = runtime.label;
    $('kernel-mode-tag').textContent = runtime.available
      ? (runtime.mode === 'gki' ? 'GKI' : 'LKM')
      : '未连接';
    const extraTag = $('kernel-extra-tag');
    extraTag.textContent = runtime.lateLoad ? '越狱模式' : '';
    extraTag.classList.toggle('hidden', !runtime.lateLoad);
    extraTag.classList.toggle('alert', runtime.lateLoad);
    $('kernel-sub').textContent = runtime.available
      ? `已安装版本：${kernel.version}${kernel.uapiVersion ? `-${kernel.uapiVersion}` : ''}`
      : `内核 ${kernel.release || '未知'} · 无法连接 ApkeSU`;
    document.querySelectorAll('[data-tools-label]').forEach((label) => {
      label.textContent = kernel.nativeKpm ? 'KPM' : '工具';
    });
    const susfsShortcut = $('susfs-shortcut-button');
    if (susfsShortcut) {
      susfsShortcut.classList.toggle('hidden', !(runtime.available && runtime.mode === 'gki' && !runtime.lateLoad));
      susfsShortcut.disabled = false;
    }

    renderWarning(homeWarningMessages(data));
    const hasSystem = renderMonitor(data, runtime.available);
    renderInfoRows(data, runtime);
    $('device-card').classList.toggle('hidden', false);
    if (!hasSystem) $('info-rows').classList.remove('hidden');
    applyAssets();
  }

  async function loadStatus() {
    try {
      const data = await api('/api/status');
      state.status = data;
      renderStatus(data);
      return data;
    } catch (error) {
      setStatusPill('连接失败', 'err');
      $('lkm-card').dataset.tone = 'err';
      $('lkm-bubble').textContent = '✕';
      $('lkm-watermark').textContent = '✕';
      $('kernel-state').textContent = '驱动未连接';
      $('kernel-mode-tag').textContent = '未连接';
      $('kernel-extra-tag').classList.add('hidden');
      $('kernel-sub').textContent = errorMessage(error);
      if ($('susfs-shortcut-button')) $('susfs-shortcut-button').classList.add('hidden');
      renderWarning([`网页服务无法读取内核状态：${errorMessage(error)}`]);
      $('status-monitor').classList.add('hidden');
      state.infoValues = {};
      $('info-rows').innerHTML = ['管理器版本', '设备型号', '内核版本', '钩子类型', '系统指纹']
        .map((label) => `<div class="info-row"><div class="info-main"><div class="info-label">${label}</div><div class="info-value">-</div></div></div>`)
        .join('');
      throw error;
    }
  }

  function moduleMatches(module) {
    const query = state.moduleQuery.trim().toLowerCase();
    const enabled = asBool(module.enabled);
    const removed = asBool(module.remove);
    const webui = asBool(module.web) || asBool(module.webui);
    const text = `${module.name || ''} ${module.id || ''} ${module.author || ''} ${module.description || ''}`.toLowerCase();
    if (query && !text.includes(query)) return false;
    return state.moduleFilter === 'all'
      || (state.moduleFilter === 'enabled' && enabled && !removed)
      || (state.moduleFilter === 'disabled' && !enabled && !removed)
      || (state.moduleFilter === 'webui' && webui && !removed)
      || (state.moduleFilter === 'remove' && removed);
  }

  function renderModules() {
    const filtered = state.modules.filter(moduleMatches);
    const enabledCount = state.modules.filter((module) => asBool(module.enabled) && !asBool(module.remove)).length;
    $('desktop-module-count').textContent = String(state.modules.length);
    $('home-module-count').textContent = String(state.modules.length);
    $('module-summary').textContent = `${state.modules.length} 个模块 · 已启用 ${enabledCount} 个${filtered.length !== state.modules.length ? ` · 显示 ${filtered.length} 个` : ''}`;
    if (!filtered.length) {
      $('module-list').innerHTML = `<div class="state"><b>${state.modules.length ? '没有匹配的模块' : '尚未安装模块'}</b>${state.modules.length ? '请调整搜索词或筛选条件。' : '安装模块后会显示在这里。'}</div>`;
      return;
    }
    $('module-list').innerHTML = filtered.map((module) => {
      const id = String(module.id || '');
      const encodedId = encodeURIComponent(id);
      const enabled = asBool(module.enabled);
      const removed = asBool(module.remove);
      const webui = asBool(module.web) || asBool(module.webui);
      const action = asBool(module.action);
      const updating = asBool(module.update);
      const metamodule = asBool(module.metamodule);
      const badges = [
        removed
          ? '<span class="badge warn">待卸载</span>'
          : enabled ? '<span class="badge ok">已启用</span>' : '<span class="badge">已停用</span>',
        updating ? '<span class="badge warn">待更新</span>' : '',
        metamodule ? '<span class="badge accent">META</span>' : '',
      ].join('');
      const primaryActions = [
        webui ? '<span class="badge">WebUI 仅限软件管理器</span>' : '',
        action ? `<button class="chip-action" type="button" data-module="${encodedId}" data-action="action">执行</button>` : '',
      ].join('');
      const wallpaper = assetMeta('modulewall', id);
      const wallpaperKey = `modulewall/${id}`;
      const wallpaperActions = `<button class="chip-action" type="button" data-asset-pick="${esc(wallpaperKey)}">${wallpaper ? '更换壁纸' : '自定义壁纸'}</button>${wallpaper ? `<button class="chip-action" type="button" data-asset-adjust="${esc(wallpaperKey)}">裁剪壁纸</button><button class="chip-action danger" type="button" data-asset-clear="${esc(wallpaperKey)}">清除壁纸</button>` : ''}`;
      const endAction = removed
        ? `<button class="chip-action" type="button" data-module="${encodedId}" data-action="undo-uninstall">撤销卸载</button>`
        : `<button class="chip-action danger" type="button" data-module="${encodedId}" data-action="uninstall">卸载</button>`;
      return `<article class="module-item${removed ? ' removed' : ''}" data-module-card="${esc(id)}">
        <div class="module-head">
          <div class="module-copy">
            <div class="module-name">${esc(module.name || id)}</div>
            <div class="module-meta">版本: ${esc(module.version || '未知')} · 作者: ${esc(module.author || '未知')}</div>
            <div class="module-id">${esc(id)}</div>
          </div>
          <label class="toggle module-toggle"><input type="checkbox" data-module-toggle="${encodedId}" ${enabled ? 'checked' : ''} aria-label="${enabled ? '停用' : '启用'} ${esc(module.name || id)}"><span></span></label>
        </div>
        <div class="badges">${badges}</div>
        ${module.description ? `<p class="module-desc">${esc(module.description)}</p>` : ''}
        <div class="module-actions">${primaryActions}${wallpaperActions}<span class="module-spacer"></span>${endAction}</div>
      </article>`;
    }).join('');
    $('module-list').querySelectorAll('[data-module-card]').forEach((card) => {
      applyModuleWallpaper(card.dataset.moduleCard, card);
    });
  }

  async function loadModules() {
    $('module-list').innerHTML = '<div class="state"><b>正在读取模块列表</b>请稍候……</div>';
    try {
      const data = await api('/api/modules');
      state.modules = Array.isArray(data.modules) ? data.modules : [];
      renderModules();
      return data;
    } catch (error) {
      $('module-summary').textContent = '模块列表不可用';
      $('home-module-count').textContent = '-';
      $('module-list').innerHTML = errorState('无法读取模块列表', errorMessage(error), 'modules');
      throw error;
    }
  }

  function appMatches(app) {
    const query = state.appQuery.trim().toLowerCase();
    const text = `${app.label || ''} ${app.packageName || ''} ${app.uid || ''}`.toLowerCase();
    if (query && !text.includes(query)) return false;
    return state.appFilter === 'all'
      || (state.appFilter === 'granted' && asBool(app.granted))
      || (state.appFilter === 'denied' && !asBool(app.granted));
  }

  function renderSuperusers() {
    const filtered = state.apps.filter(appMatches);
    const grantedCount = state.apps.filter((app) => asBool(app.granted)).length;
    $('desktop-su-count').textContent = String(grantedCount);
    $('home-su-count').textContent = String(grantedCount);
    $('superuser-summary').textContent = `${state.apps.length} 个应用 · 已授权 ${grantedCount} 个${filtered.length !== state.apps.length ? ` · 显示 ${filtered.length} 个` : ''}`;
    if (!filtered.length) {
      $('superuser-list').innerHTML = `<div class="state"><b>${state.apps.length ? '没有匹配的应用' : '没有可管理的应用'}</b>${state.apps.length ? '请调整搜索词或筛选条件。' : '系统应用不会显示在这里。'}</div>`;
      return;
    }
    $('superuser-list').innerHTML = filtered.map((app) => {
      const granted = asBool(app.granted);
      const packageName = String(app.packageName || '');
      const label = String(app.label || packageName).trim() || packageName;
      const initial = label.charAt(0).toUpperCase() || '?';
      const icon = asBool(app.iconAvailable)
        ? `<img class="app-icon" alt="" loading="lazy" src="${API_BASE}/apps/icon/${encodeURIComponent(packageName)}.png">`
        : '';
      return `<article class="app-item">
        <span class="app-avatar" aria-hidden="true"><span>${esc(initial)}</span>${icon}</span>
        <span class="app-copy">
          <span class="app-name" title="${esc(label)}">${esc(label)}</span>
          <span class="app-package" title="${esc(packageName)}">${esc(packageName)}</span>
          <span class="app-meta">UID ${esc(app.uid)} · ${granted ? '有 Root 权限' : '无 Root 权限'}</span>
        </span>
        <span class="badges"><span class="badge${granted ? ' ok' : ''}">${granted ? '有 Root 权限' : '无 Root 权限'}</span></span>
        <label class="toggle permission-toggle"><input type="checkbox" data-uid="${esc(app.uid)}" data-package="${esc(packageName)}" data-label="${esc(label)}" ${granted ? 'checked' : ''} aria-label="${granted ? '关闭' : '开启'} ${esc(label)} 的 Root 权限"><span></span></label>
      </article>`;
    }).join('');
    $('superuser-list').querySelectorAll('img.app-icon').forEach((image) => {
      image.addEventListener('error', () => image.remove(), { once: true });
    });
  }

  async function loadSuperusers() {
    $('superuser-list').innerHTML = '<div class="state"><b>正在读取应用列表</b>首次读取可能需要几秒。</div>';
    try {
      const data = await api('/api/superuser', { timeout: 20000 });
      state.apps = Array.isArray(data.apps) ? data.apps : [];
      renderSuperusers();
      return data;
    } catch (error) {
      $('superuser-summary').textContent = '授权列表不可用';
      $('home-su-count').textContent = '-';
      $('superuser-list').innerHTML = errorState('无法读取超级用户列表', errorMessage(error), 'superuser');
      throw error;
    }
  }

  const wallpaperHosts = {
    lkm: 'lkm-card',
    superuser: 'superuser-card',
    module: 'module-card',
    device: 'device-card',
  };

  function assetBucket(kind) {
    if (kind === 'wallpaper') return state.assets.wallpapers || {};
    if (kind === 'navicon') return state.assets.navIcons || {};
    return state.assets.moduleWallpapers || {};
  }

  function assetMeta(kind, name) {
    return assetBucket(kind)[name] || null;
  }

  function assetUrl(kind, name) {
    const meta = assetMeta(kind, name);
    return `${API_BASE}/assets/${kind}/${encodeURIComponent(name)}${meta && meta.updatedAt ? `?v=${meta.updatedAt}` : ''}`;
  }

  function assetLabel(kind, name) {
    const catalog = state.assetCatalog || {};
    if (kind === 'modulewall') {
      const module = state.modules.find((entry) => String(entry.id) === name);
      return `模块壁纸 · ${module && (module.name || module.id) || name}`;
    }
    const labels = kind === 'wallpaper' ? catalog.wallpaperTargets : catalog.navIconSlots;
    return labels && labels[name] || name;
  }

  function applyWallpaperStyles(layer, dim, meta, url) {
    if (url) layer.style.backgroundImage = `url("${url}")`;
    layer.style.backgroundSize = meta.fit === 'stretch' ? '100% 100%' : meta.fit === 'contain' ? 'contain' : 'cover';
    layer.style.transform = `scale(${meta.scale || 1}) translate(${meta.offsetX || 0}%, ${meta.offsetY || 0}%)`;
    layer.style.filter = Number(meta.blur) > 0 ? `blur(${Number(meta.blur)}px)` : 'none';
    dim.style.opacity = String(meta.dim === undefined ? 0.35 : meta.dim);
  }

  function applyWallpaper(name) {
    const host = $(wallpaperHosts[name]);
    if (!host) return;
    const meta = assetMeta('wallpaper', name);
    let layer = host.querySelector('.bg-layer');
    let dim = host.querySelector('.bg-dim');
    if (!meta) {
      if (layer) layer.remove();
      if (dim) dim.remove();
      host.classList.remove('card-bg-host', 'light-text');
      return;
    }
    if (!layer) {
      layer = document.createElement('span');
      layer.className = 'bg-layer';
      dim = document.createElement('span');
      dim.className = 'bg-dim';
      host.prepend(dim);
      host.prepend(layer);
    }
    host.classList.add('card-bg-host', 'light-text');
    applyWallpaperStyles(layer, dim, meta, assetUrl('wallpaper', name));
  }

  function applyNavIcon(slot) {
    const meta = assetMeta('navicon', slot);
    document.querySelectorAll(`[data-icon-slot="${slot}"]`).forEach((button) => {
      const icon = button.querySelector('.nav-ico');
      if (!icon) return;
      button.classList.toggle('custom-icon', Boolean(meta));
      icon.style.setProperty('--nav-image', meta ? `url("${assetUrl('navicon', slot)}")` : 'none');
      icon.style.setProperty('--nav-scale', String(meta && meta.scale || 1));
      icon.style.setProperty('--nav-offset-x', `${meta && meta.offsetX || 0}px`);
      icon.style.setProperty('--nav-offset-y', `${meta && meta.offsetY || 0}px`);
    });
  }

  function applyModuleWallpaper(moduleId, card) {
    const meta = assetMeta('modulewall', moduleId);
    let layer = card.querySelector('.bg-layer');
    let dim = card.querySelector('.bg-dim');
    if (!meta) {
      if (layer) layer.remove();
      if (dim) dim.remove();
      card.classList.remove('card-bg-host', 'light-text');
      return;
    }
    if (!layer) {
      layer = document.createElement('span');
      layer.className = 'bg-layer';
      dim = document.createElement('span');
      dim.className = 'bg-dim';
      card.prepend(dim);
      card.prepend(layer);
    }
    card.classList.add('card-bg-host', 'light-text');
    applyWallpaperStyles(layer, dim, meta, assetUrl('modulewall', moduleId));
  }

  function applyAssets() {
    Object.keys(wallpaperHosts).forEach(applyWallpaper);
    ['home', 'superuser', 'module', 'kpm', 'settings'].forEach(applyNavIcon);
    document.querySelectorAll('[data-module-card]').forEach((card) => {
      applyModuleWallpaper(card.dataset.moduleCard, card);
    });
  }

  function assetRow(kind, name, label, hint) {
    const meta = assetMeta(kind, name);
    const key = `${kind}/${name}`;
    const preview = meta
      ? `<img class="asset-thumb" alt="" src="${assetUrl(kind, name)}">`
      : '<span class="asset-thumb empty">未设置</span>';
    return `<div class="row"><div class="row-main"><div class="row-title">${esc(label)}</div><div class="row-detail">${meta ? `已设置 · ${hint}` : '未设置图片'}</div></div>${preview}<div class="row-actions"><button class="btn small" type="button" data-asset-pick="${key}">${meta ? '更换' : '选择图片'}</button>${meta ? `<button class="btn small" type="button" data-asset-adjust="${key}">裁剪</button><button class="btn small danger" type="button" data-asset-clear="${key}">清除</button>` : ''}</div></div>`;
  }

  function renderAssetSettings() {
    const catalog = state.assetCatalog || {};
    const targets = catalog.wallpaperTargets || {};
    const slots = catalog.navIconSlots || {};
    $('wallpaper-rows').innerHTML = Object.keys(targets).map((name) =>
      assetRow('wallpaper', name, targets[name], '可裁剪、缩放、移动、变暗和模糊')
    ).join('') || '<div class="state"><b>没有可配置的卡片</b>当前服务未返回外观槽位。</div>';
    $('nav-icon-rows').innerHTML = Object.keys(slots).map((name) =>
      assetRow('navicon', name, slots[name], '可在方形区域内裁剪、缩放和移动')
    ).join('') || '<div class="state"><b>没有可配置的图标</b>当前服务未返回导航槽位。</div>';
  }

  function startAssetPick(key) {
    const [kind, name] = String(key).split('/');
    if (!kind || !name) return;
    state.assetPickTarget = { kind, name };
    $('asset-file-input').value = '';
    $('asset-file-input').click();
  }

  async function uploadAsset(file) {
    const target = state.assetPickTarget;
    if (!target || !file) return;
    const maximum = Number(state.assetCatalog.maxBytes || 8 * 1024 * 1024);
    if (file.size > maximum) {
      notify('图片不能超过 8 MiB', true, 5000);
      return;
    }
    notify('正在上传图片……', false, 8000);
    try {
      await api(`/api/assets/${target.kind}/${target.name}`, {
        method: 'POST',
        body: file,
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        timeout: 120000,
      });
      notify('图片已更新');
      const key = `${target.kind}/${target.name}`;
      await loadSettings();
      if (target.kind === 'modulewall') renderModules();
      openAssetAdjustment(key);
    } catch (error) {
      notify(`上传失败：${errorMessage(error)}`, true, 5000);
    } finally {
      state.assetPickTarget = null;
    }
  }

  async function clearAsset(key) {
    const [kind, name] = String(key).split('/');
    if (!assetMeta(kind, name)) return;
    if (!window.confirm(`清除“${assetLabel(kind, name)}”的自定义图片？`)) return;
    try {
      await api(`/api/assets/${kind}/${name}`, { method: 'DELETE' });
      notify('自定义图片已清除');
      await loadSettings();
      if (kind === 'modulewall') renderModules();
    } catch (error) {
      notify(`清除失败：${errorMessage(error)}`, true, 5000);
    }
  }

  function rangeField(id, label, min, max, step, value, unit) {
    return `<label class="field"><span>${label}</span><input id="${id}" type="range" min="${min}" max="${max}" step="${step}" value="${value}"><output id="${id}-value">${value}${unit}</output></label>`;
  }

  function currentAssetAdjustment() {
    const target = state.assetAdjustTarget;
    if (!target) return {};
    if (target.kind === 'navicon') {
      return {
        scale: Number($('asset-scale').value) / 100,
        offsetX: Number($('asset-offset-x').value),
        offsetY: Number($('asset-offset-y').value),
      };
    }
    return {
      fit: $('asset-fit').value,
      scale: Number($('asset-scale').value) / 100,
      offsetX: Number($('asset-offset-x').value),
      offsetY: Number($('asset-offset-y').value),
      dim: Number($('asset-dim').value) / 100,
      blur: Number($('asset-blur').value),
    };
  }

  function previewAssetAdjustment() {
    const target = state.assetAdjustTarget;
    if (!target) return;
    const meta = currentAssetAdjustment();
    const cropLayer = $('asset-crop-image');
    const cropDim = $('asset-crop-dim');
    if (target.kind === 'navicon') {
      if (cropLayer) {
        cropLayer.style.transform = `translate(${meta.offsetX}px, ${meta.offsetY}px) scale(${meta.scale})`;
      }
      document.querySelectorAll(`[data-icon-slot="${target.name}"] .nav-ico`).forEach((icon) => {
        icon.style.setProperty('--nav-scale', String(meta.scale));
        icon.style.setProperty('--nav-offset-x', `${meta.offsetX}px`);
        icon.style.setProperty('--nav-offset-y', `${meta.offsetY}px`);
      });
    } else {
      if (cropLayer && cropDim) applyWallpaperStyles(cropLayer, cropDim, meta, null);
      const host = target.kind === 'wallpaper'
        ? $(wallpaperHosts[target.name])
        : Array.from(document.querySelectorAll('[data-module-card]'))
          .find((card) => card.dataset.moduleCard === target.name);
      const layer = host && host.querySelector('.bg-layer');
      const dim = host && host.querySelector('.bg-dim');
      if (layer && dim) applyWallpaperStyles(layer, dim, meta, null);
    }
  }

  function openAssetAdjustment(key) {
    const [kind, name] = String(key).split('/');
    const meta = assetMeta(kind, name);
    if (!meta) return;
    state.assetAdjustTarget = { kind, name };
    const imageUrl = assetUrl(kind, name);
    const body = kind === 'navicon'
      ? `<div class="asset-crop-preview icon"><span id="asset-crop-image" class="asset-crop-image icon" style="background-image:url('${imageUrl}')"></span></div>${rangeField('asset-scale', '缩放', 50, 300, 5, Math.round((meta.scale || 1) * 100), '%')}${rangeField('asset-offset-x', '水平移动', -12, 12, 1, Math.round(meta.offsetX || 0), 'px')}${rangeField('asset-offset-y', '垂直移动', -12, 12, 1, Math.round(meta.offsetY || 0), 'px')}`
      : `<div class="asset-crop-preview wallpaper"><span id="asset-crop-image" class="asset-crop-image" style="background-image:url('${imageUrl}')"></span><span id="asset-crop-dim" class="asset-crop-dim"></span><span class="asset-crop-frame" aria-hidden="true"></span></div><label class="field"><span>填充方式</span><select id="asset-fit"><option value="cover">填充裁剪</option><option value="contain">完整显示</option><option value="stretch">拉伸</option></select></label>${rangeField('asset-scale', '裁剪缩放', 50, 300, 5, Math.round((meta.scale || 1) * 100), '%')}${rangeField('asset-offset-x', '水平移动', -50, 50, 1, Math.round(meta.offsetX || 0), '%')}${rangeField('asset-offset-y', '垂直移动', -50, 50, 1, Math.round(meta.offsetY || 0), '%')}${rangeField('asset-dim', '变暗', 0, 85, 5, Math.round((meta.dim === undefined ? 0.35 : meta.dim) * 100), '%')}${rangeField('asset-blur', '模糊', 0, 12, 1, Math.round(meta.blur || 0), 'px')}`;
    openDialog(`裁剪外观 · ${assetLabel(kind, name)}`, body, '<button id="asset-adjust-save" class="btn primary" type="button">保存裁剪</button><button class="btn" type="button" data-close-dialog>取消</button>');
    if ($('asset-fit')) $('asset-fit').value = meta.fit || 'cover';
    $('dialog-body').querySelectorAll('input[type="range"]').forEach((input) => {
      input.addEventListener('input', () => {
        const unit = input.id === 'asset-blur' ||
          (kind === 'navicon' && (input.id === 'asset-offset-x' || input.id === 'asset-offset-y'))
          ? 'px'
          : '%';
        $(`${input.id}-value`).textContent = `${input.value}${unit}`;
        previewAssetAdjustment();
      });
    });
    if ($('asset-fit')) $('asset-fit').addEventListener('change', previewAssetAdjustment);
    $('asset-adjust-save').addEventListener('click', saveAssetAdjustment);
    previewAssetAdjustment();
  }

  async function saveAssetAdjustment() {
    const target = state.assetAdjustTarget;
    if (!target) return;
    const button = $('asset-adjust-save');
    setBusy(button, true);
    try {
      await api('/api/settings/asset-meta', {
        method: 'POST',
        body: JSON.stringify({ kind: target.kind, name: target.name, meta: currentAssetAdjustment() }),
      });
      closeDialog();
      notify('外观参数已保存');
      await loadSettings();
      if (target.kind === 'modulewall') renderModules();
    } catch (error) {
      notify(`保存失败：${errorMessage(error)}`, true, 5000);
      setBusy(button, false);
    }
  }

  function renderFeatures() {
    const rows = state.features.map((feature) => {
      const status = feature.managed ? '由模块管理' : feature.supported ? (feature.enabled ? '已启用' : '已关闭') : '内核不支持';
      const detail = `${feature.summary || ''}${feature.rebootHint ? ` · ${feature.rebootHint}` : ''} · ${status}`;
      return `<div class="row"><div class="row-main"><div class="row-title">${esc(feature.label || feature.key)}</div><div class="row-detail">${esc(detail)}</div></div><label class="toggle"><input type="checkbox" data-feature="${esc(feature.key)}" ${feature.enabled ? 'checked' : ''} ${feature.supported ? '' : 'disabled'} aria-label="${esc(feature.label || feature.key)}"><span></span></label></div>`;
    }).join('');
    $('feature-rows').innerHTML = rows || '<div class="state"><b>没有可管理的内核特性</b>请确认内核与 ksud 版本匹配。</div>';
    const seccomp = state.seccomp || {};
    $('seccomp-detail').textContent = seccomp.status === null || seccomp.status === undefined
      ? '当前内核未提供状态接口'
      : `状态 0x${Number(seccomp.status).toString(16)} · 调用 ${seccomp.callCount ?? '-'} · 释放 ${seccomp.releaseCount ?? '-'} · 失败 ${seccomp.failureCount ?? '-'} · 最近错误 ${seccomp.lastError ?? 0}`;
  }

  async function loadFeatures() {
    try {
      const data = await api('/api/features');
      state.features = Array.isArray(data.features) ? data.features : [];
      state.seccomp = data.seccomp || null;
      renderFeatures();
      return data;
    } catch (error) {
      $('feature-rows').innerHTML = errorState('无法读取内核特性', errorMessage(error), 'features');
      $('seccomp-detail').textContent = '读取失败';
      throw error;
    }
  }

  async function changeFeature(input) {
    const enabled = input.checked;
    input.disabled = true;
    try {
      const data = await api('/api/features', {
        method: 'POST',
        body: JSON.stringify({ key: input.dataset.feature, enabled }),
      });
      notify(`${enabled ? '已启用' : '已关闭'}${data.rebootHint ? ` · ${data.rebootHint}` : ''}`);
      await loadFeatures();
    } catch (error) {
      input.checked = !enabled;
      notify(`功能更新失败：${errorMessage(error)}`, true, 5000);
    } finally {
      input.disabled = false;
    }
  }

  function stealthCodeIsValid(value) {
    return String(value || '').trim().length > 0;
  }

  function openStealthDialog(enableAfterSave) {
    const warning = enableAfterSave
      ? '<div class="notice warn"><b>启用后软件管理器会伪装为未安装</b><span>超级用户、模块、KPM 与设置页面会隐藏。之后只能在网页管理器输入密令或通过拨号密令关闭。</span></div>'
      : '';
    openDialog(
      enableAfterSave ? '启用隐身模式' : '设置隐身密令',
      `${warning}<label class="field"><span>新密令${enableAfterSave ? '（留空则保留当前密令）' : ''}</span><input id="stealth-code-input" type="password" autocomplete="new-password" value="" aria-label="隐身密令" autofocus></label><p class="sub">服务不会把当前密令返回到网页。密令不限制长度或字符；使用拨号关闭时输入 *#*#密令#*#*。</p>`,
      '<button id="stealth-save" class="btn primary" type="button">保存</button><button class="btn" type="button" data-close-dialog>取消</button>',
    );
    $('stealth-save').addEventListener('click', () => saveStealth(enableAfterSave));
  }

  function openStealthDisableDialog() {
    openDialog(
      '关闭隐身模式',
      '<div class="notice warn"><b>需要验证隐身密令</b><span>请输入启用隐身模式时保存的密令。</span></div><label class="field"><span>密令</span><input id="stealth-disable-code" type="password" autocomplete="off" value="" aria-label="隐身密令" autofocus></label>',
      '<button id="stealth-disable" class="btn danger" type="button">验证并关闭</button><button class="btn" type="button" data-close-dialog>取消</button>',
    );
    $('stealth-disable').addEventListener('click', disableStealth);
  }

  async function disableStealth() {
    const input = $('stealth-disable-code');
    const code = input.value.trim();
    if (!stealthCodeIsValid(code)) {
      notify('密令不能为空', true, 4000);
      input.focus();
      return;
    }
    const button = $('stealth-disable');
    setBusy(button, true);
    try {
      await api('/api/stealth', {
        method: 'POST',
        body: JSON.stringify({ enabled: false, code }),
      });
      closeDialog();
      notify('隐身模式已关闭，密令备份仍保留');
      await loadSettings();
    } catch (error) {
      notify(`隐身模式关闭失败：${errorMessage(error)}`, true, 5000);
      setBusy(button, false);
      input.focus();
      input.select();
    }
  }

  async function saveStealth(enableAfterSave) {
    const input = $('stealth-code-input');
    const code = input.value.trim();
    if (!stealthCodeIsValid(code) && !enableAfterSave) {
      notify('密令不能为空', true, 4000);
      input.focus();
      return;
    }
    const currentEnabled = Boolean(state.settings && state.settings.stealth && state.settings.stealth.enabled);
    const button = $('stealth-save');
    setBusy(button, true);
    try {
      const payload = { enabled: enableAfterSave || currentEnabled };
      if (code) payload.code = code;
      await api('/api/stealth', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      closeDialog();
      notify(enableAfterSave ? '隐身模式已启用' : '隐身密令已保存');
      await loadSettings();
    } catch (error) {
      notify(`隐身模式更新失败：${errorMessage(error)}`, true, 5000);
      setBusy(button, false);
    }
  }

  async function changeStealth(input) {
    if (input.checked) {
      input.checked = false;
      openStealthDialog(true);
      return;
    }
    input.checked = true;
    openStealthDisableDialog();
  }

  const MANAGER_SETTING_INPUTS = {
    language: 'manager-language',
    checkModuleUpdate: 'manager-check-module-update',
    showVersionMismatchWarning: 'manager-version-warning',
    showGkiWarning: 'manager-gki-warning',
    showHomeSupportCard: 'manager-support-card',
    showHomeLearnCard: 'manager-learn-card',
  };

  function renderManagerSettings(manager, syncError) {
    const settings = manager || {};
    Object.entries(MANAGER_SETTING_INPUTS).forEach(([key, id]) => {
      const input = $(id);
      if (!input) return;
      if (input.tagName === 'SELECT') input.value = settings[key] || 'zh-CN';
      else input.checked = settings[key] !== false;
      input.disabled = false;
    });
    $('manager-home-title-detail').textContent = settings.customHomeTitle
      ? settings.customHomeTitle
      : '当前跟随默认名称';
    const error = $('manager-settings-error');
    error.classList.toggle('hidden', !syncError);
    const message = error.querySelector('span');
    if (message) message.textContent = syncError || '';
  }

  async function updateManagerSetting(key, value, input) {
    if (input) input.disabled = true;
    try {
      const result = await api('/api/settings/manager', {
        method: 'POST',
        body: JSON.stringify({ [key]: value }),
      });
      state.settings = { ...(state.settings || {}), manager: result.manager };
      renderManagerSettings(result.manager, '');
      notify('软件管理器设置已同步');
      return result.manager;
    } catch (error) {
      renderManagerSettings(state.settings && state.settings.manager, errorMessage(error));
      notify(`设置同步失败：${errorMessage(error)}`, true, 5000);
      throw error;
    } finally {
      if (input) input.disabled = false;
    }
  }

  function openManagerHomeTitleDialog() {
    const manager = state.settings && state.settings.manager || {};
    openDialog(
      '自定义主页顶部名称',
      `<label class="field"><span>主页顶部名称</span><input id="manager-home-title-input" type="text" maxlength="40" value="${esc(manager.customHomeTitle || '')}" placeholder="留空则使用默认名称" autofocus></label><p class="sub">最多 40 个字符，留空可恢复默认名称。</p>`,
      '<button id="manager-home-title-save" class="btn primary" type="button">保存</button><button class="btn" type="button" data-close-dialog>取消</button>',
    );
    $('manager-home-title-save').addEventListener('click', async (event) => {
      const value = $('manager-home-title-input').value.trim();
      setBusy(event.currentTarget, true);
      try {
        await updateManagerSetting('customHomeTitle', value);
        closeDialog();
      } catch (_) {
        setBusy(event.currentTarget, false);
      }
    });
  }

  function dynamicManagerStatusText(status) {
    if (!status || status.supported === false) return '当前内核不支持';
    if (status.active) return '已配置 · 已被内核识别';
    if (status.configured) return '已配置 · 等待兼容管理器生效';
    return '未配置';
  }

  async function loadDynamicManagerSummary() {
    try {
      const data = await api('/api/dynamic-manager');
      $('dynamic-manager-detail').textContent = dynamicManagerStatusText(data.status);
      return data.status;
    } catch (error) {
      $('dynamic-manager-detail').textContent = `读取失败：${errorMessage(error)}`;
      throw error;
    }
  }

  async function openDynamicManagerDialog() {
    openDialog(
      '动态管理器',
      '<div class="state"><b>正在读取动态管理器状态</b>请稍候……</div>',
      '<button class="btn" type="button" data-close-dialog>关闭</button>',
    );
    try {
      const data = await api('/api/dynamic-manager');
      const status = data.status || {};
      const disabled = status.supported === false ? ' disabled' : '';
      openDialog(
        '动态管理器',
        `<div class="notice${status.supported === false ? ' warn' : ''}"><b>${esc(dynamicManagerStatusText(status))}</b><span>${status.error ? esc(status.error) : '管理当前内核识别的副管理器签名证书。'}</span></div><label class="field"><span>证书大小（256-4096）</span><input id="dynamic-manager-size" type="number" min="256" max="4096" value="${esc(status.certificateSize || '')}"${disabled}></label><label class="field"><span>证书 SHA-256（64 位小写十六进制）</span><input id="dynamic-manager-hash" type="text" maxlength="64" spellcheck="false" value="${esc(status.certificateSha256 || '')}"${disabled}></label>`,
        `<button id="dynamic-manager-clear" class="btn danger" type="button"${disabled}>清除</button><button id="dynamic-manager-save" class="btn primary" type="button"${disabled}>保存</button><button class="btn" type="button" data-close-dialog>取消</button>`,
      );
      if (status.supported === false) return;
      $('dynamic-manager-save').addEventListener('click', async (event) => {
        const size = Number($('dynamic-manager-size').value);
        const hash = $('dynamic-manager-hash').value.trim();
        if (!Number.isInteger(size) || size < 256 || size > 4096 || !/^[0-9a-f]{64}$/.test(hash)) {
          notify('请输入 256-4096 的证书大小和 64 位小写 SHA-256', true, 5000);
          return;
        }
        setBusy(event.currentTarget, true);
        try {
          await api('/api/dynamic-manager', {
            method: 'POST',
            body: JSON.stringify({ action: 'set', certificateSize: size, certificateSha256: hash }),
          });
          closeDialog();
          notify('动态管理器证书已保存');
          await loadDynamicManagerSummary();
        } catch (error) {
          notify(`动态管理器更新失败：${errorMessage(error)}`, true, 5000);
          setBusy(event.currentTarget, false);
        }
      });
      $('dynamic-manager-clear').addEventListener('click', async (event) => {
        if (!window.confirm('清除动态管理器证书并撤销副管理器？')) return;
        setBusy(event.currentTarget, true);
        try {
          await api('/api/dynamic-manager', {
            method: 'POST',
            body: JSON.stringify({ action: 'clear' }),
          });
          closeDialog();
          notify('动态管理器已清除');
          await loadDynamicManagerSummary();
        } catch (error) {
          notify(`动态管理器清除失败：${errorMessage(error)}`, true, 5000);
          setBusy(event.currentTarget, false);
        }
      });
    } catch (error) {
      $('dialog-body').innerHTML = errorState('无法读取动态管理器', errorMessage(error));
    }
  }

  function renderSettings(data) {
    state.assets = data.assets || { wallpapers: {}, navIcons: {}, moduleWallpapers: {} };
    state.assetCatalog = data.assetCatalog || { wallpaperTargets: {}, navIconSlots: {} };
    $('settings-card').innerHTML = [
      `<div class="row"><div class="row-main"><div class="row-title">开机自动启动</div><div class="row-detail">由 ksud 在开机流程中启动网页服务</div></div><label class="toggle"><input id="auto-start" type="checkbox" ${data.enabled ? 'checked' : ''} aria-label="开机自动启动"><span></span></label></div>`,
      `<div class="row"><div class="row-main"><div class="row-title">监听地址</div><div class="row-detail">${esc(data.bindAddress || '127.0.0.1')}:${esc(data.port || '-')}</div></div></div>`,
      `<div class="row"><div class="row-main"><div class="row-title">认证方式</div><div class="row-detail">${esc(data.authentication || 'token-cookie')}</div></div></div>`,
      `<div class="row"><div class="row-main"><div class="row-title">配置文件</div><div class="row-detail"><code>${esc(data.configPath || '-')}</code></div></div></div>`,
    ].join('');
    const stealth = data.stealth || {};
    $('stealth-detail').textContent = stealth.enabled
      ? '已启用 · 软件管理器主页伪装为未安装'
      : `未启用${stealth.codeBackedUp ? ' · 密令备份仍保留' : ''}`;
    $('stealth-toggle').checked = Boolean(stealth.enabled);
    $('stealth-toggle').disabled = false;
    $('stealth-code-detail').textContent = stealth.codeBackedUp ? '已安全保存（网页不回显）' : '尚未备份';
    renderManagerSettings(data.manager, data.managerSettingsError || '');
    loadDynamicManagerSummary().catch(() => {});
    renderAssetSettings();
    applyAssets();
  }

  async function loadSettings() {
    $('settings-card').innerHTML = '<div class="state"><b>正在读取服务设置</b>请稍候……</div>';
    try {
      const data = await api('/api/settings');
      state.settings = data;
      renderSettings(data);
      return data;
    } catch (error) {
      $('settings-card').innerHTML = errorState('无法读取服务设置', errorMessage(error), 'settings');
      $('wallpaper-rows').innerHTML = errorState('无法读取卡片设置', errorMessage(error), 'settings');
      $('nav-icon-rows').innerHTML = errorState('无法读取图标设置', errorMessage(error), 'settings');
      throw error;
    }
  }

  function setDialogBackgroundInert(inert) {
    document.querySelectorAll('.topbar, .tabs, main, .navbar').forEach((element) => {
      element.inert = inert;
      if (inert) element.setAttribute('aria-hidden', 'true');
      else element.removeAttribute('aria-hidden');
    });
  }

  function dialogFocusables() {
    return Array.from($('dialog').querySelectorAll(
      'button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
    )).filter((element) => element.getClientRects().length > 0);
  }

  function openDialog(title, body, foot) {
    const dialog = $('dialog');
    if (dialog.classList.contains('hidden')) {
      state.dialogReturnFocus = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    }
    $('dialog-title').textContent = title;
    $('dialog-body').innerHTML = body;
    $('dialog-foot').innerHTML = foot || '<button class="btn" type="button" data-close-dialog>取消</button>';
    setDialogBackgroundInert(true);
    dialog.removeAttribute('aria-hidden');
    dialog.classList.remove('hidden');
    window.requestAnimationFrame(() => {
      const initialFocus = dialog.querySelector('[autofocus]') || dialogFocusables()[0];
      if (initialFocus) initialFocus.focus();
    });
  }

  function closeDialog() {
    if (state.rebootPending) return;
    const dialog = $('dialog');
    if (dialog.classList.contains('hidden')) return;
    dialog.classList.add('hidden');
    dialog.setAttribute('aria-hidden', 'true');
    $('dialog-body').innerHTML = '';
    setDialogBackgroundInert(false);
    if (state.assetAdjustTarget) {
      state.assetAdjustTarget = null;
      applyAssets();
    }
    const returnFocus = state.dialogReturnFocus;
    state.dialogReturnFocus = null;
    if (returnFocus && returnFocus.isConnected) returnFocus.focus();
  }

  // 与原生 RebootListPopupMiuix 同一份菜单：顺序、文案、可见性都由服务端 modes 决定
  const REBOOT_LABELS = {
    system: '重启',
    userspace: '用户空间重启',
    soft: '软重启',
    recovery: '重启到 Recovery',
    bootloader: '重启到 BootLoader',
    download: '重启到 Download',
    edl: '重启到 EDL',
  };
  const REBOOT_ORDER = ['system', 'userspace', 'soft', 'recovery', 'bootloader', 'download', 'edl'];

  function closeRebootMenu() {
    const menu = $('reboot-menu');
    if (!menu || menu.classList.contains('hidden')) return;
    menu.classList.add('hidden');
    menu.innerHTML = '';
    $('reboot-button').setAttribute('aria-expanded', 'false');
  }

  function renderRebootMenu(modes, error) {
    const menu = $('reboot-menu');
    if (error) {
      menu.innerHTML = `<div class="popup-note">${esc(error)}</div>`;
      return;
    }
    const ordered = REBOOT_ORDER
      .map((id) => modes.find((mode) => String(mode.id) === id))
      .filter(Boolean);
    menu.innerHTML = ordered
      .map((mode) => {
        const id = String(mode.id);
        const label = REBOOT_LABELS[id] || mode.label || id;
        const supported = mode.supported !== false;
        return `<button class="popup-item" type="button" role="menuitem" data-reboot="${esc(id)}"${supported ? '' : ' disabled title="当前设备不支持"'}>${esc(supported ? label : `${label}（不支持）`)}</button>`;
      })
      .join('') || '<div class="popup-note">没有可用的重启方式</div>';
  }

  async function openRebootMenu() {
    const menu = $('reboot-menu');
    if (!menu.classList.contains('hidden')) {
      closeRebootMenu();
      return;
    }
    $('reboot-button').setAttribute('aria-expanded', 'true');
    menu.innerHTML = '<div class="popup-note">正在检查设备能力……</div>';
    menu.classList.remove('hidden');
    try {
      const data = await api('/api/reboot');
      state.rebootModes = Array.isArray(data.modes) ? data.modes : [];
      renderRebootMenu(state.rebootModes, '');
    } catch (error) {
      renderRebootMenu([], `无法读取重启能力：${errorMessage(error)}`);
    }
  }

  async function requestReboot(mode, button) {
    const entry = state.rebootModes.find((item) => String(item.id) === String(mode));
    if (entry && entry.supported === false) return;
    if (state.rebootPending) return;
    const label = REBOOT_LABELS[mode] || (entry && entry.label) || mode;
    // 与原生一致：只有越狱模式下的普通重启会先确认（其余模式在菜单里点即执行）
    if (mode === 'system' && state.lateLoad && !window.confirm('当前处于越狱模式，重启后将失去 Root 权限，开机后需要重新越狱。确定要重启吗？')) return;
    state.rebootPending = true;
    setBusy(button, true);
    try {
      await api('/api/reboot', { method: 'POST', body: JSON.stringify({ mode }), timeout: 10000 });
      state.rebootPending = false;
      closeRebootMenu();
      notify(`已发送“${label}”请求`, false, 5000);
    } catch (error) {
      state.rebootPending = false;
      notify(`重启请求失败：${errorMessage(error)}`, true, 5000);
      setBusy(button, false);
    }
  }

  async function toggleModule(input) {
    const moduleId = decodeURIComponent(input.dataset.moduleToggle || '');
    if (!moduleId) return;
    const enable = input.checked;
    const module = state.modules.find((item) => String(item.id) === moduleId);
    const label = module && (module.name || module.id) || moduleId;
    input.disabled = true;
    try {
      await api(`/api/modules/${encodeURIComponent(moduleId)}/${enable ? 'enable' : 'disable'}`, { method: 'POST', timeout: 12000 });
      notify(`${label} 已${enable ? '启用' : '停用'}`);
      await loadModules();
    } catch (error) {
      input.checked = !enable;
      notify(`模块状态更新失败：${errorMessage(error)}`, true, 5000);
    } finally {
      input.disabled = false;
    }
  }

  async function runModuleAction(button) {
    const moduleId = decodeURIComponent(button.dataset.module || '');
    const action = button.dataset.action;
    const module = state.modules.find((item) => String(item.id) === moduleId);
    const label = module && (module.name || module.id) || moduleId;
    if (action === 'uninstall' && !window.confirm(`确定将“${label}”标记为下次重启时卸载吗？`)) return;
    if (action === 'action' && !window.confirm(`执行“${label}”的操作脚本？`)) return;
    setBusy(button, true);
    try {
      await api(`/api/modules/${encodeURIComponent(moduleId)}/${action}`, { method: 'POST', timeout: action === 'action' ? 30000 : 12000 });
      notify(action === 'undo-uninstall' ? '已撤销卸载' : action === 'action' ? '操作脚本执行完成' : '模块状态已更新');
      await loadModules();
    } catch (error) {
      notify(`模块操作失败：${errorMessage(error)}`, true, 5000);
    } finally {
      setBusy(button, false);
    }
  }

  async function changeSuperuser(input) {
    const uid = input.dataset.uid;
    const packageName = input.dataset.package;
    const label = input.dataset.label || packageName;
    const grant = input.checked;
    if (!grant && !window.confirm(`关闭“${label}”的 Root 权限？`)) {
      input.checked = true;
      return;
    }
    input.disabled = true;
    try {
      await api(`/api/superuser/${encodeURIComponent(uid)}/${grant ? 'grant' : 'revoke'}`, {
        method: 'POST', body: JSON.stringify({ packageName }),
      });
      notify(grant ? '已授予 Root 权限' : '已撤销 Root 权限');
      await loadSuperusers();
    } catch (error) {
      input.checked = !grant;
      notify(`授权更新失败：${errorMessage(error)}`, true, 5000);
    } finally {
      input.disabled = false;
    }
  }

  async function changeAutoStart(input) {
    const enabled = input.checked;
    input.disabled = true;
    try {
      const data = await api('/api/settings/auto-start', { method: 'POST', body: JSON.stringify({ enabled }) });
      state.settings = { ...(state.settings || {}), enabled: data.enabled };
      notify(data.enabled ? '已开启开机自动启动' : '已关闭开机自动启动');
      if (state.status && state.status.server) state.status.server.enabled = data.enabled;
      if (state.status) renderStatus(state.status);
    } catch (error) {
      input.checked = !enabled;
      notify(`设置保存失败：${errorMessage(error)}`, true, 5000);
    } finally {
      input.disabled = false;
    }
  }

  async function stopService(button) {
    if (!window.confirm('停止当前网页服务？\n\n当前页面会立即断开，但开机自动启动设置不会改变。')) return;
    setBusy(button, true);
    try {
      await api('/api/admin/stop', { method: 'POST' });
      setStatusPill('正在停止');
      notify('网页服务正在停止', false, 8000);
    } catch (error) {
      notify(`停止失败：${errorMessage(error)}`, true, 5000);
      setBusy(button, false);
    }
  }

  const linesOf = (value) => String(value || '').split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
  const linesText = (values) => Array.isArray(values) ? values.join('\n') : '';

  function showToolPanel(id, visible) {
    const panel = $(id);
    if (panel) panel.classList.toggle('hidden', !visible);
  }

  function toolMode() {
    return String(state.status && state.status.kernel && state.status.kernel.mode || '').toLowerCase();
  }

  function renderKpm(data) {
    state.tools.kpm = data;
    const caps = data.caps || {};
    const backend = String(caps.backend || 'unsupported');
    const backendLabel = backend === 'native-gki' ? 'GKI 原生加载器' : backend === 'kpatch-next' ? 'KPatch-Next' : '不可用';
    const policy = $('kpm-policy');
    policy.checked = Boolean(caps.policyEnabled);
    policy.disabled = Boolean(caps.lateLoad) || backend === 'unsupported';
    $('kpm-import').disabled = Boolean(caps.lateLoad) || backend === 'unsupported' || caps.managementAvailable === false;
    const loaded = caps.loadedCount == null ? '运行状态未知' : `已加载 ${caps.loadedCount} 个`;
    $('kpm-detail').textContent = `${backendLabel} · ${loaded}${caps.disabledReason ? ` · ${caps.disabledReason}` : ''}`;
    const modules = Array.isArray(data.modules) ? data.modules : [];
    if (!modules.length) {
      $('kpm-list').innerHTML = data.listError
        ? errorState('KPM 列表读取失败', data.listError, 'tools')
        : '<div class="state"><b>还没有导入 KPM</b>选择 KPM 文件后可在这里管理启用和运行状态。</div>';
      return;
    }
    $('kpm-list').innerHTML = modules.map((module) => {
      if (module.error) return `<div class="state error"><b>${esc(module.id || 'KPM')}</b>${esc(module.error)}</div>`;
      const id = String(module.id || '');
      const encoded = encodeURIComponent(id);
      const enabled = Boolean(module.enabled);
      const loadedNow = module.loaded === true;
      const known = module.runtimeKnown !== false && module.loaded != null;
      const badges = [
        `<span class="badge ${enabled ? 'ok' : ''}">${enabled ? '已启用' : '已停用'}</span>`,
        `<span class="badge ${loadedNow ? 'accent' : known ? '' : 'warn'}">${loadedNow ? '运行中' : known ? '未加载' : '状态未知'}</span>`,
        module.quarantined ? '<span class="badge warn">已隔离</span>' : '',
      ].join('');
      return `<article class="card kpm-item"><div class="kpm-head"><div class="kpm-copy"><div class="kpm-name">${esc(module.name || id)}</div><div class="kpm-meta">${esc(id)}${module.version ? ` · ${esc(module.version)}` : ''}</div></div><div class="badges">${badges}</div></div>${module.description ? `<p class="kpm-description">${esc(module.description)}</p>` : ''}${module.quarantineReason ? `<div class="notice warn"><b>隔离原因</b><span>${esc(module.quarantineReason)}</span></div>` : ''}<div class="kpm-actions"><button class="btn" type="button" data-kpm-id="${esc(encoded)}" data-kpm-action="${enabled ? 'disable' : 'enable'}">${enabled ? '停用' : '启用'}</button>${enabled && known ? `<button class="btn" type="button" data-kpm-id="${esc(encoded)}" data-kpm-action="${loadedNow ? 'unload' : 'load'}">${loadedNow ? '卸载运行实例' : '立即加载'}</button>` : ''}<button class="btn" type="button" data-kpm-id="${esc(encoded)}" data-kpm-action="control">控制命令</button><button class="btn danger" type="button" data-kpm-id="${esc(encoded)}" data-kpm-action="remove">移除</button></div></article>`;
    }).join('');
  }

  async function loadKpm() {
    $('kpm-list').innerHTML = '<div class="state"><b>正在读取 KPM</b>请稍候……</div>';
    try {
      const data = await api('/api/kpm', { timeout: 20000 });
      renderKpm(data);
      return data;
    } catch (error) {
      state.tools.kpm = null;
      $('kpm-policy').disabled = true;
      $('kpm-detail').textContent = `不可用 · ${errorMessage(error)}`;
      $('kpm-list').innerHTML = errorState('无法读取 KPM', errorMessage(error), 'tools');
      throw error;
    }
  }

  function renderSusfs(data) {
    state.tools.susfs = data;
    const config = data.config || {};
    const runtime = data.runtime || {};
    $('susfs-detail').textContent = `${data.version || '版本未知'} · ${Array.isArray(data.features) ? data.features.length : 0} 项内核能力${data.error ? ` · ${data.error}` : ''}`;
    $('susfs-state').textContent = runtime.requiresReboot ? '需要重启' : runtime.state || (data.available ? '可用' : '不可用');
    $('susfs-state').className = `badge ${data.available && !runtime.failedCount ? 'ok' : 'warn'}`;
    $('susfs-enabled').checked = Boolean(config.enabled);
    $('susfs-logging').checked = Boolean(config.logging);
    $('susfs-avc').checked = Boolean(config.avcLogSpoofing);
    $('susfs-hide-mounts').checked = Boolean(config.hideSusMounts);
    $('susfs-paths').value = linesText(config.paths);
    $('susfs-loop-paths').value = linesText(config.loopPaths);
    $('susfs-maps').value = linesText(config.susMaps);
    $('susfs-redirects').value = (config.openRedirects || []).map((item) => `${item.original || ''}|${item.redirected || ''}|${item.uidScheme == null ? 3 : item.uidScheme}`).join('\n');
    $('susfs-kstats').value = (config.kstatEntries || []).map((item) => Array.isArray(item) ? item.join('|') : '').filter(Boolean).join('\n');
    $('susfs-uname-release').value = config.unameRelease || '';
    $('susfs-uname-version').value = config.unameVersion || '';
    $('susfs-cmdline').value = config.cmdlineOrBootconfig || '';
    $('susfs-save').disabled = !data.available;
  }

  async function loadSusfs() {
    $('susfs-state').textContent = '读取中';
    try {
      const data = await api('/api/susfs', { timeout: 16000 });
      renderSusfs(data);
      return data;
    } catch (error) {
      state.tools.susfs = null;
      $('susfs-state').textContent = '不可用';
      $('susfs-state').className = 'badge warn';
      $('susfs-detail').textContent = errorMessage(error);
      $('susfs-save').disabled = true;
      throw error;
    }
  }

  function renderPathmask(data) {
    state.tools.pathmask = data;
    const status = data.status || {};
    $('pathmask-state').textContent = status.requiresReboot ? '需要重启' : status.phase || (status.loaded ? '已加载' : '未加载');
    $('pathmask-state').className = `badge ${status.loaded && !status.lastErrorCode ? 'ok' : status.lastErrorCode ? 'warn' : ''}`;
    $('pathmask-detail').textContent = `${status.savedCount || 0} 条路径 · ${status.activeCount || 0} 条生效${status.lastErrorMessage ? ` · ${status.lastErrorMessage}` : ''}`;
    $('pathmask-paths').value = linesText(status.targetPaths);
    $('pathmask-apps').value = linesText(status.appPackages);
    $('pathmask-app-scope').checked = status.useAppScope !== false;
    $('pathmask-dirents').checked = status.hideDirents !== false;
    $('pathmask-isolated').checked = status.hideIsolated !== false;
    $('pathmask-auto-load').checked = status.autoLoadEnabled !== false;
    $('pathmask-delay').value = Number(status.autoLoadDelaySeconds || 0);
  }

  async function loadPathmask() {
    $('pathmask-state').textContent = '读取中';
    try {
      const data = await api('/api/pathmask', { timeout: 16000 });
      renderPathmask(data);
      return data;
    } catch (error) {
      state.tools.pathmask = null;
      $('pathmask-state').textContent = '不可用';
      $('pathmask-state').className = 'badge warn';
      $('pathmask-detail').textContent = errorMessage(error);
      throw error;
    }
  }

  function renderBuiltinMount(data) {
    state.tools.builtinMount = data;
    const status = data.status || {};
    $('builtin-mount-enabled').disabled = false;
    $('builtin-mount-mode').disabled = false;
    $('builtin-mount-variant').disabled = false;
    $('builtin-mount-enabled').checked = Boolean(status.enabled);
    $('builtin-mount-mode').value = status.defaultMode || 'overlay';
    $('builtin-mount-variant').value = status.variant || 'lite';
    const compatibility = status.compatibility === 'unsupported' ? '当前 KMI 不兼容' : status.compatibility === 'compatible' ? 'KMI 兼容' : status.compatibility === 'not_required' ? '无需 KMI 匹配' : 'KMI 状态未知';
    $('builtin-mount-detail').textContent = `${status.moduleName || 'Hybrid Mount'} ${status.version || ''}`.trim();
    $('builtin-mount-status').innerHTML = `${status.conflict ? `<span class="badge warn">与 ${esc(status.conflict)} 冲突</span>` : ''}<span class="badge ${status.enabled ? 'ok' : ''}">${status.installed ? status.enabled ? '已启用' : '已安装但停用' : '未安装'}</span> <span class="badge ${status.compatibility === 'unsupported' ? 'warn' : ''}">${esc(compatibility)}</span><div class="row-detail">切换内置版本或启停后通常需要重启才能完全生效。</div>`;
  }

  async function loadBuiltinMount() {
    try {
      const data = await api('/api/builtin-mount', { timeout: 20000 });
      renderBuiltinMount(data);
      return data;
    } catch (error) {
      state.tools.builtinMount = null;
      $('builtin-mount-enabled').disabled = true;
      $('builtin-mount-status').textContent = `无法读取：${errorMessage(error)}`;
      throw error;
    }
  }

  function renderKpatchNext(data) {
    state.tools.kpatchNext = data;
    const status = data.status || {};
    $('kpatch-next-enabled').disabled = false;
    $('kpatch-next-enabled').checked = Boolean(status.enabled);
    $('kpatch-next-detail').textContent = `${status.moduleName || 'KPatch-Next'} ${status.version || ''}`.trim();
    const states = [status.installed ? '已安装' : '未安装', status.enabled ? '已启用' : '已停用'];
    if (status.pendingUpdate) states.push('更新待重启');
    if (status.pendingRemove) states.push('卸载待重启');
    if (status.unresolved) states.push('存在未解析状态');
    $('kpatch-next-status').innerHTML = `<div class="badges">${states.map((item, index) => `<span class="badge ${index === 1 && status.enabled ? 'ok' : item.includes('待重启') || item.includes('未解析') ? 'warn' : ''}">${esc(item)}</span>`).join('')}</div><div class="row-detail">KPM 的 LKM 后端；安装、更新或停用后可能需要重启。</div>`;
  }

  async function loadKpatchNext() {
    try {
      const data = await api('/api/kpatch-next', { timeout: 20000 });
      renderKpatchNext(data);
      return data;
    } catch (error) {
      state.tools.kpatchNext = null;
      $('kpatch-next-enabled').disabled = true;
      $('kpatch-next-status').textContent = `无法读取：${errorMessage(error)}`;
      throw error;
    }
  }

  async function loadTools() {
    const mode = toolMode();
    const lateLoad = mode === 'late-load';
    const gki = mode === 'gki';
    const lkm = mode === 'lkm';
    $('tools-summary').textContent = lateLoad ? 'Late-load 模式 · 内核扩展管理不可用' : gki ? 'GKI 模式 · 原生 KPM、SUSFS 与内置挂载' : lkm ? 'LKM 模式 · KPatch-Next、PathMask 与内置挂载' : '内核模式未知';
    $('tools-runtime').innerHTML = `<b>${gki ? 'GKI' : lkm ? 'LKM' : lateLoad ? 'Late-load' : '未知模式'}</b><span>${lateLoad ? '当前加载方式不支持持久内核扩展。' : '页面只显示当前模式支持的真实能力；所有更改均由 ksud 执行。'}</span>`;
    $('tools-runtime').className = `notice${lateLoad || (!gki && !lkm) ? ' warn' : ''}`;
    showToolPanel('kpm-panel', gki || lkm);
    showToolPanel('susfs-panel', gki);
    showToolPanel('pathmask-panel', lkm);
    showToolPanel('builtin-mount-panel', gki || lkm);
    showToolPanel('kpatch-next-panel', lkm);
    const jobs = [];
    if (gki || lkm) jobs.push(loadKpm());
    if (gki) jobs.push(loadSusfs(), loadBuiltinMount());
    if (lkm) jobs.push(loadPathmask(), loadBuiltinMount(), loadKpatchNext());
    const results = await Promise.allSettled(jobs);
    const failures = results.filter((result) => result.status === 'rejected').length;
    if (failures && jobs.length && failures === jobs.length) throw new Error('当前模式的内核工具均无法读取');
    return results;
  }

  async function openSusfsManagement() {
    if (toolMode() !== 'gki') {
      notify('SUSFS 管理仅在 GKI 模式下可用', true);
      return;
    }
    setView('tools');
    try {
      await loadTools();
      const panel = $('susfs-panel');
      if (!panel || panel.classList.contains('hidden')) throw new Error('当前设备没有可用的 SUSFS 管理接口');
      panel.focus({ preventScroll: true });
      panel.scrollIntoView({
        block: 'start',
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      });
    } catch (error) {
      notify(`无法打开 SUSFS 管理：${errorMessage(error)}`, true, 6000);
    }
  }

  async function postTool(path, payload, button, success) {
    setBusy(button, true);
    try {
      await api(path, { method: 'POST', body: JSON.stringify(payload), timeout: 45000 });
      notify(success);
    } catch (error) {
      notify(`${success.replace(/^已/, '')}失败：${errorMessage(error)}`, true, 6000);
    } finally {
      setBusy(button, false);
    }
    await loadTools().catch(() => {});
  }

  async function importKpm(button) {
    const file = $('kpm-file').files && $('kpm-file').files[0];
    if (!file) { notify('请先选择 KPM 文件', true); return; }
    if (file.size > 4 * 1024 * 1024) { notify('KPM 文件不能超过 4 MiB', true); return; }
    setBusy(button, true);
    try {
      await api('/api/kpm/import', {
        method: 'POST',
        body: await file.arrayBuffer(),
        timeout: 60000,
        headers: {
          'Content-Type': 'application/octet-stream',
          'X-ApkeSU-Kpm-Args': $('kpm-args').value,
          'X-ApkeSU-Kpm-Enable': $('kpm-import-enable').checked ? '1' : '0',
        },
      });
      $('kpm-file').value = '';
      notify('KPM 已导入');
      await loadTools();
    } catch (error) {
      notify(`KPM 导入失败：${errorMessage(error)}`, true, 7000);
    } finally {
      setBusy(button, false);
    }
  }

  async function runKpmAction(button) {
    const id = decodeURIComponent(button.dataset.kpmId || '');
    const action = button.dataset.kpmAction || '';
    if (action === 'remove' && !window.confirm(`确定移除 KPM“${id}”？`)) return;
    if (action === 'control') {
      openDialog('KPM 控制命令', `<label class="form-field"><span>${esc(id)} 参数</span><input id="kpm-control-args" type="text" maxlength="4096" autocomplete="off" autofocus placeholder="留空也可执行"></label>`, `<button class="btn" type="button" data-close-dialog>取消</button><button class="btn primary" type="button" data-kpm-control="${esc(encodeURIComponent(id))}">执行</button>`);
      return;
    }
    setBusy(button, true);
    try {
      await api(`/api/kpm/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: JSON.stringify({}), timeout: 45000 });
      notify('KPM 状态已更新');
      await loadTools();
    } catch (error) {
      notify(`KPM 操作失败：${errorMessage(error)}`, true, 6000);
    } finally {
      setBusy(button, false);
    }
  }

  async function runKpmControl(button) {
    const id = decodeURIComponent(button.dataset.kpmControl || '');
    setBusy(button, true);
    try {
      await api(`/api/kpm/${encodeURIComponent(id)}/control`, { method: 'POST', body: JSON.stringify({ args: $('kpm-control-args').value }), timeout: 45000 });
      closeDialog();
      notify('KPM 控制命令已执行');
      await loadTools();
    } catch (error) {
      notify(`KPM 控制失败：${errorMessage(error)}`, true, 6000);
      setBusy(button, false);
    }
  }

  function susfsPayload() {
    return {
      enabled: $('susfs-enabled').checked,
      logging: $('susfs-logging').checked,
      avcLogSpoofing: $('susfs-avc').checked,
      hideSusMounts: $('susfs-hide-mounts').checked,
      paths: linesOf($('susfs-paths').value),
      loopPaths: linesOf($('susfs-loop-paths').value),
      susMaps: linesOf($('susfs-maps').value),
      openRedirects: linesOf($('susfs-redirects').value).map((line) => {
        const [original = '', redirected = '', uidScheme = '3'] = line.split('|');
        return { original: original.trim(), redirected: redirected.trim(), uidScheme: uidScheme.trim() };
      }),
      kstatEntries: linesOf($('susfs-kstats').value).map((line) => line.split('|').map((item) => item.trim())),
      unameRelease: $('susfs-uname-release').value.trim(),
      unameVersion: $('susfs-uname-version').value.trim(),
      cmdlineOrBootconfig: $('susfs-cmdline').value.trim(),
    };
  }

  function pathmaskPayload() {
    return {
      targetPaths: linesOf($('pathmask-paths').value),
      appPackages: linesOf($('pathmask-apps').value),
      useAppScope: $('pathmask-app-scope').checked,
      hideDirents: $('pathmask-dirents').checked,
      hideIsolated: $('pathmask-isolated').checked,
      autoLoadEnabled: $('pathmask-auto-load').checked,
      autoLoadDelaySeconds: Number($('pathmask-delay').value || 0),
    };
  }

  async function refreshAll(showToast = false) {
    const results = await Promise.allSettled([
      loadStatus(),
      loadModules(),
      loadSuperusers(),
      loadSettings(),
      loadFeatures(),
      loadApiMeta(),
    ]);
    const toolsResult = await Promise.allSettled([loadTools()]);
    results.push(...toolsResult);
    const failures = results.filter((result) => result.status === 'rejected').length;
    if (showToast) notify(failures ? `${failures} 项刷新失败，请查看对应页面` : '数据已刷新', failures > 0);
  }

  function retry(kind) {
    if (kind === 'status') return loadStatus();
    if (kind === 'modules') return loadModules();
    if (kind === 'superuser') return loadSuperusers();
    if (kind === 'settings') return loadSettings();
    if (kind === 'features') return loadFeatures();
    if (kind === 'tools') return loadTools();
    if (kind === 'reboot') return openRebootMenu();
    return refreshAll();
  }

  async function refreshHome() {
    await Promise.allSettled([loadStatus(), loadModules(), loadSuperusers()]);
  }

  async function copyInfoValue(key) {
    const value = state.infoValues[key];
    if (!value || value === '-') return;
    try {
      await navigator.clipboard.writeText(value);
      notify(`已复制${key}`, false, 1800);
    } catch (_) {
      notify('复制失败，请手动选择文本', true);
    }
  }

  function toggleInfoRow(key) {
    state.expandedInfoRows[key] = !state.expandedInfoRows[key];
    if (state.status) renderInfoRows(state.status, kernelStateOf(state.status));
  }

  document.addEventListener('click', (event) => {
    const viewButton = event.target.closest('[data-view]');
    if (viewButton) { setView(viewButton.dataset.view); return; }
    const goButton = event.target.closest('[data-go]');
    if (goButton) { setView(goButton.dataset.go); return; }
    if (event.target.closest('#susfs-shortcut-button')) { openSusfsManagement(); return; }
    if (event.target.closest('#reboot-button')) { openRebootMenu(); return; }
    if (event.target.closest('[data-open-reboot]')) { openRebootMenu(); return; }
    const assetPick = event.target.closest('[data-asset-pick]');
    if (assetPick) { startAssetPick(assetPick.dataset.assetPick); return; }
    const assetAdjust = event.target.closest('[data-asset-adjust]');
    if (assetAdjust) { openAssetAdjustment(assetAdjust.dataset.assetAdjust); return; }
    const assetClear = event.target.closest('[data-asset-clear]');
    if (assetClear) { clearAsset(assetClear.dataset.assetClear); return; }
    if (event.target.closest('[data-close-dialog]') || event.target === $('dialog') || event.target.closest('#dialog-close')) { closeDialog(); return; }
    const rebootButton = event.target.closest('[data-reboot]');
    if (rebootButton) { requestReboot(rebootButton.dataset.reboot, rebootButton); return; }
    const retryButton = event.target.closest('[data-retry]');
    if (retryButton) {
      setBusy(retryButton, true);
      Promise.resolve(retry(retryButton.dataset.retry)).catch(() => {}).finally(() => setBusy(retryButton, false));
      return;
    }
    const webuiButton = event.target.closest('[data-webui]');
    if (webuiButton) {
      notify('为防止模块网页窃取管理签名，请在 ApkeSU 软件管理器内打开模块 WebUI', true, 6000);
      return;
    }
    const moduleButton = event.target.closest('[data-module]');
    if (moduleButton) { runModuleAction(moduleButton); return; }
    const kpmButton = event.target.closest('[data-kpm-id]');
    if (kpmButton) { runKpmAction(kpmButton); return; }
    const kpmControlButton = event.target.closest('[data-kpm-control]');
    if (kpmControlButton) { runKpmControl(kpmControlButton); return; }
    const copyButton = event.target.closest('[data-info-copy]');
    if (copyButton) { copyInfoValue(copyButton.dataset.infoCopy); return; }
    const expandButton = event.target.closest('[data-info-expand]');
    if (expandButton) { toggleInfoRow(expandButton.dataset.infoExpand); return; }
    if (event.target.closest('#home-warning-toggle')) {
      state.warningExpanded = !state.warningExpanded;
      if (state.status) renderWarning(homeWarningMessages(state.status));
      return;
    }
    if (event.target.closest('#lkm-card')) { refreshHome(); return; }
    if (!event.target.closest('#reboot-menu')) closeRebootMenu();
  });

  document.addEventListener('change', (event) => {
    if (event.target.matches('[data-uid]')) { changeSuperuser(event.target); }
    else if (event.target.matches('[data-module-toggle]')) { toggleModule(event.target); }
    else if (event.target.matches('[data-feature]')) { changeFeature(event.target); }
    else if (event.target.id === 'stealth-toggle') { changeStealth(event.target); }
    else if (event.target.matches('[data-manager-setting]')) {
      const input = event.target;
      const value = input.tagName === 'SELECT' ? input.value : input.checked;
      updateManagerSetting(input.dataset.managerSetting, value, input).catch(() => {});
    }
    else if (event.target.id === 'module-filter') { state.moduleFilter = event.target.value; renderModules(); }
    else if (event.target.id === 'superuser-filter') { state.appFilter = event.target.value; renderSuperusers(); }
    else if (event.target.id === 'theme-select') { applyTheme(event.target.value); }
    else if (event.target.id === 'auto-start') { changeAutoStart(event.target); }
    else if (event.target.id === 'kpm-policy') { postTool('/api/kpm/policy', { enabled: event.target.checked }, event.target, 'KPM 策略已更新'); }
    else if (event.target.id === 'builtin-mount-enabled') { postTool('/api/builtin-mount', { action: event.target.checked ? 'enable' : 'disable' }, event.target, '内置挂载状态已更新'); }
    else if (event.target.id === 'builtin-mount-mode') { postTool('/api/builtin-mount', { action: 'mode', value: event.target.value }, event.target, '默认挂载模式已更新'); }
    else if (event.target.id === 'builtin-mount-variant') { postTool('/api/builtin-mount', { action: 'variant', value: event.target.value }, event.target, '内置挂载版本已更新'); }
    else if (event.target.id === 'kpatch-next-enabled') { postTool('/api/kpatch-next', { action: event.target.checked ? 'enable' : 'disable' }, event.target, 'KPatch-Next 状态已更新'); }
  });

  $('module-search').addEventListener('input', (event) => { state.moduleQuery = event.target.value; renderModules(); });
  $('superuser-search').addEventListener('input', (event) => { state.appQuery = event.target.value; renderSuperusers(); });
  $('refresh-all').addEventListener('click', (event) => {
    setBusy(event.currentTarget, true);
    refreshAll(true).finally(() => setBusy(event.currentTarget, false));
  });
  $('theme-toggle').addEventListener('click', toggleTheme);
  $('asset-file-input').addEventListener('change', (event) => uploadAsset(event.target.files && event.target.files[0]));
  $('feature-refresh').addEventListener('click', (event) => {
    setBusy(event.currentTarget, true);
    loadFeatures().catch(() => {}).finally(() => setBusy(event.currentTarget, false));
  });
  $('install-pwa-settings').addEventListener('click', installPwa);
  $('auth-retry').addEventListener('click', () => {
    showAuthGate('正在重新验证', '正在检查浏览器签名密钥。');
    initializeAuthentication().then((ready) => {
      if (ready) refreshAll(false);
    });
  });
  $('stealth-code-edit').addEventListener('click', () => openStealthDialog(false));
  $('manager-home-title-edit').addEventListener('click', openManagerHomeTitleDialog);
  $('dynamic-manager-open').addEventListener('click', openDynamicManagerDialog);
  $('stop-service').addEventListener('click', (event) => stopService(event.currentTarget));
  $('kpm-import').addEventListener('click', (event) => importKpm(event.currentTarget));
  $('susfs-save').addEventListener('click', (event) => postTool('/api/susfs', { config: susfsPayload() }, event.currentTarget, 'SUSFS 配置已保存并应用'));
  $('pathmask-save').addEventListener('click', (event) => postTool('/api/pathmask', { action: 'apply', config: pathmaskPayload() }, event.currentTarget, 'PathMask 配置已保存并应用'));
  $('pathmask-unload').addEventListener('click', (event) => {
    if (window.confirm('卸载当前 PathMask 模块并清除本次开机的隐藏状态？')) postTool('/api/pathmask', { action: 'unload' }, event.currentTarget, 'PathMask 已卸载');
  });
  $('pathmask-delete').addEventListener('click', (event) => {
    if (window.confirm('确定删除 PathMask 的已保存配置、候选配置和回退配置？此操作不能撤销。')) postTool('/api/pathmask', { action: 'delete' }, event.currentTarget, 'PathMask 配置已删除');
  });
  document.addEventListener('keydown', (event) => {
    const dialog = $('dialog');
    if (event.key === 'Tab' && !dialog.classList.contains('hidden')) {
      const focusables = dialogFocusables();
      if (!focusables.length) {
        event.preventDefault();
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (!dialog.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
      return;
    }
    if (event.key === 'Escape') {
      closeDialog();
      closeRebootMenu();
    }
  });
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    state.pwaPrompt = event;
    updatePwaUi();
  });
  window.addEventListener('appinstalled', () => {
    state.pwaPrompt = null;
    updatePwaUi();
    enablePwaAutoStart().catch((error) => {
      notify(`PWA 已安装，但开机服务启用失败：${errorMessage(error)}`, true, 6000);
    });
  });
  window.addEventListener('online', () => {
    notify('已重新连接 ksud');
    refreshAll(false);
  });
  window.addEventListener('offline', () => setStatusPill('ksud 已断开', 'err'));

  const systemColorScheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
  const syncAutomaticTheme = () => {
    if (storedTheme() === 'auto') syncThemeChrome('auto');
  };
  if (systemColorScheme) {
    if (systemColorScheme.addEventListener) systemColorScheme.addEventListener('change', syncAutomaticTheme);
    else if (systemColorScheme.addListener) systemColorScheme.addListener(syncAutomaticTheme);
  }

  applyTheme(storedTheme());
  registerPwa();
  const initialView = (location.hash || '').slice(1);
  const openSusfsOnLoad = initialView === 'susfs';
  setView(openSusfsOnLoad ? 'tools' : (initialView || 'home'), false);
  initializeAuthentication().then((ready) => {
    if (!ready) return;
    refreshAll(false).then(() => {
      if (openSusfsOnLoad) openSusfsManagement();
    });
  });
})();
