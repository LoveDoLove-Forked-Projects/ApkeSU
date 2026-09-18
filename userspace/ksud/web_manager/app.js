(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const state = { modules: [], apps: [], status: null };
  const api = async (path, options = {}) => {
    const response = await fetch(path, { ...options, headers: { 'Accept': 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) } });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error?.message || `请求失败 (${response.status})`);
    return data;
  };
  const toast = (message) => { $('toast').textContent = message; $('toast').classList.add('show'); setTimeout(() => $('toast').classList.remove('show'), 2600); };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[char]));
  const busy = (button, value) => { if (button) { button.disabled = value; button.style.opacity = value ? '.55' : ''; } };
  async function loadStatus() {
    const data = await api('/api/status'); state.status = data;
    const kernel = data.kernel || {}; const device = data.device || {};
    $('status-grid').innerHTML = [
      ['内核版本', kernel.version || '未连接'], ['运行模式', kernel.mode || '未知'], ['UAPI', kernel.uapiVersion || '未知'], ['Native KPM', kernel.nativeKpm ? '已启用' : '未启用']
    ].map(([label, value]) => `<div class="stat"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`).join('');
    $('device-card').innerHTML = `<h3>${esc(device.brand || '')} ${esc(device.model || '设备')}</h3><p>Android ${esc(device.android || '未知')} · ${esc(device.device || '未知')} · 内核 ${esc(kernel.release || '未知')}</p><p>ksud ${esc(data.userspace?.versionName || '未知')} · ${data.server?.persistent ? '持久网页服务已接管' : '网页服务未持久化'}</p>`;
  }
  async function loadModules() {
    const data = await api('/api/modules'); state.modules = data.modules || [];
    $('module-list').innerHTML = state.modules.length ? state.modules.map((module) => {
      const id = encodeURIComponent(module.id || ''); const enabled = String(module.enabled) === 'true';
      return `<div class="list-row"><div class="row-main"><strong>${esc(module.name || module.id)}</strong><span class="muted">${esc(module.id)} · ${enabled ? '已启用' : '已停用'}${module.version ? ` · ${esc(module.version)}` : ''}</span></div><div class="row-actions"><button data-module="${id}" data-action="${enabled ? 'disable' : 'enable'}" class="${enabled ? 'secondary' : 'primary'}">${enabled ? '停用' : '启用'}</button>${module.action === 'true' ? `<button data-module="${id}" data-action="action">执行</button>` : ''}<button data-module="${id}" data-action="uninstall" class="danger">卸载</button></div></div>`;
    }).join('') : '<div class="empty">暂未发现模块</div>';
  }
  async function loadSuperuser() {
    const data = await api('/api/superuser'); state.apps = data.apps || [];
    $('su-list').innerHTML = state.apps.length ? state.apps.map((app) => `<div class="list-row"><div class="row-main"><strong>${esc(app.packageName)}</strong><span class="muted">UID ${esc(app.uid)}</span></div><button data-uid="${esc(app.uid)}" data-package="${esc(app.packageName)}" data-grant="${app.granted ? 'false' : 'true'}" class="${app.granted ? 'danger' : 'primary'}">${app.granted ? '撤销授权' : '授权 Root'}</button></div>`).join('') : '<div class="empty">暂未发现可管理应用</div>';
  }
  async function loadSettings() {
    const data = await api('/api/settings'); $('settings-card').innerHTML = `<h3>服务状态</h3><p>监听地址：${esc(data.bindAddress)}:${esc(data.port)}</p><p>认证方式：${esc(data.authentication)} · 配置：${esc(data.configPath)}</p><button id="toggle-auto" class="switch ${data.enabled ? 'on' : ''}">${data.enabled ? '开机自动启动：开' : '开机自动启动：关'}</button>`;
    $('toggle-auto').onclick = async () => { const button = $('toggle-auto'); busy(button, true); try { const result = await api('/api/settings/auto-start', { method:'POST', body: JSON.stringify({ enabled: !data.enabled }) }); data.enabled = result.enabled; await loadSettings(); toast('设置已保存'); } catch (error) { toast(error.message); } finally { busy(button, false); } };
  }
  async function refreshAll() { try { await loadStatus(); await Promise.all([loadModules(), loadSuperuser(), loadSettings()]); } catch (error) { toast(error.message); } }
  document.querySelectorAll('.bottom-nav button').forEach((button) => button.onclick = () => { document.querySelectorAll('.bottom-nav button').forEach((item) => item.classList.toggle('selected', item === button)); document.querySelectorAll('.page').forEach((page) => page.classList.toggle('active', page.id === button.dataset.page)); $('page-title').textContent = button.textContent; });
  $('refresh').onclick = refreshAll; $('modules-refresh').onclick = () => loadModules().catch((e) => toast(e.message)); $('su-refresh').onclick = () => loadSuperuser().catch((e) => toast(e.message));
  $('module-list').onclick = async (event) => { const button = event.target.closest('button[data-module]'); if (!button) return; if (button.dataset.action === 'uninstall' && !confirm('确定标记这个模块在下次重启时卸载吗？')) return; busy(button, true); try { await api(`/api/modules/${button.dataset.module}/${button.dataset.action}`, { method:'POST' }); toast('模块操作已完成'); await loadModules(); } catch (error) { toast(error.message); } finally { busy(button, false); } };
  $('su-list').onclick = async (event) => { const button = event.target.closest('button[data-uid]'); if (!button) return; busy(button, true); try { await api(`/api/superuser/${button.dataset.uid}/${button.dataset.grant === 'true' ? 'grant' : 'revoke'}`, { method:'POST', body: JSON.stringify({ packageName: button.dataset.package }) }); toast('授权状态已更新'); await loadSuperuser(); } catch (error) { toast(error.message); } finally { busy(button, false); } };
  refreshAll();
})();
