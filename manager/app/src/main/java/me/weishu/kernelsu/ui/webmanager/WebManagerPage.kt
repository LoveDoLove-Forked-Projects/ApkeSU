package me.weishu.kernelsu.ui.webmanager

/**
 * 页面骨架与样式。注意：单个字符串字面量不能超过 JVM 的 65535 字节 UTF-8 上限，
 * 页面因此拆成 HEAD/MARKUP/SCRIPT_HEAD/SCRIPT_TAIL 四段常量，由 WEB_MANAGER_PAGE 运行时拼接；
 * 增删内容时保持每段各自低于上限。
 */
private const val WEB_MANAGER_PAGE_HEAD: String = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#f5f6f8">
<meta name="color-scheme" content="light dark">
<title>ApkeSU（web）</title>
<style>
:root{color-scheme:light dark;--bg:#f5f6f8;--card:#fff;--card-2:#f0f2f5;--ink:#161a1f;--muted:#6b7480;--line:#e3e6ea;--accent:#2f6df6;--accent-ink:#fff;--accent-soft:#e8efff;--ok:#12855a;--ok-soft:#e4f6ec;--warn:#b26a00;--warn-soft:#fff3e0;--danger:#c0392b;--danger-soft:#fdecea;--radius:14px}
@media(prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#101215;--card:#191c21;--card-2:#20242a;--ink:#e7eaee;--muted:#9aa4b0;--line:#2a2f36;--accent:#7aa2ff;--accent-ink:#0d1017;--accent-soft:#232f4d;--ok:#5bd39a;--ok-soft:#17352a;--warn:#f0b866;--warn-soft:#3a2e18;--danger:#ff8f80;--danger-soft:#3c2320}}
:root[data-theme=dark]{--bg:#101215;--card:#191c21;--card-2:#20242a;--ink:#e7eaee;--muted:#9aa4b0;--line:#2a2f36;--accent:#7aa2ff;--accent-ink:#0d1017;--accent-soft:#232f4d;--ok:#5bd39a;--ok-soft:#17352a;--warn:#f0b866;--warn-soft:#3a2e18;--danger:#ff8f80;--danger-soft:#3c2320}
:root[data-theme=dark]{color-scheme:dark}
:root[data-theme=light]{color-scheme:light}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{width:100%;max-width:100%;margin:0;padding:0;overflow-x:hidden}
body{background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI","Noto Sans SC",sans-serif;padding-bottom:calc(66px + env(safe-area-inset-bottom))}
.hidden{display:none!important}
button,input,select,textarea{font:inherit;color:inherit}
a{color:var(--accent)}
.wrap{max-width:1100px;margin:0 auto;padding:0 14px}
/* 顶栏 */
.topbar{position:sticky;top:0;z-index:20;background:color-mix(in srgb,var(--bg) 88%,transparent);backdrop-filter:blur(12px);border-bottom:1px solid var(--line)}
.topbar-inner{max-width:1100px;margin:0 auto;padding:11px max(14px,env(safe-area-inset-right)) 11px max(14px,env(safe-area-inset-left));display:flex;align-items:center;gap:10px;overflow:hidden}
.logo{width:34px;height:34px;flex:none;border-radius:10px;background:var(--accent);color:var(--accent-ink);display:grid;place-items:center;font-weight:800;font-size:17px}
.brand{min-width:0;flex:1 1 auto;overflow:hidden}
.brand b{display:block;font-size:16px;font-weight:700;line-height:1.2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.brand span{display:block;font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pill{display:inline-flex;align-items:center;gap:6px;padding:5px 10px;border-radius:999px;background:var(--card-2);color:var(--muted);font-size:12px;font-weight:600;white-space:nowrap;flex:none}
.pill:before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor;opacity:.7}
.pill.ok{background:var(--ok-soft);color:var(--ok)}
.pill.warn{background:var(--warn-soft);color:var(--warn)}
.pill.err{background:var(--danger-soft);color:var(--danger)}
.iconbtn{width:44px;height:44px;flex:none;border:1px solid var(--line);border-radius:10px;background:var(--card);display:grid;place-items:center;cursor:pointer;font-size:16px;line-height:1}
.iconbtn svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round}
.iconbtn:active{background:var(--card-2)}
.top-action{min-width:44px;min-height:44px;padding:0 10px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--accent);display:inline-flex;align-items:center;justify-content:center;gap:6px;font-size:12px;font-weight:700;cursor:pointer;white-space:nowrap}
.top-action svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round}
.top-action:active{background:var(--accent-soft)}
body:not([data-current-view=home]) #susfsShortcut{display:none}
/* 桌面标签 / 移动底部导航 */
.tabs{display:none;gap:6px;max-width:1100px;margin:0 auto;padding:10px 14px 0}
.tab{padding:8px 14px;border:1px solid transparent;border-radius:999px;background:transparent;color:var(--muted);font-weight:600;cursor:pointer;display:inline-flex;align-items:center;gap:6px}
.tab.active{background:var(--card);border-color:var(--line);color:var(--ink)}
.tab-count{display:inline-block;min-width:18px;margin-left:5px;padding:0 5px;border-radius:999px;background:var(--card-2);color:var(--muted);font-size:11px;font-weight:700;line-height:17px;text-align:center}
.navbar{position:fixed;left:0;right:0;bottom:0;z-index:30;display:grid;grid-template-columns:repeat(5,1fr);background:color-mix(in srgb,var(--bg) 92%,transparent);backdrop-filter:blur(12px);border-top:1px solid var(--line);padding-bottom:env(safe-area-inset-bottom)}
.navbar button{border:0;background:transparent;padding:9px 4px 10px;display:flex;flex-direction:column;align-items:center;gap:3px;color:var(--muted);font-size:11px;font-weight:600;cursor:pointer}
.navbar button.active{color:var(--accent)}
.navbar .ico{font-size:17px;line-height:1}
.view{padding:14px 0 8px}
h1{font-size:19px;margin:2px 0 3px}
h2{font-size:15px;margin:0}
.sub{margin:0 0 12px;color:var(--muted);font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;margin-bottom:12px}
/* 自定义壁纸 / 自定义导航栏图标（与原生卡片壁纸、导航图标同款） */
.card-bg-host{position:relative;overflow:hidden}
.card-bg-host>.bg-layer{position:absolute;inset:0;background-position:center;background-repeat:no-repeat;pointer-events:none;z-index:0}
.card-bg-host>.bg-dim{position:absolute;inset:0;background:#000;pointer-events:none;z-index:1}
.card-bg-host>*:not(.bg-layer):not(.bg-dim){position:relative;z-index:2}
.card.card-bg-host,.metric.card-bg-host,.lkm-card.card-bg-host{background:transparent}
.card-bg-host.light-text{color:#fff}
.card-bg-host.light-text .lkm-sub,.card-bg-host.light-text .metric-title,.card-bg-host.light-text .metric-sub,.card-bg-host.light-text .info-label{color:rgba(255,255,255,.84);opacity:1}
.card-bg-host.light-text .lkm-tag{background:rgba(255,255,255,.22);color:#fff}
.card-bg-host.light-text .lkm-tag.neutral{background:rgba(255,255,255,.16);color:rgba(255,255,255,.85)}
.card-bg-host.light-text .info-row{border-top-color:rgba(255,255,255,.22)}
.card-bg-host.light-text .info-row:first-child{border-top-color:transparent}
.card-bg-host.light-text .btn{background:rgba(255,255,255,.16);border-color:transparent;color:#fff}
.custom-icon .ico{position:relative;width:18px;height:18px;overflow:hidden;font-size:0;color:transparent}
.custom-icon .ico:before{content:"";position:absolute;inset:0;background-image:var(--nav-image);background-repeat:no-repeat;background-position:center;background-size:contain;transform:translate(var(--nav-offset-x,0),var(--nav-offset-y,0)) scale(var(--nav-scale,1));transform-origin:center}
/* 设置页分类（与原生设置的分类一致） */
details.cat{margin:0 0 16px}
details.cat>summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;padding:4px 2px 10px;font-size:13.5px;font-weight:700;color:var(--muted)}
details.cat>summary::-webkit-details-marker{display:none}
details.cat>summary::before{content:"▸";font-size:11px;line-height:1;transition:transform .15s}
details.cat[open]>summary::before{transform:rotate(90deg)}
details.cat>summary::after{content:"";flex:1 1 auto;height:1px;background:var(--line)}
/* 模块卡片壁纸（与原生模块页同款） */
.item.card-bg-host{padding:11px 12px;border-radius:var(--radius)}
.item.card-bg-host .item-title,.item.card-bg-host .name{color:#fff}
.item.card-bg-host .item-meta{color:rgba(255,255,255,.85)}
.item.card-bg-host .item-desc{color:rgba(255,255,255,.9)}
.item.card-bg-host .tag{background:rgba(255,255,255,.22);color:#fff}
.item.card-bg-host .img,.item.card-bg-host .img-fallback{border-color:rgba(255,255,255,.3)}
.tool-rows{margin-top:2px}
.tool-hint{font-size:12.5px;color:var(--muted);padding:0 14px 10px}
.tool-target{width:100%;box-sizing:border-box}
.asset-thumb{width:44px;height:28px;flex:none;object-fit:cover;border-radius:7px;border:1px solid var(--line);background:var(--card-2)}
.asset-thumb.empty{display:grid;place-items:center;font-size:11px;color:var(--muted)}
.asset-crop-preview{position:relative;overflow:hidden;margin-bottom:10px;border-radius:11px;background:var(--card-2)}
.asset-crop-preview.wallpaper{width:100%;aspect-ratio:16/7}
.asset-crop-preview.icon{width:112px;height:112px;margin-inline:auto}
.asset-crop-image,.asset-crop-dim,.asset-crop-frame{position:absolute;inset:0;pointer-events:none}
.asset-crop-image{background-position:center;background-repeat:no-repeat;background-size:cover}
.asset-crop-image.icon{background-size:contain;transform-origin:center}
.asset-crop-dim{z-index:1;background:#000}
.asset-crop-frame{z-index:2;border:1px solid color-mix(in srgb,var(--ink) 38%,transparent);border-radius:inherit;box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--card) 45%,transparent)}
.field{display:flex;align-items:center;gap:8px;margin:8px 0}
.field span{flex:none;width:74px;font-size:12.5px;color:var(--muted)}
.field input[type=range]{flex:1 1 auto;min-width:0}
.field output{flex:none;width:52px;text-align:right;font-size:12.5px;color:var(--muted)}
.field select{flex:1 1 auto}
/* 主页：状态卡（图标在左，状态/标签/版本在右） + 指标卡 + 信息卡 */
.lkm-card{position:relative;display:flex;align-items:center;gap:12px;margin-bottom:12px;padding:15px 16px;border-radius:16px;background:var(--lkm-bg);color:var(--ink);cursor:pointer;overflow:hidden}
.lkm-card[data-tone=ok]{--lkm-bg:#dffae4;--lkm-fg:#1faf55}
.lkm-card[data-tone=warn]{--lkm-bg:#fff0cf;--lkm-fg:#9a6200}
.lkm-card[data-tone=err]{--lkm-bg:#ffe1e2;--lkm-fg:#d83b45}
.lkm-card:active{filter:brightness(.97)}
.lkm-bubble{position:relative;width:38px;height:38px;flex:none;border-radius:50%;display:grid;place-items:center;font-size:20px;line-height:1;background:color-mix(in srgb,var(--lkm-fg) 16%,transparent);color:var(--lkm-fg)}
.lkm-body{position:relative;display:flex;flex-direction:column;gap:5px;min-width:0;flex:1 1 auto}
.lkm-title{font-size:21px;font-weight:600;line-height:1.25}
.lkm-tags{display:flex;flex-wrap:wrap;gap:6px}
.lkm-tag{padding:2px 9px;border-radius:999px;font-size:11.5px;font-weight:700;background:color-mix(in srgb,var(--lkm-fg) 16%,transparent);color:var(--lkm-fg)}
.lkm-tag.neutral{background:color-mix(in srgb,var(--muted) 20%,transparent);color:var(--muted)}
.lkm-sub{font-size:13px;font-weight:500;opacity:.78;overflow-wrap:anywhere}
.lkm-watermark{position:absolute;right:-10px;bottom:-20px;font-size:76px;line-height:1;font-weight:900;color:var(--lkm-fg);opacity:.18;pointer-events:none}
.metrics{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px}
.metric{display:flex;flex-direction:column;align-items:flex-start;gap:2px;min-height:84px;padding:14px;border:1px solid var(--line);border-radius:18px;background:var(--card);text-align:left;cursor:pointer;color:inherit;font:inherit}
.metric:active{background:var(--card-2)}
.metric-title{font-size:15px;font-weight:500;color:var(--muted)}
.metric-value{font-size:26px;font-weight:600;line-height:1.15}
.metric-sub{font-size:12px;color:var(--muted)}
.info-row{display:flex;align-items:flex-start;gap:10px;padding:14px 16px;border-top:1px solid var(--line)}
.info-row:first-child{border-top:0}
.info-main{flex:1 1 auto;min-width:0}
.info-label{font-size:12px;font-weight:500;color:var(--muted)}
.info-value{margin-top:3px;font-size:14px;font-weight:600;overflow-wrap:anywhere}
@media(prefers-color-scheme:dark){
.lkm-card[data-tone=ok]{--lkm-bg:#1a3825}
.lkm-card[data-tone=warn]{--lkm-bg:#3b3020}
.lkm-card[data-tone=err]{--lkm-bg:#3d2023}}
@media(min-width:760px){
.metrics{gap:14px}
.metric{min-height:104px}
.metric-value{font-size:32px}}
.row{display:flex;align-items:center;gap:12px;padding:13px 14px;border-top:1px solid var(--line)}
.row:first-child{border-top:0}
.row-main{min-width:0;flex:1 1 220px}
.row-title{font-weight:600;font-size:14px}
.row-detail{margin-top:3px;color:var(--muted);font-size:12px;overflow-wrap:anywhere}
.row-actions{display:flex;align-items:center;gap:8px;flex:none}
.mono{font-family:ui-monospace,Menlo,Consolas,monospace}
/* 按钮 */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:38px;padding:8px 14px;border:1px solid var(--line);border-radius:10px;background:var(--card);font-weight:600;font-size:14px;cursor:pointer;white-space:nowrap}
.btn:active{background:var(--card-2)}
.btn:disabled{opacity:.5;cursor:default}
.btn.primary{background:var(--accent);border-color:transparent;color:var(--accent-ink)}
.btn.ok{background:var(--ok-soft);border-color:transparent;color:var(--ok)}
.btn.danger{background:var(--danger-soft);border-color:transparent;color:var(--danger)}
.btn.small{min-height:32px;padding:5px 11px;font-size:13px;border-radius:9px}
/* 工具条 */
.toolbar{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px}
.search{flex:1 1 100%;min-height:42px;padding:9px 12px;border:1px solid var(--line);border-radius:11px;background:var(--card)}
select{min-height:40px;padding:8px 10px;border:1px solid var(--line);border-radius:11px;background:var(--card);flex:1 1 auto}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}
.chip{padding:6px 12px;border:1px solid var(--line);border-radius:999px;background:var(--card);color:var(--muted);font-size:13px;font-weight:600;cursor:pointer}
.chip.active{background:var(--accent);border-color:transparent;color:var(--accent-ink)}
.count{color:var(--muted);font-size:12px;margin-left:auto;align-self:center}
/* 列表 */
.list{display:flex;flex-direction:column;gap:9px}
.item{display:flex;flex-wrap:wrap;gap:12px;padding:12px;background:var(--card);border:1px solid var(--line);border-radius:var(--radius)}
.av{position:relative;width:42px;height:42px;flex:none;border-radius:12px;background:var(--card-2);display:grid;place-items:center;overflow:hidden;font-weight:700;color:var(--muted)}
.av img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.item-main{min-width:0;flex:1 1 220px}
.item-title{display:flex;align-items:center;gap:8px;font-weight:650;font-size:15px}
.item-title .name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.item-meta{margin-top:4px;display:flex;flex-wrap:wrap;align-items:center;gap:7px;color:var(--muted);font-size:12px}
.item-meta .mono{min-width:0;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.superuser-item{width:100%;min-width:0;display:grid;grid-template-columns:42px minmax(0,1fr) auto;align-items:center;overflow:hidden}
.superuser-item .item-main{min-width:0;max-width:100%}
.superuser-item .item-actions{width:auto;min-width:46px;margin:0;justify-content:flex-end}
.item-desc{margin-top:6px;color:var(--muted);font-size:12.5px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.tag{padding:2px 8px;border-radius:999px;background:var(--card-2);color:var(--muted);font-size:11px;font-weight:700;white-space:nowrap}
.tag.ok{background:var(--ok-soft);color:var(--ok)}
.tag.accent{background:var(--accent-soft);color:var(--accent)}
.tag.warn{background:var(--warn-soft);color:var(--warn)}
.tag.danger{background:var(--danger-soft);color:var(--danger)}
.item-actions{margin-top:2px;display:flex;flex-wrap:wrap;gap:7px;width:100%;justify-content:flex-end}
.state{padding:26px 16px;text-align:center;color:var(--muted);font-size:13.5px}
.state b{display:block;color:var(--ink);font-size:15px;margin-bottom:4px}
.state .btn{margin-top:12px}
.notice{display:flex;align-items:center;gap:10px;margin-bottom:10px;padding:11px 12px;border:1px solid var(--line);border-left:3px solid var(--warn);border-radius:11px;background:var(--card);font-size:13px}
.notice.danger{border-left-color:var(--danger)}
.notice .btn{margin-left:auto;flex:none}
/* 控制台面板 */
.sheet{position:fixed;inset:0;z-index:50;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;background:rgba(8,10,14,.45)}
.sheet-panel{width:100%;background:var(--card);border-radius:18px 18px 0 0;max-height:86vh;display:flex;flex-direction:column}
.sheet-head{display:flex;align-items:center;gap:10px;padding:13px 14px;border-bottom:1px solid var(--line)}
.sheet-head .row-main{display:flex;align-items:center;gap:9px;flex-wrap:wrap}
.sheet-body{padding:12px 14px;overflow:auto}
.console{margin:0;padding:12px;min-height:130px;max-height:46vh;overflow:auto;background:#0d1117;color:#d5dbe3;border-radius:11px;font:12.5px/1.55 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere}
.sheet-foot{display:flex;gap:8px;flex-wrap:wrap;padding:12px 14px;border-top:1px solid var(--line)}
.reboot-alert{padding:10px 11px;margin-bottom:10px;border-radius:10px;background:var(--warn-soft);color:var(--warn);font-size:12.5px}
.reboot-alert.danger{background:var(--danger-soft);color:var(--danger)}
.reboot-list{overflow:hidden;border:1px solid var(--line);border-radius:12px;background:var(--card)}
.reboot-option{width:100%;min-height:62px;padding:10px 12px;border:0;border-bottom:1px solid var(--line);background:transparent;display:flex;align-items:center;gap:11px;text-align:left;cursor:pointer}
.reboot-option:last-child{border-bottom:0}
.reboot-option:active{background:var(--card-2)}
.reboot-option:disabled{opacity:.48;cursor:not-allowed}
.reboot-symbol{width:36px;height:36px;flex:none;border-radius:10px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:18px;font-weight:700}
.reboot-option.critical .reboot-symbol{background:var(--danger-soft);color:var(--danger)}
.reboot-copy{min-width:0;flex:1 1 auto}
.reboot-copy b{display:block;font-size:14px}
.reboot-copy span{display:block;margin-top:2px;color:var(--muted);font-size:12px;line-height:1.4}
.reboot-chevron{flex:none;color:var(--muted);font-size:18px}
.toast{position:fixed;left:50%;bottom:calc(78px + env(safe-area-inset-bottom));transform:translateX(-50%);z-index:60;max-width:min(460px,calc(100vw - 28px));padding:11px 14px;border:1px solid var(--line);border-radius:11px;background:var(--card);font-size:13.5px;box-shadow:0 8px 26px rgba(0,0,0,.18)}
.toast.err{border-color:var(--danger);color:var(--danger)}
label.switch{display:inline-flex;align-items:center;gap:8px;flex:none}
/* 开关样式（与原生 Miuix 开关观感一致）：设置页与对话框里的复选框统一成滑动开关 */
input[type=checkbox]{appearance:none;-webkit-appearance:none;position:relative;flex:none;width:46px;height:27px;box-sizing:border-box;border-radius:999px;background:var(--line);border:none;cursor:pointer;transition:background .18s ease;margin:0}
input[type=checkbox]::after{content:"";position:absolute;top:3px;left:3px;width:21px;height:21px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.28);transition:transform .18s ease}
input[type=checkbox]:checked{background:var(--accent)}
input[type=checkbox]:checked::after{transform:translateX(19px)}
input[type=checkbox]:disabled{opacity:.45;cursor:not-allowed}
input[type=checkbox]:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.cat-tools{display:flex;gap:8px;justify-content:flex-end;padding:0 2px 8px}
@media(min-width:760px){
  body{padding-bottom:24px}
  .navbar{display:none}
  .tabs{display:flex}
  .topbar-inner,.tabs,.wrap{max-width:1100px}
  .view{padding:16px 0 24px}
  #view-home:not(.hidden){display:grid;grid-template-columns:minmax(0,.9fr) minmax(0,1.1fr);gap:14px;align-items:start}
  #homeSub{grid-column:1/-1;grid-row:1;margin-bottom:0}
  #authBanner{grid-column:1/-1;grid-row:2;margin-bottom:0}
  #lkmCard{grid-column:1;grid-row:3;margin-bottom:0}
  #view-home>.metrics{grid-column:1;grid-row:4;margin-bottom:0}
  #infoCard{grid-column:2;grid-row:3/span 2;margin-bottom:0}
  .search{flex:1 1 220px}
  .row-main{flex:1 1 auto}
  .item{flex-wrap:nowrap;align-items:center}
  .item-actions{width:auto;flex:none;margin-top:0}
  .sheet-panel{max-width:860px;border-radius:18px;margin-bottom:20px;max-height:78vh}
}
@media(max-width:430px){.topbar .pill{display:none}}
@media(prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
body.compact .item{padding:9px 12px}
body.compact .item-desc{display:none}
body.reduce-motion *{transition:none!important;animation:none!important}
</style>"""

/** 页面标记：<head> 结束到脚本之前。 */
private const val WEB_MANAGER_PAGE_MARKUP: String = """
</head>
<body>
<header class="topbar">
  <div class="topbar-inner">
    <div class="logo" aria-hidden="true">A</div>
    <div class="brand"><b>ApkeSU（web）</b><span id="brandSub">本机控制台 · 仅回环访问</span></div>
    <span id="statusPill" class="pill">读取中</span>
    <button id="susfsShortcut" class="top-action hidden" type="button" title="打开 GKI SUSFS 管理" aria-label="打开 GKI SUSFS 管理"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5.5 5.7v5.8c0 4.2 2.6 7.7 6.5 9.5 3.9-1.8 6.5-5.3 6.5-9.5V5.7L12 3Z"/><path d="M9.2 12.1 11 14l3.9-4.2"/></svg><span>SUSFS</span></button>
    <button id="rebootMenu" class="iconbtn" type="button" title="重启菜单" aria-label="打开重启菜单"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.8v8.4"/><path d="M6.4 6.4a8 8 0 1 0 11.2 0"/></svg></button>
    <button id="refreshAll" class="iconbtn" type="button" title="刷新数据" aria-label="刷新数据"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6v5h-5"/><path d="M19 11a7.5 7.5 0 1 0 .3 3"/></svg></button>
  </div>
</header>

<nav class="tabs" id="desktopTabs" aria-label="页面导航">
  <button class="tab active" type="button" data-view="home"><span class="ico" aria-hidden="true">⌂</span>主页</button>
  <button class="tab" type="button" data-view="superuser"><span class="ico" aria-hidden="true">✓</span>超级用户<span class="tab-count" id="tabSuperuser">-</span></button>
  <button class="tab" type="button" data-view="modules"><span class="ico" aria-hidden="true">▤</span>模块<span class="tab-count" id="tabModules">-</span></button>
  <button class="tab" type="button" data-view="kpm"><span class="ico" aria-hidden="true">◇</span>KPM<span class="tab-count" id="tabKpm">-</span></button>
  <button class="tab" type="button" data-view="settings"><span class="ico" aria-hidden="true">⚙</span>设置</button>
</nav>

<main class="wrap">
  <section id="view-home" class="view">
    <p class="sub" id="homeSub">本机控制台 · 仅回环访问</p>
    <div id="authBanner" class="notice danger hidden"><span id="authBannerText"></span><button class="btn small" type="button" data-retry="status">重试</button></div>

    <div class="lkm-card" id="lkmCard" data-tone="ok" role="button" tabindex="0" aria-label="刷新内核状态">
      <span class="lkm-watermark" id="lkmWatermark" aria-hidden="true">✓</span>
      <span class="lkm-bubble" id="lkmBubble" aria-hidden="true">✓</span>
      <div class="lkm-body">
        <div class="lkm-title" id="kernelState">读取内核状态……</div>
        <div class="lkm-tags">
          <span class="lkm-tag" id="kernelModeTag">-</span>
          <span class="lkm-tag neutral" id="kernelUapiTag">uapi -</span>
        </div>
        <div class="lkm-sub" id="kernelSub">已安装版本：-</div>
      </div>
    </div>

    <div class="metrics">
      <button class="metric" id="metricSuperuserCard" type="button" data-go="superuser">
        <span class="metric-title">超级用户</span>
        <span class="metric-value" id="metricSuperuser">-</span>
        <span class="metric-sub" id="metricSuperuserSub">已授权 UID</span>
      </button>
      <button class="metric" id="metricModuleCard" type="button" data-go="modules">
        <span class="metric-title">模块</span>
        <span class="metric-value" id="metricModule">-</span>
        <span class="metric-sub" id="metricModuleSub">已安装</span>
      </button>
    </div>

    <div class="card" id="infoCard">
      <div class="info-row"><div class="info-main"><div class="info-label">设备型号</div><div class="info-value" id="infoModel">读取中……</div></div><button class="btn small" type="button" data-copy-row="infoModel">复制</button></div>
      <div class="info-row"><div class="info-main"><div class="info-label">系统版本</div><div class="info-value" id="infoSystem">-</div></div><button class="btn small" type="button" data-copy-row="infoSystem">复制</button></div>
      <div class="info-row"><div class="info-main"><div class="info-label">内核版本</div><div class="info-value mono" id="infoKernel">-</div></div><button class="btn small" type="button" data-copy-row="infoKernel">复制</button></div>
      <div class="info-row hidden" id="infoKpmRow"><div class="info-main"><div class="info-label">KPM</div><div class="info-value" id="infoKpm">-</div></div><button class="btn small" type="button" data-copy-row="infoKpm">复制</button></div>
      <div class="info-row hidden" id="infoSusfsRow"><div class="info-main"><div class="info-label">SUSFS</div><div class="info-value" id="infoSusfs">-</div></div><button class="btn small" type="button" data-copy-row="infoSusfs">复制</button></div>
      <div class="info-row"><div class="info-main"><div class="info-label">管理器版本</div><div class="info-value" id="infoManager">-</div></div><button class="btn small" type="button" data-copy-row="infoManager">复制</button></div>
      <div class="info-row"><div class="info-main"><div class="info-label">访问地址</div><div class="info-value mono" id="infoAddress">127.0.0.1:—</div></div><button class="btn small" type="button" data-copy-row="infoAddress">复制</button></div>
    </div>
  </section>

  <section id="view-superuser" class="view hidden">
    <h1>超级用户</h1>
    <p class="sub">直接读写内核应用配置来管理 Root 授权。</p>
    <div id="superuserError" class="notice danger hidden"><span id="superuserErrorText"></span><button class="btn small" type="button" data-retry="superuser">重试</button></div>
    <div class="toolbar">
      <input id="superuserSearch" class="search" type="search" placeholder="搜索应用名称、包名或 UID" autocomplete="off" aria-label="搜索应用">
      <select id="superuserFilter" aria-label="筛选授权状态">
        <option value="all">全部状态</option>
        <option value="granted">已授权</option>
        <option value="not-granted">未授权</option>
      </select>
      <select id="appSort" aria-label="排序">
        <option value="label">按名称排序</option>
        <option value="uid">按 UID 排序</option>
        <option value="status">已授权在前</option>
      </select>
      <button id="superuserRefreshButton" class="btn small" type="button">刷新列表</button>
    </div>
    <div id="superuserStats" class="card row-detail" style="margin-bottom:10px;padding:11px 14px"></div>
    <div id="superusers" class="list" aria-live="polite"></div>
  </section>

  <section id="view-modules" class="view hidden">
    <h1>模块</h1>
    <p class="sub">启用、停用、卸载模块，打开模块 WebUI 或执行 action.sh。</p>
    <div id="moduleError" class="notice hidden"><span id="moduleErrorText"></span><button class="btn small" type="button" data-retry="modules">重试</button></div>
    <div class="toolbar">
      <input id="moduleSearch" class="search" type="search" placeholder="搜索模块名称或 ID" autocomplete="off" aria-label="搜索模块">
      <select id="moduleSort" aria-label="排序">
        <option value="name">按名称排序</option>
        <option value="status">按状态排序</option>
        <option value="version">按版本排序</option>
      </select>
      <button id="moduleRefreshButton" class="btn small" type="button">刷新模块</button>
    </div>
    <div class="chips" id="moduleChips">
      <button class="chip active" type="button" data-filter="all">全部</button>
      <button class="chip" type="button" data-filter="enabled">已启用</button>
      <button class="chip" type="button" data-filter="disabled">已停用</button>
      <button class="chip" type="button" data-filter="webui">含 WebUI</button>
      <button class="chip" type="button" data-filter="action">可执行</button>
      <button class="chip" type="button" data-filter="pending">待重启移除</button>
      <span class="count" id="moduleCount"></span>
    </div>
    <div id="modules" class="list" aria-live="polite"></div>
  </section>

  <section id="view-kpm" class="view hidden">
    <h1>KPM</h1>
    <p class="sub">与「管理器 → KPM 管理」同一套后端：所有操作都经 ksud 落到内核 KPM ABI。</p>
    <div id="kpmError" class="notice danger hidden"><span id="kpmErrorText"></span><button class="btn small" type="button" data-retry="kpm">重试</button></div>
    <div id="kpmNotices"></div>

    <div class="card">
      <div class="row">
        <div class="row-main"><div class="row-title">后端</div><div class="row-detail" id="kpmBackend">读取中……</div></div>
        <span class="tag" id="kpmBackendTag">-</span>
      </div>
      <div class="row"><div class="row-main"><div class="row-title">能力</div><div class="row-detail" id="kpmCapsDetail">-</div></div></div>
      <div class="row">
        <div class="row-main"><div class="row-title">KPM 加载开关</div><div class="row-detail" id="kpmPolicyDetail">-</div></div>
        <label class="switch"><input id="kpmPolicy" type="checkbox" aria-label="KPM 加载开关"><span>允许加载</span></label>
      </div>
      <div class="row">
        <div class="row-main"><div class="row-title">导入 KPM</div><div class="row-detail" id="kpmImportDetail">选择受信任的 AArch64 可重定位 KPM 文件</div></div>
        <input id="kpmImportInput" type="file" class="hidden" accept="*/*">
        <button class="btn small" type="button" id="kpmImportPick">选择文件</button>
      </div>
      <div class="row">
        <div class="row-main"><div class="row-title">排除应用</div><div class="row-detail" id="kpmExcludeDetail">被排除的 UID 不会执行 KPM 的进程级 Hook</div></div>
        <button class="btn small" type="button" id="kpmExcludeOpen">管理</button>
      </div>
    </div>

    <div class="toolbar">
      <input id="kpmSearch" class="search" type="search" placeholder="搜索 KPM 名称或 ID" autocomplete="off" aria-label="搜索 KPM">
      <button id="kpmRefresh" class="btn small" type="button">刷新</button>
    </div>
    <div id="kpmEntries" class="list" aria-live="polite"></div>

    <div id="kpmExcludePanel" class="card hidden">
      <div class="row">
        <div class="row-main"><div class="row-title">排除应用</div><div class="row-detail">勾选后的 UID 写入 KPM 排除表（ksud kpm exclude &lt;包名&gt; &lt;uid&gt;）。</div></div>
        <button class="btn small" type="button" id="kpmExcludeClose">收起</button>
      </div>
      <div style="padding:12px 14px 0"><input id="kpmExcludeSearch" class="search" type="search" placeholder="搜索应用名称或包名" autocomplete="off" aria-label="搜索待排除应用"></div>
      <div id="kpmExcludeList" class="list" style="padding:12px 14px"></div>
    </div>

    <p class="sub" style="margin-top:12px">KPM 使用 KernelPatch 区段 ABI；ApkeSU 负责受信任导入、策略、救砖恢复与 su 日志集成。请勿导入来源不明的内核代码。</p>
  </section>

  <section id="view-settings" class="view hidden">
    <h1>设置</h1>
    <p class="sub">网页管理器的运行、显示与安全选项，按原生管理器的分类归置。</p>
    <div id="settingsError" class="notice danger hidden"><span id="settingsErrorText"></span><button class="btn small" type="button" data-retry="settings">重试</button></div>
    <div class="cat-tools">
      <button class="btn small" type="button" id="expandCats">展开全部</button>
      <button class="btn small" type="button" id="collapseCats">收起全部</button>
    </div>

    <details class="cat">
      <summary>服务与维护</summary>
      <div class="card">
        <div class="row"><div class="row-main"><div class="row-title">开机自动启动</div><div class="row-detail">开机后自动启动服务（随机回环端口，启动后以访问地址为准）</div></div><input id="autoStart" type="checkbox" aria-label="开机自动启动"></div>
        <div class="row"><div class="row-main"><div class="row-title">服务状态</div><div class="row-detail" id="settingsService">读取中</div></div></div>
        <div class="row"><div class="row-main"><div class="row-title">运行时长</div><div class="row-detail" id="settingsUptime">读取中</div></div></div>
        <div class="row"><div class="row-main"><div class="row-title">接口版本</div><div class="row-detail" id="settingsApi">-</div></div></div>
        <div class="row"><div class="row-main"><div class="row-title">自动刷新</div><div class="row-detail">每 10 秒刷新状态与模块数据</div></div><input id="autoRefreshToggle" type="checkbox" aria-label="自动刷新"></div>
        <div class="row"><div class="row-main"><div class="row-title">隐身模式</div><div class="row-detail" id="stealthDetail">读取中</div></div><input id="stealthToggle" type="checkbox" aria-label="隐身模式"></div>
        <div class="row"><div class="row-main"><div class="row-title">隐身密令</div><div class="row-detail mono" id="stealthCodeDetail">默认 *#*#4211#*#*</div></div><button class="btn small" type="button" id="stealthCodeEdit">设置密令</button></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">隐藏桌面图标</div><div class="row-detail" id="launcherDetail">隐藏后桌面不再显示管理器图标，管理器只能从本控制台进入（APK 仍在、服务照常运行）。关掉本开关即可恢复图标。</div></div>
          <input id="launcherHidden" type="checkbox" aria-label="隐藏桌面图标">
        </div>
        <div class="row">
          <div class="row-main"><div class="row-title">端口模式</div><div class="row-detail" id="portDetail">默认随机回环端口；固定端口后地址不再变化，方便收藏本控制台。</div></div>
          <select id="portMode" aria-label="端口模式"><option value="random">随机端口</option><option value="fixed">固定端口</option></select>
        </div>
        <div class="row" id="fixedPortRow">
          <div class="row-main"><div class="row-title">固定端口</div><div class="row-detail">1024-65535（普通应用无法绑定更低端口），重启服务后生效</div></div>
          <div class="row-actions"><input id="fixedPortInput" class="tool-target" type="number" min="1024" max="65535" placeholder="例如 45678" aria-label="固定端口"><button class="btn small" type="button" id="applyPort">保存</button></div>
        </div>
        <div class="row"><div class="row-main"><div class="row-title">当前端口</div><div class="row-detail mono" id="portDetailCurrent">-</div></div></div>
      </div>
      <div class="card">
        <div class="row"><div class="row-main"><div class="row-title">访问安全</div><div class="row-detail">仅监听回环地址，页面与资源均需随机访问令牌。令牌失效时，请在管理器中重新打开网页管理器。</div></div></div>
        <div class="row"><div class="row-main"><div class="row-title">访问地址</div><div class="row-detail mono" id="settingsAddress">-</div></div><button class="btn small" type="button" id="copyAddress">复制</button></div>
        <div class="row"><div class="row-main"><div class="row-title">清空缓存</div><div class="row-detail">重新读取模块、授权与设备数据</div></div><button class="btn small" type="button" id="clearCache">执行</button></div>
      </div>
    </details>

    <details class="cat" open>
      <summary>软件管理器</summary>
      <div class="card">
        <div class="row"><div class="row-main"><div class="row-title">语言</div><div class="row-detail">选择整个应用使用的界面语言</div></div><select id="languageSelect" aria-label="软件管理器语言"><option value="">读取中</option></select></div>
        <div class="row"><div class="row-main"><div class="row-title">检查模块更新</div><div class="row-detail">自动检查已安装模块是否有可用更新</div></div><input id="managerCheckModuleUpdate" type="checkbox" data-manager-setting="checkModuleUpdate" aria-label="检查模块更新"></div>
        <div class="row"><div class="row-main"><div class="row-title">版本不匹配警告</div><div class="row-detail">管理器与 ApkeSU 驱动版本不匹配时显示警告</div></div><input id="managerVersionWarning" type="checkbox" data-manager-setting="showVersionMismatchWarning" aria-label="版本不匹配警告"></div>
        <div class="row"><div class="row-main"><div class="row-title">显示 GKI 测试提示</div><div class="row-detail">在首页显示 GKI 工作模式仅建议测试使用的提醒</div></div><input id="managerGkiWarning" type="checkbox" data-manager-setting="showGkiWarning" aria-label="显示 GKI 测试提示"></div>
        <div class="row"><div class="row-main"><div class="row-title">显示支持开发卡片</div><div class="row-detail">在主页显示支持开发卡片</div></div><input id="managerSupportCard" type="checkbox" data-manager-setting="showHomeSupportCard" aria-label="显示支持开发卡片"></div>
        <div class="row"><div class="row-main"><div class="row-title">显示了解 ApkeSU 卡片</div><div class="row-detail">在主页显示了解 ApkeSU 卡片</div></div><input id="managerLearnCard" type="checkbox" data-manager-setting="showHomeLearnCard" aria-label="显示了解 ApkeSU 卡片"></div>
        <div class="row"><div class="row-main"><div class="row-title">动态管理器</div><div class="row-detail" id="dynamicManagerDetail">读取中</div></div><button class="btn small" type="button" id="dynamicManagerOpen">管理</button></div>
        <div class="row"><div class="row-main"><div class="row-title">自定义主页顶部名称</div><div class="row-detail" id="managerHomeTitleDetail">当前跟随默认名称</div></div><button class="btn small" type="button" id="managerHomeTitleEdit">修改</button></div>
      </div>
      <div id="managerSettingsError" class="notice warn hidden"><span></span></div>
    </details>

    <details class="cat">
      <summary>外观</summary>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">界面主题</div><div class="row-detail">跟随系统 / 浅色 / 深色，与原生管理器的日夜设置对应</div></div>
          <select id="themeSelect" aria-label="界面主题"><option value="auto">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select>
        </div>
        <div class="row"><div class="row-main"><div class="row-title">紧凑列表</div><div class="row-detail">减少列表行高与描述，适合小屏幕</div></div><input id="compactMode" type="checkbox" aria-label="紧凑列表"></div>
        <input id="assetFileInput" type="file" class="hidden" accept="image/*">
        <div class="row"><div class="row-main"><div class="row-title">减少动画</div><div class="row-detail">关闭界面过渡与动画效果</div></div><input id="reduceMotion" type="checkbox" aria-label="减少动画"></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">自定义壁纸</div><div class="row-detail">主页四张卡片可各配一张背景图，支持缩放、平移、变暗、模糊</div></div>
        </div>
        <div id="wallpaperRows"></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">自定义导航栏图标</div><div class="row-detail">替换底部标签栏图标，可调大小与垂直偏移</div></div>
        </div>
        <div id="navIconRows"></div>
      </div>
    </details>

    <details class="cat">
      <summary>Root 与权限</summary>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">内核特性开关</div><div class="row-detail">与原生「Root 与权限」同一套开关，状态实时探测</div></div>
          <button class="btn small" type="button" id="refreshFeaturesRoot">刷新</button>
        </div>
        <div id="featureRowsRoot"><div class="state">读取中……</div></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">重启设备</div><div class="row-detail">正常重启、用户空间、软重启与启动模式</div></div>
          <button class="btn small" type="button" id="rebootMenuSettings">打开菜单</button>
        </div>
      </div>
    </details>

    <details class="cat">
      <summary>挂载与隐藏</summary>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">挂载特性开关</div><div class="row-detail">与原生「挂载与隐藏」同一套开关，状态实时探测</div></div>
          <button class="btn small" type="button" id="refreshFeaturesMount">刷新</button>
        </div>
        <div id="featureRowsMount"><div class="state">读取中……</div></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">内置挂载</div><div class="row-detail">Hybrid Mount Lite 内置挂载模块</div></div>
          <button class="btn small" type="button" id="refreshBuiltinMount">刷新</button>
        </div>
        <div class="tool-rows" id="builtinMountBody"><div class="state">读取中……</div></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">KPatch-Next</div><div class="row-detail">内嵌 KPatch-Next 内核补丁模块</div></div>
          <button class="btn small" type="button" id="refreshKPatch">刷新</button>
        </div>
        <div class="tool-rows" id="kpatchBody"><div class="state">读取中……</div></div>
      </div>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">隐藏路径</div><div class="row-detail">pathmask：对指定路径做隐藏（需 pathmask LKM）</div></div>
          <button class="btn small" type="button" id="refreshPathmask">刷新</button>
        </div>
        <div class="tool-rows" id="pathmaskBody"><div class="state">读取中……</div></div>
        <div class="tool-rows" id="pathmaskLogs"></div>
      </div>
    </details>

    <details class="cat">
      <summary>工具箱</summary>
      <div class="card">
        <div class="row">
          <div class="row-main"><div class="row-title">CPU 伪装</div><div class="row-detail">把 /proc/cpuinfo 等处的型号替换成自定义值</div></div>
          <button class="btn small" type="button" id="refreshCpuSpoof">刷新</button>
        </div>
        <div class="tool-rows" id="cpuSpoofBody"><div class="state">读取中……</div></div>
      </div>
      <div class="card">
        <div class="row"><div class="row-main"><div class="row-title">诊断</div><div class="row-detail">检查 root shell、ksud、模块与授权查询的实际状态</div></div><button class="btn small" type="button" id="runDiagnostics">运行</button></div>
        <div style="padding:0 14px 14px"><pre id="diagnostics" class="console">尚未运行诊断。</pre></div>
        <div class="row"><div class="row-main"><div class="row-title">复制诊断结果</div><div class="row-detail">把结果发给维护者即可定位问题</div></div><button class="btn small" type="button" id="copyDiagnostics">复制</button></div>
      </div>
    </details>
  </section>
</main>

<nav class="navbar" id="bottomTabs" aria-label="页面导航">
  <button class="active" type="button" data-view="home"><span class="ico" aria-hidden="true">⌂</span>主页</button>
  <button type="button" data-view="superuser"><span class="ico" aria-hidden="true">✓</span>授权</button>
  <button type="button" data-view="modules"><span class="ico" aria-hidden="true">▤</span>模块</button>
  <button type="button" data-view="kpm"><span class="ico" aria-hidden="true">◆</span>KPM</button>
  <button type="button" data-view="settings"><span class="ico" aria-hidden="true">⚙</span>设置</button>
</nav>

<div id="kpmDialog" class="sheet hidden" role="dialog" aria-modal="true" aria-label="操作面板">
  <div class="sheet-panel">
    <div class="sheet-head">
      <div class="row-main"><h2 id="kpmDialogTitle">KPM 操作</h2></div>
      <button id="kpmDialogClose" class="iconbtn" type="button" aria-label="关闭">✕</button>
    </div>
    <div class="sheet-body" id="kpmDialogBody"></div>
    <div class="sheet-foot" id="kpmDialogFoot"></div>
  </div>
</div>

<div id="sheet" class="sheet hidden" role="dialog" aria-modal="true" aria-label="模块脚本控制台">
  <div class="sheet-panel">
    <div class="sheet-head">
      <div class="row-main">
        <h2 id="sheetTitle">执行模块脚本</h2>
        <span id="sheetState" class="tag">准备中</span>
      </div>
      <button id="sheetClose" class="iconbtn" type="button" aria-label="关闭">✕</button>
    </div>
    <div class="sheet-body">
      <pre id="consoleOut" class="console" tabindex="0">等待输出……</pre>
    </div>
    <div class="sheet-foot">
      <button id="sheetCancel" class="btn danger" type="button">中止执行</button>
      <button id="sheetRerun" class="btn" type="button">重新执行</button>
      <button id="sheetCopy" class="btn" type="button">复制输出</button>
    </div>
  </div>
</div>
<div id="toast" class="toast hidden" role="status"></div>

"""

/** 页面脚本上半部分。 */
private const val WEB_MANAGER_PAGE_SCRIPT_HEAD: String = """<script>
(function () {
  "use strict";
  var TOKEN_KEY = "apkesu_web_token";
  var queryToken = new URLSearchParams(location.search).get("token") || "";
  var token = queryToken;
  try {
    if (queryToken) {
      sessionStorage.setItem(TOKEN_KEY, queryToken);
    } else {
      token = sessionStorage.getItem(TOKEN_KEY) || "";
    }
  } catch (_) { /* storage disabled: the query token still works */ }
  var PREFIX = token ? "/w/" + token : "";

  var state = {
    view: "home",
    status: null,
    device: null,
    modules: [],
    moduleLoaded: false,
    moduleFilter: "all",
    moduleQuery: "",
    moduleSort: "name",
    apps: [],
    appLoaded: false,
    appLoading: false,
    summaryLoaded: false,
    kpm: null,
    kpmLoaded: false,
    kpmQuery: "",
    kpmExcludeQuery: "",
    kpmControlTarget: null,
    kpmPendingFile: null,
    theme: "auto",
    stealth: { enabled: false, code: "*#*#4211#*#*" },
    managerSettings: null,
    features: [],
    featuresLoaded: false,
    assets: { wallpapers: {}, navIcons: {}, moduleWallpapers: {} },
    tools: null,
    toolsLoaded: false,
    rebootStatus: null,
    rebootPending: false,
    rebootMenuRequest: 0,
    assetCatalog: { wallpaperTargets: {}, navIconSlots: {} },
    assetPickTarget: null,
    assetAdjustTarget: null,
    appQuery: "",
    appFilter: "all",
    appSort: "label",
    appStats: { uidCount: 0, totalApps: 0, authorizedCount: 0, sharedUidCount: 0, source: "" },
    busy: {},
    activity: [],
    autoRefresh: false,
    timer: null,
    job: null,
    jobModule: null,
    jobTimer: null,
    authError: false
  };

  var el = function (id) { return document.getElementById(id); };
  function esc(value) {
    return String(value === null || value === undefined ? "" : value)
      .replace(/[&<>"']/g, function (c) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
      });
  }
  function flag(key, fallback) {
    try {
      var value = localStorage.getItem("web_manager_" + key);
      if (value === null) return fallback;
      return value === "1";
    } catch (_) {
      return fallback;
    }
  }
  function setFlag(key, value) {
    try { localStorage.setItem("web_manager_" + key, value ? "1" : "0"); } catch (_) { }
  }
  var toastTimer = null;
  function notify(message, isError) {
    var box = el("toast");
    box.textContent = message;
    box.className = "toast" + (isError ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.className = "toast hidden"; }, 3600);
  }
  function record(message, isError) {
    state.activity.unshift({
      time: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
      message: message,
      error: !!isError
    });
    state.activity = state.activity.slice(0, 12);
  }
  function formatUptime(seconds) {
    var total = Math.max(0, Math.floor(Number(seconds) || 0));
    var days = Math.floor(total / 86400);
    var hours = Math.floor((total % 86400) / 3600);
    var minutes = Math.floor((total % 3600) / 60);
    var secs = total % 60;
    var parts = [];
    if (days) parts.push(days + " 天");
    if (hours) parts.push(hours + " 小时");
    if (minutes) parts.push(minutes + " 分");
    parts.push(secs + " 秒");
    return parts.join(" ");
  }
  function shortVersion(value) {
    if (value === undefined || value === null || value === "" || Number(value) <= 0) return "未知";
    return "v" + value;
  }
  function accessAddress() {
    return location.origin + "/?token=" + encodeURIComponent(token);
  }

  function api(path, options) {
    var config = options || {};
    var headers = Object.assign({ "Authorization": "Bearer " + token }, config.headers || {});
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, config.timeout || 60000);
    config.headers = headers;
    config.signal = controller.signal;
    return fetch(PREFIX + path, config).then(function (response) {
      return response.text().then(function (text) {
        var data;
        try { data = JSON.parse(text); } catch (_) { data = { error: text || "服务器返回了无效数据" }; }
        if (response.status === 401) {
          showAuthBanner("访问令牌已失效，请在管理器中重新打开网页管理器。");
          throw new Error("访问令牌已失效");
        }
        if (!response.ok) {
          var error = new Error(data.error || response.statusText || "请求失败");
          error.code = data.errorCode || ("http_" + response.status);
          error.detail = data.detail || "";
          throw error;
        }
        return data;
      });
    }).finally(function () { clearTimeout(timer); });
  }

  function showAuthBanner(message) {
    state.authError = true;
    el("authBannerText").textContent = message;
    el("authBanner").classList.remove("hidden");
  }

  function setPill(text, kind) {
    var pill = el("statusPill");
    pill.textContent = text;
    pill.className = "pill" + (kind ? " " + kind : "");
  }

  // -------------------------------------------------------- 主页：内核状态

  var KERNEL_MODES = {
    lkm: { label: "LKM", tag: "accent", text: "LKM（可加载内核模块）" },
    gki: { label: "GKI", tag: "ok", text: "GKI（内核内置驱动）" },
    late_load: { label: "晚加载", tag: "warn", text: "晚加载 / jailbreak 模式" },
    unknown: { label: "未知", tag: "warn", text: "未知工作模式" }
  };

  function renderDeviceCard() {
    var data = state.device;
    if (!data) return;
    var kernel = data.kernel || {};
    var device = data.device || {};
    var mode = KERNEL_MODES[kernel.mode] || KERNEL_MODES.unknown;
    var driverVersion = Number(kernel.driverVersion || 0);
    var managerCode = Number(device.managerVersionCode || 0);
    var kernelUapi = Number(kernel.kernelUapi || 0);
    var managerUapi = Number(kernel.managerUapi || 0);
    var uapiMismatch = kernelUapi > 0 && managerUapi > 0 && kernelUapi !== managerUapi;
    var versionMismatch = driverVersion > 0 && managerCode > 0 && driverVersion !== managerCode;

    /* 判定顺序对齐原生 RootRuntimeState：驱动未连接 → 版本不匹配 → 守护进程异常 → 正常运行 */
    var label, tone, glyph;
    if (driverVersion <= 0 && !kernel.kernelModuleLoaded && kernel.mode === "unknown") {
      label = "驱动未连接"; tone = "err"; glyph = "✕";
    } else if (uapiMismatch || versionMismatch) {
      label = "版本不匹配"; tone = "warn"; glyph = "!";
    } else if (!kernel.kernelModuleLoaded && !kernel.ksuRootShell) {
      label = "守护进程异常"; tone = "warn"; glyph = "!";
    } else {
      label = "正常运行"; tone = "ok"; glyph = "✓";
    }
    el("lkmCard").dataset.tone = tone;
    el("lkmBubble").textContent = glyph;
    el("lkmWatermark").textContent = glyph;
    el("kernelState").textContent = label;
    el("kernelModeTag").textContent = kernel.mode === "late_load" ? "越狱模式" : mode.label;
    el("kernelUapiTag").textContent = "uapi " + (kernelUapi > 0 ? kernelUapi : "未知");
    el("kernelSub").textContent = "已安装版本：" +
      (driverVersion > 0 ? shortVersion(driverVersion) + (kernelUapi > 0 ? "-" + kernelUapi : "") : mode.text) +
      (versionMismatch ? "（与当前管理器 " + managerCode + " 不一致）" : "");
    el("susfsShortcut").classList.toggle("hidden", kernel.mode !== "gki" || driverVersion <= 0);

    // 卡片只保留状态 / 模式标签 / 已安装版本；内核与 KMI 细节放到下方「设备信息」里

    var release = kernel.release || "未知";
    if (kernel.kmi) release += " · KMI " + kernel.kmi;
    if (kernel.slot) release += " · 槽位 " + kernel.slot;
    el("infoModel").textContent = (device.manufacturer ? device.manufacturer + " " : "") +
      (device.model || "未知设备");
    el("infoSystem").textContent = "Android " + (device.androidRelease || "?") +
      "（API " + (device.sdkInt || "?") + "） · " + (device.abi || "未知架构");
    el("infoKernel").textContent = release;
    /* KPM / SUSFS：接口返回空串表示内核没有，整行隐藏 */
    setInfoRow("infoKpmRow", data.kpm);
    setInfoRow("infoSusfsRow", data.susfs);
    el("infoManager").textContent = "ApkeSU " + (device.managerVersionName || "-") +
      "（" + (device.managerVersionCode || "-") + "） · 接口 v" + (data.apiVersion || "-") +
      (data.stale ? " · 状态来自缓存" : "");
    if (data.port) el("infoAddress").textContent = "127.0.0.1:" + data.port;
  }

  function renderStatusCards() {
    var data = state.status;
    if (data) {
      var running = data.serverRunning !== false;
      var root = !!data.root;
      var modulesOk = data.moduleQueryOk !== false;
      setPill(!running ? "服务异常" : !root ? "Root 不可用" : modulesOk ? "运行正常" : "部分可用",
        !running || !root ? "err" : modulesOk ? "ok" : "warn");
      if (data.port) {
        el("homeSub").textContent = "本机控制台 · 127.0.0.1:" + data.port +
          " · 已运行 " + formatUptime(data.uptimeSeconds);
      }
    }
    renderModuleCard();
    renderSuperuserCard();
  }

  function renderModuleCard() {
    var total = state.modules.length;
    var enabled = state.modules.filter(function (m) { return m.enabled && !m.remove; }).length;
    var pending = state.modules.filter(function (m) { return m.remove; }).length;
    el("tabModules").textContent = state.moduleLoaded ? String(total) : "-";
    el("metricModule").textContent = state.moduleLoaded ? String(total) : "-";
    if (!state.moduleLoaded) {
      el("metricModuleSub").textContent = "读取中……";
      return;
    }
    var sub = total ? "已启用 " + enabled : "尚未安装模块";
    if (pending > 0) sub += " · 待重启移除 " + pending;
    el("metricModuleSub").textContent = sub;
  }

  function renderSuperuserCard() {
    var stats = state.appStats;
    var known = state.appLoaded || state.summaryLoaded;
    el("tabSuperuser").textContent = known ? String(stats.uidCount || state.apps.length) : "-";
    el("metricSuperuser").textContent = known ? String(stats.authorizedCount || 0) : "-";
    if (!known) {
      el("metricSuperuserSub").textContent = "读取中……";
      return;
    }
    el("metricSuperuserSub").textContent = "已授权 UID · 可管理 " + stats.uidCount +
      (stats.source === "local" ? "（本机兜底）" : "");
  }

  /** KPM / SUSFS 这类「有就显示」的信息行：值为空串时整行隐藏。 */
  function setInfoRow(rowId, value) {
    var row = el(rowId);
    if (!row) return;
    var text = String(value === undefined || value === null ? "" : value).trim();
    row.classList.toggle("hidden", !text);
    var target = row.querySelector(".info-value");
    if (target) target.textContent = text || "-";
  }

  function copyFromRow(button) {
    var row = button.closest(".info-row");
    var id = button.dataset.copyRow;
    var node = id ? el(id) : null;
    var label = row ? (row.querySelector(".info-label").textContent || "内容") : "内容";
    var text = node ? node.textContent.trim() : "";
    if (!text || text === "-" || text.indexOf("读取中") === 0) {
      notify("暂无可复制的内容", true);
      return;
    }
    var done = function () { notify(label + "已复制"); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { window.prompt(label, text); });
    } else {
      window.prompt(label, text);
    }
  }

  /** Counters only: the home card does not need the whole app list. */
  function loadSuperuserSummary(force) {
    return api("/api/superuser/summary" + (force ? "?refresh=1" : ""), { timeout: 60000 })
      .then(function (data) {
        state.appStats = {
          uidCount: Number(data.uidCount || 0),
          totalApps: Number(data.totalApps || 0),
          authorizedCount: Number(data.authorizedCount || 0),
          sharedUidCount: Number(data.sharedUidCount || 0),
          source: String(data.source || "")
        };
        state.summaryLoaded = true;
        renderSuperuserCard();
        return true;
      })
      .catch(function (error) {
        if (!state.appLoaded) {
          el("metricSuperuser").textContent = "-";
          el("metricSuperuserSub").textContent = "读取失败：" + (error.message || "未知错误");
        }
        return false;
      });
  }

  // -------------------------------------------------------- 功能开关（内核特性）

  var FEATURE_STATUS_TEXT = {
    supported: "可用",
    unsupported: "内核不支持",
    unknown: "无法探测",
    unavailable: "不可用"
  };

  function loadFeatures() {
    ["featureRowsRoot", "featureRowsMount"].forEach(function (nodeId) {
      var node = el(nodeId);
      if (node) node.innerHTML = '<div class="state">读取中……</div>';
    });
    return api("/api/features", { timeout: 30000 }).then(function (data) {
      state.features = (data && data.features) || [];
      state.featuresLoaded = true;
      renderFeatures();
      return state.features;
    }).catch(function (error) {
      state.featuresLoaded = false;
      ["featureRowsRoot", "featureRowsMount"].forEach(function (nodeId) {
        var node = el(nodeId);
        if (node) {
          node.innerHTML = '<div class="state error">开关状态读取失败：' +
            esc(error.message || "未知错误") + '</div>';
        }
      });
      throw error;
    });
  }

  function featureRowHtml(feature) {
    return (function () {
      var status = String(feature.status || "unknown");
      var detail = FEATURE_STATUS_TEXT[status] || status;
      if (feature.rebootHint) detail += " · " + feature.rebootHint;
      return '<div class="row">' +
        '<div class="row-main"><div class="row-title">' + esc(feature.label) + '</div>' +
        '<div class="row-detail">' + esc(feature.summary || "") + '<br>状态：' + esc(detail) + '</div></div>' +
        '<input type="checkbox" data-feature="' + esc(feature.key) + '"' +
        (feature.enabled ? " checked" : "") + (feature.supported ? "" : " disabled") +
        ' aria-label="' + esc(feature.label) + '"></div>';
    })();
  }

  function renderFeatures() {
    var groups = { root: [], mount: [] };
    state.features.forEach(function (feature) {
      var group = feature.group === "mount" ? "mount" : "root";
      groups[group].push(featureRowHtml(feature));
    });
    renderFeatureGroup("featureRowsRoot", groups.root);
    renderFeatureGroup("featureRowsMount", groups.mount);
  }

  function renderFeatureGroup(nodeId, rows) {
    var node = el(nodeId);
    if (!node) return;
    node.innerHTML = rows.length
      ? rows.join("")
      : '<div class="state">这台设备没有该分类下可调整的开关</div>';
  }

  function setFeature(key, enabled, node) {
    if (node) node.disabled = true;
    kpmPost("/api/features", { key: key, enabled: enabled }, 30000).then(function (result) {
      notify((enabled ? "已开启 " : "已关闭 ") + key + (result && result.rebootHint ? "（" + result.rebootHint + "）" : ""));
      record("功能开关：" + key + "=" + enabled);
      return loadFeatures();
    }).catch(function (error) {
      notify("开关写入失败：" + (error.message || "未知错误"), true);
      return loadFeatures();
    }).finally(function () {
      if (node) node.disabled = false;
    });
  }

  // ------------------------------------------------------------ 主题与自定义外观

  /** 主题：auto 交给系统 prefers-color-scheme，light/dark 由 data-theme 强制。 */
  function applyTheme(theme) {
    state.theme = theme === "light" || theme === "dark" ? theme : "auto";
    var root = document.documentElement;
    if (state.theme === "auto") {
      root.removeAttribute("data-theme");
    } else {
      root.setAttribute("data-theme", state.theme);
    }
  }

  function assetBucket(kind) {
    if (kind === "wallpaper") return state.assets.wallpapers || {};
    if (kind === "navicon") return state.assets.navIcons || {};
    return state.assets.moduleWallpapers || {};
  }

  function assetMeta(kind, name) {
    return assetBucket(kind)[name] || null;
  }

  function assetUrl(kind, name) {
    var meta = assetMeta(kind, name);
    var suffix = meta && meta.updatedAt ? "?v=" + meta.updatedAt : "";
    return PREFIX + "/api/assets/" + kind + "/" + name + suffix;
  }

  function assetLabel(kind, name) {
    var catalog = state.assetCatalog || {};
    if (kind === "modulewall") {
      var module = (state.modules || []).filter(function (item) { return item.id === name; })[0];
      return module ? module.name : name;
    }
    var table = kind === "wallpaper" ? (catalog.wallpaperTargets || {}) : (catalog.navIconSlots || {});
    return table[name] || name;
  }

  var WALLPAPER_HOSTS = {
    lkm: "lkmCard",
    superuser: "metricSuperuserCard",
    module: "metricModuleCard",
    device: "infoCard"
  };
  var NAV_ICON_SLOTS = ["home", "superuser", "module", "kpm", "settings"];
  var NAV_ICON_VIEWS = { home: "home", superuser: "superuser", module: "modules", kpm: "kpm", settings: "settings" };

  /** 把某张卡片的壁纸（含缩放/偏移/变暗/模糊）贴到 DOM 上；没有壁纸就撤掉。 */
  function applyWallpaper(target) {
    var host = el(WALLPAPER_HOSTS[target]);
    if (!host) return;
    var meta = assetMeta("wallpaper", target);
    var layer = host.querySelector(".bg-layer");
    var dim = host.querySelector(".bg-dim");
    if (!meta) {
      if (layer) layer.remove();
      if (dim) dim.remove();
      host.classList.remove("card-bg-host", "light-text");
      return;
    }
    if (!layer) {
      layer = document.createElement("div");
      layer.className = "bg-layer";
      dim = document.createElement("div");
      dim.className = "bg-dim";
      host.insertBefore(dim, host.firstChild);
      host.insertBefore(layer, dim);
    }
    host.classList.add("card-bg-host", "light-text");
    applyWallpaperStyles(layer, dim, meta, assetUrl("wallpaper", target));
  }

  function applyWallpaperStyles(layer, dim, meta, url) {
    layer.style.backgroundImage = url ? 'url("' + url + '")' : layer.style.backgroundImage;
    layer.style.backgroundSize = meta.fit === "stretch"
      ? "100% 100%"
      : (meta.fit === "contain" ? "contain" : "cover");
    layer.style.transform = "scale(" + (meta.scale || 1) + ") translate(" + (meta.offsetX || 0) + "%, " +
      (meta.offsetY || 0) + "%)";
    layer.style.filter = Number(meta.blur) > 0 ? "blur(" + Number(meta.blur) + "px)" : "none";
    dim.style.opacity = String(meta.dim === undefined ? 0.35 : meta.dim);
  }

  function applyNavIcon(slot) {
    var meta = assetMeta("navicon", slot);
    var view = NAV_ICON_VIEWS[slot] || slot;
    document.querySelectorAll('.navbar button[data-view="' + view + '"], .tabs button[data-view="' + view + '"]').forEach(function (button) {
      var icon = button.querySelector(".ico");
      if (!icon) return;
      if (!meta) {
        button.classList.remove("custom-icon");
        icon.style.removeProperty("--nav-image");
        icon.style.removeProperty("--nav-scale");
        icon.style.removeProperty("--nav-offset-x");
        icon.style.removeProperty("--nav-offset-y");
        return;
      }
      button.classList.add("custom-icon");
      icon.style.setProperty("--nav-image", 'url("' + assetUrl("navicon", slot) + '")');
      icon.style.setProperty("--nav-scale", String(meta.scale || 1));
      icon.style.setProperty("--nav-offset-x", String(meta.offsetX || 0) + "px");
      icon.style.setProperty("--nav-offset-y", String(meta.offsetY || 0) + "px");
    });
  }

  function applyAssets() {
    Object.keys(WALLPAPER_HOSTS).forEach(applyWallpaper);
    NAV_ICON_SLOTS.forEach(applyNavIcon);
    document.querySelectorAll("article.item[data-module-id]").forEach(function (card) {
      applyModuleWallpaper(card.getAttribute("data-module-id"), card);
    });
    renderAssetSettings();
  }

  function renderAssetSettings() {
    var catalog = state.assetCatalog || {};
    var targets = catalog.wallpaperTargets || {};
    var slots = catalog.navIconSlots || {};
    var wallpapers = Object.keys(targets).map(function (key) {
      return assetRow("wallpaper", key, targets[key], "裁剪 / 缩放 / 移动 / 变暗 / 模糊");
    }).join("");
    el("wallpaperRows").innerHTML = wallpapers || '<div class="state">没有可配置的卡片</div>';
    var icons = Object.keys(slots).map(function (key) {
      return assetRow("navicon", key, slots[key], "方形裁剪 / 缩放 / 水平与垂直移动");
    }).join("");
    el("navIconRows").innerHTML = icons || '<div class="state">没有可配置的图标</div>';
  }

  function assetRow(kind, name, label, hint) {
    var key = kind + "/" + name;
    var meta = assetMeta(kind, name);
    var preview = meta
      ? '<img class="asset-thumb" alt="" src="' + assetUrl(kind, name) + '">'
      : '<span class="asset-thumb empty">未设置</span>';
    return '<div class="row">' +
      '<div class="row-main"><div class="row-title">' + esc(label) + '</div>' +
      '<div class="row-detail">' + (meta ? "已设置 · 可调整 " + hint : "未设置图片") + '</div></div>' +
      preview +
      '<div class="row-actions">' +
      '<button class="btn small" type="button" data-asset-pick="' + key + '">选择图片</button>' +
      (meta
        ? '<button class="btn small" type="button" data-asset-adjust="' + key + '">裁剪</button>' +
          '<button class="btn small danger" type="button" data-asset-clear="' + key + '">清除</button>'
        : '') +
      '</div></div>';
  }

  function startAssetPick(key) {
    var parts = key.split("/");
    state.assetPickTarget = { kind: parts[0], name: parts[1] };
    el("assetFileInput").click();
  }

  function uploadAsset(file) {
    var target = state.assetPickTarget;
    if (!target || !file) return;
    var label = target.kind === "navicon" ? "导航图标" : (target.kind === "modulewall" ? "模块壁纸" : "壁纸");
    notify("正在上传" + label + "……");
    api("/api/assets/" + target.kind + "/" + target.name, {
      method: "POST",
      headers: { "Content-Type": file.type || "application/octet-stream" },
      body: file,
      timeout: 180000
    }).then(function (result) {
      notify(label + "已更新（" + formatBytes((result && result.bytes) || file.size) + "）");
      record("自定义" + label + "：" + target.name);
      var kind = target.kind;
      var name = target.name;
      return loadSettings().then(function () {
        // 模块卡片壁纸：按钮状态（换壁纸/调整/清除）要跟着列表一起刷新
        if (kind === "modulewall") renderModules();
        openAssetAdjust(kind + "/" + name);
        return name;
      });
    }).catch(function (error) {
      notify("上传失败：" + (error.message || "未知错误"), true);
    }).finally(function () {
      state.assetPickTarget = null;
    });
  }

  function clearAsset(key) {
    var parts = key.split("/");
    var kind = parts[0], name = parts[1];
    api("/api/assets/" + kind + "/" + name, { method: "DELETE", timeout: 30000 })
      .then(function () {
        notify("已清除自定义" + (kind === "navicon" ? "图标" : "壁纸"));
        record("清除自定义外观：" + kind + "/" + name);
        return loadSettings().then(function () {
          if (kind === "modulewall") renderModules();
          return name;
        });
      })
      .catch(function (error) { notify("清除失败：" + (error.message || "未知错误"), true); });
  }

  function assetFieldRange(id, label, min, max, step, value, unit) {
    return '<label class="field"><span>' + label + '</span>' +
      '<input type="range" id="' + id + '" min="' + min + '" max="' + max + '" step="' + step +
      '" value="' + value + '">' +
      '<output id="' + id + 'Value">' + value + unit + '</output></label>';
  }

  function openAssetAdjust(key) {
    var parts = key.split("/");
    var kind = parts[0], name = parts[1];
    var meta = assetMeta(kind, name) || {};
    state.assetAdjustTarget = { kind: kind, name: name };
    var imageUrl = assetUrl(kind, name);
    var body, foot = '<button class="btn small primary" type="button" id="assetAdjustApply">保存裁剪</button>' +
      '<button class="btn small" type="button" data-kpm-dialog="close">关闭</button>';
    if (kind !== "navicon") {
      body = '<div class="asset-crop-preview wallpaper"><span id="assetCropImage" class="asset-crop-image" style="background-image:url(\'' + imageUrl + '\')"></span>' +
        '<span id="assetCropDim" class="asset-crop-dim"></span><span class="asset-crop-frame"></span></div>' +
        '<label class="field"><span>填充方式</span><select id="assetFit">' +
        '<option value="cover">填充裁剪</option>' +
        '<option value="contain">完整显示</option>' +
        '<option value="stretch">拉伸</option></select></label>' +
        assetFieldRange("assetScale", "裁剪缩放", 50, 300, 5, Math.round((meta.scale || 1) * 100), "%") +
        assetFieldRange("assetOffsetX", "水平移动", -50, 50, 1, Math.round(meta.offsetX || 0), "%") +
        assetFieldRange("assetOffsetY", "垂直移动", -50, 50, 1, Math.round(meta.offsetY || 0), "%") +
        assetFieldRange("assetDim", "变暗", 0, 85, 5, Math.round((meta.dim === undefined ? 0.35 : meta.dim) * 100), "%") +
        assetFieldRange("assetBlur", "模糊", 0, 12, 1, Math.round(meta.blur || 0), "px");
      openSheet("裁剪壁纸 · " + assetLabel(kind, name), body, foot);
      el("assetFit").value = meta.fit || "cover";
    } else {
      body = '<div class="asset-crop-preview icon"><span id="assetCropImage" class="asset-crop-image icon" style="background-image:url(\'' + imageUrl + '\')"></span></div>' +
        assetFieldRange("assetScale", "缩放", 50, 300, 5, Math.round((meta.scale || 1) * 100), "%") +
        assetFieldRange("assetOffsetX", "水平移动", -12, 12, 1, Math.round(meta.offsetX || 0), "px") +
        assetFieldRange("assetOffsetY", "垂直移动", -12, 12, 1, Math.round(meta.offsetY || 0), "px");
      openSheet("裁剪图标 · " + assetLabel(kind, name), body, foot);
    }
    ["assetScale", "assetOffsetX", "assetOffsetY", "assetDim", "assetBlur"].forEach(function (id) {
      var input = el(id);
      if (!input) return;
      input.addEventListener("input", function () {
        var unit = id === "assetBlur" || (kind === "navicon" && (id === "assetOffsetX" || id === "assetOffsetY")) ? "px" : "%";
        el(id + "Value").textContent = input.value + unit;
        previewAssetAdjust();
      });
    });
    var fit = el("assetFit");
    if (fit) fit.addEventListener("change", previewAssetAdjust);
    previewAssetAdjust();
  }

  function assetAdjustMeta() {
    var kind = state.assetAdjustTarget ? state.assetAdjustTarget.kind : "";
    var meta = {};
    if (kind !== "navicon") {
      meta.fit = el("assetFit") ? el("assetFit").value : "cover";
      meta.scale = Number(el("assetScale").value) / 100;
      meta.offsetX = Number(el("assetOffsetX").value);
      meta.offsetY = Number(el("assetOffsetY").value);
      meta.dim = Number(el("assetDim").value) / 100;
      meta.blur = Number(el("assetBlur").value);
    } else {
      meta.scale = Number(el("assetScale").value) / 100;
      meta.offsetX = Number(el("assetOffsetX").value);
      meta.offsetY = Number(el("assetOffsetY").value);
    }
    return meta;
  }

  /** 滑块拖动时立刻在当前页面上预览，保存后才会写回服务端。 */
  function previewAssetAdjust() {
    var target = state.assetAdjustTarget;
    if (!target) return;
    var meta = assetAdjustMeta();
    var cropImage = el("assetCropImage");
    var cropDim = el("assetCropDim");
    if (target.kind === "navicon") {
      if (cropImage) cropImage.style.transform = "translate(" + meta.offsetX + "px," + meta.offsetY + "px) scale(" + meta.scale + ")";
      var view = NAV_ICON_VIEWS[target.name] || target.name;
      document.querySelectorAll('.navbar button[data-view="' + view + '"] .ico, .tabs button[data-view="' + view + '"] .ico').forEach(function (icon) {
        icon.style.setProperty("--nav-scale", String(meta.scale));
        icon.style.setProperty("--nav-offset-x", String(meta.offsetX) + "px");
        icon.style.setProperty("--nav-offset-y", String(meta.offsetY) + "px");
      });
      return;
    }
    if (cropImage && cropDim) applyWallpaperStyles(cropImage, cropDim, meta, null);
    var host = target.kind === "wallpaper" ? el(WALLPAPER_HOSTS[target.name]) : null;
    if (target.kind === "modulewall") {
      document.querySelectorAll("article.item[data-module-id]").forEach(function (card) {
        if (card.getAttribute("data-module-id") === target.name) host = card;
      });
    }
    if (!host) return;
    var layer = host.querySelector(".bg-layer");
    var dim = host.querySelector(".bg-dim");
    if (!layer || !dim) return;
    applyWallpaperStyles(layer, dim, meta, null);
  }

  function saveAssetAdjust() {
    var target = state.assetAdjustTarget;
    if (!target) return;
    var meta = assetAdjustMeta();
    kpmPost("/api/settings/asset-meta", { kind: target.kind, name: target.name, meta: meta }, 30000)
      .then(function () {
        notify("外观参数已保存");
        closeSheet();
        return loadSettings().then(function () {
          if (target.kind === "modulewall") renderModules();
          return target.name;
        });
      })
      .catch(function (error) { notify("保存失败：" + (error.message || "未知错误"), true); });
  }

  // -------------------------------------------------------------------- KPM

  /**
   * 与原生「KPM 管理」页同源：能力/列表/策略/启用/加载/控制/删除/排除全部走
   * /api/kpm 系列端点，服务端再用 ksud kpm 命令落到内核。
   */
  var KPM_BACKENDS = {
    "native-gki": "Native GKI KPM 运行时",
    "native_gki": "Native GKI KPM 运行时",
    "kpatch-next": "KPatch-Next 运行时",
    "kpatch_next": "KPatch-Next 运行时",
    "none": "不可用",
    "": "不可用"
  };

  function kpmBackendKey(caps) {
    return String((caps && caps.backend) || "").trim().toLowerCase();
  }

  function formatBytes(value) {
    var bytes = Number(value) || 0;
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(bytes % (1024 * 1024) ? 1 : 0) + " MiB";
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KiB";
    return bytes + " B";
  }

  function kpmPost(path, payload, timeout) {
    return api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload || {}),
      timeout: timeout || 90000
    });
  }

  function loadKpm(force) {
    el("kpmError").classList.add("hidden");
    return api("/api/kpm" + (force ? "?refresh=1" : ""), { timeout: 60000 }).then(function (data) {
      state.kpm = data;
      state.kpmLoaded = true;
      renderKpm();
      return true;
    }).catch(function (error) {
      var text = (error.message || "无法读取 KPM 状态") + (error.code ? " [" + error.code + "]" : "");
      el("kpmErrorText").textContent = state.kpmLoaded ? text + " · 显示上次读取的数据" : text;
      el("kpmError").classList.remove("hidden");
      el("tabKpm").textContent = "-";
      if (!state.kpmLoaded) {
        el("kpmEntries").innerHTML = '<div class="state"><b>无法读取 KPM 状态</b>' + esc(text) +
          '<button class="btn small" type="button" data-retry="kpm">重试</button></div>';
      }
      return false;
    });
  }

  function kpmNotice(title, message, danger) {
    return '<div class="notice' + (danger ? " danger" : "") + '"><span><b>' + esc(title) + '</b> · ' +
      esc(message) + '</span></div>';
  }

  function renderKpm() {
    var data = state.kpm;
    if (!data) return;
    var caps = data.caps || {};
    var backend = kpmBackendKey(caps);
    var entries = Array.isArray(data.entries) ? data.entries : [];
    el("tabKpm").textContent = state.kpmLoaded ? String(entries.length) : "-";

    el("kpmBackend").textContent = (KPM_BACKENDS[backend] || ("后端：" + (backend || "未知"))) +
      (caps.managementAvailable ? " · 可管理" : " · 当前不可管理");
    el("kpmBackendTag").textContent = caps.supported
      ? "已启用"
      : (caps.kernelSupported ? "内核支持" : "不可用");
    el("kpmBackendTag").className = "tag " + (caps.supported ? "ok" : (caps.kernelSupported ? "warn" : "danger"));

    var capsParts = [];
    if (Number(caps.abiVersion) > 0) capsParts.push("ABI v" + Number(caps.abiVersion));
    if (Number(caps.maxLoaded) > 0) capsParts.push("最多加载 " + Number(caps.maxLoaded) + " 个");
    if (Number(caps.maxImageSize) > 0) capsParts.push("单文件上限 " + formatBytes(caps.maxImageSize));
    capsParts.push("已导入 " + entries.length + " 个");
    el("kpmCapsDetail").textContent = capsParts.join(" · ");

    el("kpmPolicy").checked = !!caps.policyEnabled;
    el("kpmPolicy").disabled = !caps.managementAvailable || !!caps.lateLoad;
    el("kpmPolicyDetail").textContent = caps.policyEnabled
      ? "允许加载受信任的 KPM 代码，并在启动时恢复已启用模块"
      : "KPM 加载已关闭，已导入文件与逐模块配置会保留";

    el("kpmImportDetail").textContent = Number(caps.maxImageSize) > 0
      ? "选择受信任的 AArch64 可重定位 KPM 文件 · 上限 " + formatBytes(caps.maxImageSize)
      : "选择受信任的 AArch64 可重定位 KPM 文件";
    el("kpmImportPick").disabled = !caps.managementAvailable || !!caps.lateLoad;

    var excludedCount = Array.isArray(data.excluded) ? data.excluded.length : 0;
    el("kpmExcludeDetail").textContent = backend === "kpatch-next"
      ? "已排除 " + excludedCount + " 个应用 · 被排除的 UID 不执行 KPM 的进程级 Hook"
      : "被排除的 UID 不会执行 KPM 的进程级 Hook（当前后端无排除表）";
    el("kpmExcludeOpen").disabled = backend !== "kpatch-next";

    var notices = [];
    if (data.error) notices.push(["无法读取 KPM 状态", data.error, true]);
    if (caps.lateLoad) {
      notices.push(["越狱模式已禁用", "越狱/后加载模式下 KPM 管理已禁用。", false]);
    } else if (!caps.managementAvailable) {
      notices.push(["KPM 管理不可用", "当前内核未提供可用的 KPM 后端。", false]);
    } else if (!caps.loaderReady) {
      notices.push([
        "Native GKI 加载器未附着",
        "内核提供了 Native KPM ABI，但启动镜像没有附着兼容的加载器；导入与策略会保留，请刷入带 KPM 加载器的镜像后再加载。",
        false
      ]);
    } else if (!caps.kernelSupported) {
      notices.push(["KPM 管理不可用", "当前内核未提供可用的 KPM 后端，重启后可能需要重新激活运行时。", false]);
    }
    if (caps.managementAvailable && !caps.lateLoad && !caps.policyEnabled) {
      notices.push(["KPM 加载已关闭", "请先打开 KPM 加载开关，再导入、启用或加载内核代码。", false]);
    }
    el("kpmNotices").innerHTML = notices.map(function (item) {
      return kpmNotice(item[0], item[1], item[2]);
    }).join("");

    renderKpmEntries();
    renderKpmExcludeList();
  }

  function renderKpmEntries() {
    var data = state.kpm;
    if (!data) return;
    var caps = data.caps || {};
    var query = (state.kpmQuery || "").trim().toLowerCase();
    var entries = (Array.isArray(data.entries) ? data.entries : []).filter(function (entry) {
      if (!query) return true;
      return (String(entry.name || "") + " " + String(entry.id || "")).toLowerCase().indexOf(query) >= 0;
    });
    if (!entries.length) {
      el("kpmEntries").innerHTML = '<div class="state"><b>' +
        (query ? "没有匹配的 KPM" : "尚未导入 KPM") + '</b>' +
        esc(query ? "换个关键词试试。" : "导入受信任的 AArch64 可重定位 KPM 后可以在这里管理。") + '</div>';
      return;
    }
    var runtimeEnabled = !!(caps.supported && caps.loaderReady && caps.policyEnabled && !caps.lateLoad);
    var managementEnabled = !!(caps.managementAvailable && caps.policyEnabled && !caps.lateLoad);
    el("kpmEntries").innerHTML = entries.map(function (entry) {
      var id = esc(entry.id);
      var name = esc(entry.name || entry.id);
      var tags = [];
      tags.push(entry.loaded
        ? '<span class="tag ok">已加载到内核</span>'
        : (entry.enabled ? '<span class="tag accent">已启用，启动时加载</span>' : '<span class="tag">已禁用</span>'));
      if (!entry.runtimeKnown) tags.push('<span class="tag warn">运行时状态不可查询</span>');
      if (entry.quarantined) tags.push('<span class="tag danger">已隔离</span>');
      var meta = [];
      if (entry.version) meta.push(esc(entry.version));
      if (entry.author) meta.push(esc(entry.author));
      if (entry.args) meta.push("参数：" + esc(entry.args));
      if (entry.importedAt) meta.push("导入于 " + esc(entry.importedAt));
      var buttons = [];
      buttons.push('<button class="btn small" type="button" data-kpm="' + (entry.enabled ? "disable" : "enable") +
        '" data-id="' + id + '" data-name="' + name + '"' + (managementEnabled ? "" : " disabled") + '>' +
        (entry.enabled ? "禁用" : "启用") + '</button>');
      // 与原生一致：已加载时可卸载/控制；未加载时只有「启用且未隔离」才允许加载
      if (entry.loaded) {
        buttons.push('<button class="btn small" type="button" data-kpm="unload" data-id="' + id +
          '" data-name="' + name + '">卸载</button>');
        buttons.push('<button class="btn small" type="button" data-kpm="control" data-id="' + id +
          '" data-name="' + name + '" data-args="' + esc(entry.args || "") + '"' +
          (runtimeEnabled ? "" : " disabled") + '>控制</button>');
      } else {
        var loadEnabled = runtimeEnabled && entry.enabled && !entry.quarantined;
        buttons.push('<button class="btn small" type="button" data-kpm="load" data-id="' + id +
          '" data-name="' + name + '"' + (loadEnabled ? "" : " disabled") + '>加载</button>');
      }
      buttons.push('<button class="btn small danger" type="button" data-kpm="remove" data-id="' + id +
        '" data-name="' + name + '">删除</button>');
      return '<div class="item">' +
        '<div class="item-main">' +
        '<div class="item-title"><span class="name">' + name + '</span>' + tags.join("") + '</div>' +
        '<div class="item-meta mono">' + id + (meta.length ? " · " + meta.join(" · ") : "") + '</div>' +
        (entry.description ? '<div class="item-desc">' + esc(entry.description) + '</div>' : '') +
        (entry.quarantined && entry.quarantineReason
          ? '<div class="item-desc" style="color:var(--danger)">已隔离：' + esc(entry.quarantineReason) + '</div>'
          : '') +
        '</div>' +
        '<div class="item-actions">' + buttons.join("") + '</div>' +
        '</div>';
    }).join("");
  }

  function renderKpmExcludeList() {
    var data = state.kpm;
    if (!data) return;
    if (!state.appLoaded) {
      el("kpmExcludeList").innerHTML = '<div class="state">正在读取应用列表……</div>';
      return;
    }
    var excluded = {};
    (Array.isArray(data.excluded) ? data.excluded : []).forEach(function (item) {
      excluded[String(item.package)] = true;
    });
    var query = (state.kpmExcludeQuery || "").trim().toLowerCase();
    var apps = (state.apps || []).filter(function (app) {
      if (!query) return true;
      return (String(app.label || "") + " " + String(app.packageName || "")).toLowerCase().indexOf(query) >= 0;
    });
    if (!apps.length) {
      el("kpmExcludeList").innerHTML = '<div class="state">没有找到已安装的应用。</div>';
      return;
    }
    el("kpmExcludeList").innerHTML = apps.map(function (app) {
      var pkg = String(app.packageName || "");
      var isExcluded = !!excluded[pkg];
      var uid = Number(app.uid);
      if (!pkg || !(uid > 0)) return '';
      return '<div class="item">' +
        '<div class="item-main">' +
        '<div class="item-title"><span class="name">' + esc(app.label || pkg) + '</span>' +
        (isExcluded ? '<span class="tag ok">已排除</span>' : '') + '</div>' +
        '<div class="item-meta mono">' + esc(pkg) + ' · UID ' + uid + '</div>' +
        '</div>' +
        '<div class="item-actions">' +
        '<button class="btn small" type="button" data-kpm-exclude="' + esc(pkg) + '" data-uid="' + uid +
        '" data-enabled="' + (isExcluded ? "0" : "1") + '">' +
        (isExcluded ? "取消排除" : "排除") + '</button>' +
        '</div></div>';
    }).join("");
  }

  function runKpmAction(action, id, name, args) {
    var payload = { action: action, id: id };
    if (action === "control") payload.args = args || "";
    return kpmPost("/api/kpm/action", payload).then(function (result) {
      if (result.success) {
        notify((name || id) + "：" + (result.output || "操作已完成"));
        record("KPM " + action + "：" + id);
      } else {
        notify((name || id) + " 操作失败：" + (result.error || "未知错误"), true);
      }
      return loadKpm(true).then(function () { return result; });
    }).catch(function (error) {
      notify("KPM 操作失败：" + (error.message || "未知错误"), true);
      throw error;
    });
  }

  function kpmToggleExclude(pkg, uid, enabled) {
    return kpmPost("/api/kpm/exclude", { package: pkg, uid: uid, enabled: enabled }).then(function (result) {
      if (result.success) {
        notify(pkg + (enabled ? " 已加入排除表" : " 已从排除表移除"));
        record("KPM 排除：" + pkg + (enabled ? "" : "（取消）"));
      } else {
        notify("排除操作失败：" + (result.error || "未知错误"), true);
      }
      return loadKpm(true);
    }).catch(function (error) {
      notify("排除操作失败：" + (error.message || "未知错误"), true);
      throw error;
    });
  }

  function openSheet(title, bodyHtml, footHtml) {
    el("kpmDialogTitle").textContent = title;
    el("kpmDialogBody").innerHTML = bodyHtml;
    el("kpmDialogFoot").innerHTML = footHtml;
    el("kpmDialog").classList.remove("hidden");
  }

  function closeSheet() {
    state.rebootMenuRequest += 1;
    var restoreAssets = state.assetAdjustTarget !== null;
    state.assetAdjustTarget = null;
    el("kpmDialog").classList.add("hidden");
    el("kpmDialogBody").innerHTML = "";
    el("kpmDialogFoot").innerHTML = "";
    state.kpmControlTarget = null;
    state.kpmRemoveTarget = null;
    state.kpmPendingFile = null;
    if (restoreAssets) applyAssets();
  }

  function openKpmControlDialog(id, name, args) {
    state.kpmControlTarget = { id: id, name: name };
    openSheet(
      "控制 " + name,
      '<div class="row"><div class="row-main"><div class="row-title">回调参数</div>' +
        '<div class="row-detail">会以 ksud kpm control &lt;id&gt; --args 传给模块控制回调。</div></div></div>' +
        '<textarea id="kpmControlArgs" class="search" rows="3" style="width:100%;margin-top:10px" ' +
        'aria-label="回调参数">' + esc(args || "") + '</textarea>' +
        '<pre id="kpmControlOutput" class="card mono hidden" style="margin-top:10px;padding:11px 12px;' +
        'white-space:pre-wrap;font-size:12.5px;max-height:36vh;overflow:auto"></pre>',
      '<button class="btn small" type="button" id="kpmControlRun">执行回调</button>' +
        '<button class="btn small" type="button" data-kpm-dialog="close">关闭</button>'
    );
  }

  function runKpmControl() {
    var target = state.kpmControlTarget;
    if (!target) return;
    var args = el("kpmControlArgs") ? el("kpmControlArgs").value : "";
    var runButton = el("kpmControlRun");
    if (runButton) { runButton.disabled = true; }
    var output = el("kpmControlOutput");
    if (output) {
      output.classList.remove("hidden");
      output.textContent = "正在执行回调……";
    }
    kpmPost("/api/kpm/action", { action: "control", id: target.id, args: args }, 120000)
      .then(function (result) {
        var text = result.success
          ? (result.output || "回调没有返回内容。")
          : ("执行失败：" + (result.error || "未知错误"));
        if (output) output.textContent = text;
        notify(result.success ? "回调已执行" : "回调执行失败", !result.success);
        record("KPM 控制：" + target.id);
      })
      .catch(function (error) {
        if (output) output.textContent = "执行失败：" + (error.message || "未知错误");
        notify("回调执行失败：" + (error.message || "未知错误"), true);
      })
      .then(function () {
        if (runButton && runButton.isConnected) runButton.disabled = false;
      });
  }

  function openKpmRemoveDialog(id, name) {
    state.kpmRemoveTarget = { id: id, name: name };
    openSheet(
      "删除 KPM？",
      '<div class="row"><div class="row-main"><div class="row-title">' + esc(name) + '</div>' +
        '<div class="row-detail">将删除 ' + esc(id) + ' 及其保存的镜像；如果模块正在运行会先卸载。</div></div></div>',
      '<button class="btn danger" type="button" id="kpmRemoveConfirm">删除</button>' +
        '<button class="btn small" type="button" data-kpm-dialog="close">取消</button>'
    );
  }

  function openKpmImportDialog(file) {
    state.kpmPendingFile = file;
    openSheet(
      "导入 KPM",
      '<div class="row"><div class="row-main"><div class="row-title">' + esc(file.name) + '</div>' +
        '<div class="row-detail">' + formatBytes(file.size) + '</div></div></div>' +
        '<div class="notice danger" style="margin-top:10px"><span>KPM 是可执行的内核代码，' +
        '请先确认来源和代码，再继续导入。</span></div>' +
        '<label class="label" style="display:block;margin-top:10px">参数（可选）</label>' +
        '<textarea id="kpmImportArgs" class="search" rows="2" style="width:100%;margin-top:6px" ' +
        'aria-label="导入参数"></textarea>' +
        '<label class="switch" style="margin-top:12px"><input type="checkbox" id="kpmTrust"><span>我理解风险，并信任这段内核代码</span></label>' +
        '<label class="switch" style="margin-top:8px;display:flex"><input type="checkbox" id="kpmEnableAfter"><span>导入后启用并加载</span></label>' +
        '<label class="switch" style="margin-top:8px;display:flex"><input type="checkbox" id="kpmReplace"><span>替换同名的已有 KPM</span></label>',
      '<button class="btn primary" type="button" id="kpmImportConfirm" disabled>导入</button>' +
        '<button class="btn small" type="button" data-kpm-dialog="close">取消</button>'
    );
    el("kpmTrust").addEventListener("change", function (event) {
      el("kpmImportConfirm").disabled = !event.target.checked;
    });
  }

  function runKpmImport() {
    var file = state.kpmPendingFile;
    if (!file) return;
    var args = el("kpmImportArgs") ? el("kpmImportArgs").value : "";
    var force = el("kpmReplace") && el("kpmReplace").checked;
    var enable = el("kpmEnableAfter") && el("kpmEnableAfter").checked;
    var confirmButton = el("kpmImportConfirm");
    if (confirmButton) confirmButton.disabled = true;
    var url = "/api/kpm/import?name=" + encodeURIComponent(file.name) +
      "&args=" + encodeURIComponent(args) +
      "&force=" + (force ? "1" : "0") +
      "&enable=" + (enable ? "1" : "0");
    api(url, {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body: file,
      timeout: 180000
    }).then(function (result) {
      if (result.success) {
        notify("导入完成：" + (result.output || file.name));
        record("KPM 导入：" + file.name);
        closeSheet();
      } else {
        notify("导入失败：" + (result.error || "未知错误"), true);
        if (confirmButton) confirmButton.disabled = false;
      }
      return loadKpm(true);
    }).catch(function (error) {
      notify("导入失败：" + (error.message || "未知错误"), true);
      if (confirmButton && confirmButton.isConnected) confirmButton.disabled = false;
    });
  }

  /** 排除名单要用已安装应用做候选，进入面板时确保应用列表已加载。 */
  function openKpmExcludePanel() {
    el("kpmExcludePanel").classList.remove("hidden");
    renderKpmExcludeList();
    if (!state.appLoaded && !state.appLoading) loadSuperusers(false);
  }

  // --------------------------------------------------------------- 数据加载

  function loadStatus() {
    return api("/api/status", { timeout: 20000 }).then(function (data) {
      state.status = data;
      renderStatusCards();
      return true;
    }).catch(function (error) {
      setPill("读取失败", "err");
      record("读取状态失败：" + (error.message || "未知错误"), true);
      return false;
    });
  }

  function loadDevice(force) {
    el("infoManager").textContent = "读取中……";
    return api("/api/device" + (force ? "?refresh=1" : ""), { timeout: 45000 }).then(function (data) {
      state.device = data;
      renderDeviceCard();
      record(state.device && state.device.kernel
        ? "内核模式：" + ((KERNEL_MODES[state.device.kernel.mode] || KERNEL_MODES.unknown).label)
        : "已读取设备信息");
      return true;
    }).catch(function (error) {
      el("infoManager").textContent = "读取失败：" + (error.message || "未知错误");
      el("kernelState").textContent = "驱动未连接";
      el("lkmCard").dataset.tone = "err";
      el("lkmBubble").textContent = "✕";
      record("读取设备信息失败：" + (error.message || "未知错误"), true);
      return false;
    });
  }

  function loadModules(force) {
    el("moduleError").classList.add("hidden");
    return api("/api/modules" + (force ? "?refresh=1" : ""), { timeout: 25000 }).then(function (data) {
      state.modules = Array.isArray(data.modules) ? data.modules : [];
      state.moduleLoaded = true;
      renderModules();
      renderModuleCard();
      return true;
    }).catch(function (error) {
      var text = (error.message || "无法读取模块") + (error.code ? " [" + error.code + "]" : "");
      el("moduleErrorText").textContent = state.moduleLoaded ? text + " · 显示上次读取的数据" : text;
      el("moduleError").classList.remove("hidden");
      if (!state.moduleLoaded) {
        el("modules").innerHTML = '<div class="state"><b>无法读取模块</b>' + esc(error.message || "未知错误") +
          '<button class="btn small" type="button" data-retry="modules">重试</button></div>';
        el("metricModule").textContent = "-";
        el("metricModuleSub").textContent = "读取失败 · 可在设置页运行诊断";
      }
      record("读取模块失败：" + (error.message || "未知错误"), true);
      return false;
    });
  }

  function loadSuperusers(force) {
    if (state.appLoading) return Promise.resolve(false);
    state.appLoading = true;
    renderSuperusers();
    el("superuserError").classList.add("hidden");
    return api("/api/superuser" + (force ? "?refresh=1" : ""), { timeout: 60000 })
      .then(function (data) {
        state.apps = Array.isArray(data.apps) ? data.apps : [];
        state.appStats = {
          uidCount: Number(data.uidCount || state.apps.length),
          totalApps: Number(data.totalApps || state.apps.length),
          authorizedCount: Number(data.authorizedCount || 0),
          sharedUidCount: Number(data.sharedUidCount || 0),
          source: String(data.source || "")
        };
        state.appLoaded = true;
        state.summaryLoaded = true;
        renderSuperusers();
        renderSuperuserCard();
        return true;
      })
      .catch(function (error) {
        var text = (error.message || "无法读取应用列表") + (error.code ? " [" + error.code + "]" : "");
        el("superuserErrorText").textContent = state.appLoaded ? text + " · 显示上次读取的数据" : text;
        el("superuserError").classList.remove("hidden");
        if (!state.appLoaded) {
          el("superusers").innerHTML = '<div class="state"><b>无法读取应用列表</b>' + esc(error.message || "未知错误") +
            '<button class="btn small" type="button" data-retry="superuser">重试</button></div>';
          el("metricSuperuser").textContent = "-";
          el("metricSuperuserSub").textContent = "读取失败 · 可在设置页运行诊断";
        }
        record("读取应用列表失败：" + (error.message || "未知错误"), true);
        return false;
      })
      .finally(function () {
        state.appLoading = false;
        renderSuperusers();
      });
  }
"""

/** 页面脚本下半部分。 */
private const val WEB_MANAGER_PAGE_SCRIPT_TOOLS: String = """  // -------------------------------------------------------- 设置分类折叠状态

  var CAT_STORAGE_KEY = "wm_settings_categories";

  function catKey(index) {
    return "cat" + index;
  }

  function loadCatState() {
    try {
      return JSON.parse(localStorage.getItem(CAT_STORAGE_KEY) || "{}") || {};
    } catch (_) {
      return {};
    }
  }

  function saveCatState(state) {
    try {
      localStorage.setItem(CAT_STORAGE_KEY, JSON.stringify(state));
    } catch (_) {
      /* 隐私模式下写不进去就算了，下次仍默认收起 */
    }
  }

  /** 分类默认收起，展开状态记在 localStorage，下次进来保持一致。 */
  function bindCategories() {
    var cats = document.querySelectorAll("details.cat");
    var saved = loadCatState();
    cats.forEach(function (cat, index) {
      var key = catKey(index);
      cat.open = saved[key] === true;
      cat.addEventListener("toggle", function () {
        var state = loadCatState();
        state[key] = cat.open;
        saveCatState(state);
      });
    });
    var expand = el("expandCats");
    var collapse = el("collapseCats");
    if (expand) {
      expand.addEventListener("click", function () {
        var state = {};
        document.querySelectorAll("details.cat").forEach(function (cat, index) {
          cat.open = true;
          state[catKey(index)] = true;
        });
        saveCatState(state);
      });
    }
    if (collapse) {
      collapse.addEventListener("click", function () {
        document.querySelectorAll("details.cat").forEach(function (cat) { cat.open = false; });
        saveCatState({});
      });
    }
  }

  // -------------------------------------------------- 管理器自身（图标 / 端口）

  function renderLauncher(launcher) {
    var toggle = el("launcherHidden");
    if (!toggle || !launcher) return;
    toggle.checked = !!launcher.hidden;
    el("launcherDetail").textContent = launcher.hidden
      ? "桌面图标已隐藏，管理器只能从本控制台进入；关掉本开关即可恢复（当前图标：" + (launcher.label || launcher.option) + "）。"
      : "隐藏后桌面不再显示管理器图标，管理器只能从本控制台进入（APK 仍在、服务照常运行）。当前图标：" + (launcher.label || launcher.option) + "。";
  }

  function renderPortSettings(settings) {
    var select = el("portMode");
    if (!select) return;
    var mode = settings.portMode === "fixed" ? "fixed" : "random";
    select.value = mode;
    var input = el("fixedPortInput");
    if (input) input.value = settings.fixedPort ? String(settings.fixedPort) : "";
    el("portDetailCurrent").textContent = "127.0.0.1:" + (settings.port || "—") +
      "（仅本机访问）" + (mode === "fixed" ? " · 已设固定端口，重启服务后使用" : " · 随机端口，每次启动可能变化");
  }

  // -------------------------------------------------------- 工具箱（原生同款工具）

  function loadTools() {
    return api("/api/tools", { timeout: 40000 }).then(function (data) {
      state.tools = data || {};
      state.toolsLoaded = true;
      renderTools();
      return state.tools;
    }).catch(function (error) {
      state.toolsLoaded = false;
      ["builtinMountBody", "kpatchBody", "pathmaskBody", "cpuSpoofBody"].forEach(function (nodeId) {
        el(nodeId).innerHTML = '<div class="state error">工具状态读取失败：' +
          esc(error.message || "未知错误") + '</div>';
      });
      throw error;
    });
  }

  function renderTools() {
    var tools = state.tools || {};
    renderBuiltinMount(tools.builtinMount);
    renderKPatch(tools.kpatchNext);
    renderPathmask(tools.pathmask);
    renderCpuSpoof(tools.cpuSpoof);
    renderLanguage(tools.language);
  }

  function toolRow(title, detail, control) {
    return '<div class="row"><div class="row-main"><div class="row-title">' + esc(title) + '</div>' +
      '<div class="row-detail">' + detail + '</div></div>' + (control || "") + "</div>";
  }

  function toolCheckbox(attr, key, value) {
    return '<input type="checkbox" ' + attr + '="' + esc(key) + '"' + (value ? " checked" : "") +
      ' aria-label="' + esc(key) + '">';
  }

  function truthText(value) {
    return value ? "是" : "否";
  }

  function renderBuiltinMount(mount) {
    var node = el("builtinMountBody");
    if (!node) return;
    if (!mount || mount.available === false) {
      node.innerHTML = '<div class="state">读取不到内置挂载状态（ksud 不可用？）</div>';
      return;
    }
    var rows = toolRow("安装状态", "版本 " + esc(mount.version || "未知") + " · " + (mount.installed ? "已安装" : "未安装"), "");
    rows += toolRow("启用", mount.enabled ? "已启用" : "未启用",
      toolCheckbox("data-tool-toggle", "builtinMount", mount.enabled));
    rows += '<div class="row"><div class="row-main"><div class="row-title">模式</div>' +
      '<div class="row-detail">overlay 走 OverlayFS，magic 走 magic mount</div></div>' +
      '<select data-tool-select="builtinMountMode" aria-label="内置挂载模式">' +
      ['overlay', 'magic'].map(function (mode) {
        return '<option value="' + mode + '"' + (mount.mode === mode ? " selected" : "") + ">" + mode + "</option>";
      }).join("") + "</select></div>";
    rows += '<div class="row"><div class="row-main"><div class="row-title">变体</div>' +
      '<div class="row-detail">lite 轻量 / full 完整</div></div>' +
      '<select data-tool-select="builtinMountVariant" aria-label="内置挂载变体">' +
      ['lite', 'full'].map(function (variant) {
        return '<option value="' + variant + '"' + (mount.variant === variant ? " selected" : "") + ">" + variant + "</option>";
      }).join("") + "</select></div>";
    if (mount.conflict) {
      rows += '<div class="tool-hint">冲突：' + esc(String(mount.conflict)) + "</div>";
    }
    node.innerHTML = rows;
  }

  function renderKPatch(kpatch) {
    var node = el("kpatchBody");
    if (!node) return;
    if (!kpatch || kpatch.available === false) {
      node.innerHTML = '<div class="state">读取不到 KPatch-Next 状态（ksud 不可用？）</div>';
      return;
    }
    var rows = toolRow("安装状态", (kpatch.installed ? "已安装" : "未安装") +
      (kpatch.version ? " · 版本 " + esc(kpatch.version) : ""), "");
    rows += toolRow("启用", kpatch.enabled ? "已启用" : "未启用", toolCheckbox("data-tool-toggle", "kpatch", kpatch.enabled));
    if (kpatch.pendingUpdate) rows += toolRow("待更新", "重启后应用新版本", "");
    if (kpatch.pendingRemove) rows += toolRow("待移除", "重启后移除", "");
    if (kpatch.unresolved) rows += toolRow("解析失败", "模块目录不完整，建议重装", "");
    if (kpatch.conflict) rows += '<div class="tool-hint">冲突：' + esc(String(kpatch.conflict)) + "</div>";
    if (kpatch.error) rows += '<div class="tool-hint">错误：' + esc(String(kpatch.error)) + "</div>";
    node.innerHTML = rows;
  }

  function renderPathmask(pathmask) {
    var node = el("pathmaskBody");
    if (!node) return;
    if (!pathmask || pathmask.available === false) {
      node.innerHTML = '<div class="state">读取不到隐藏路径状态（需要 pathmask LKM 与 ksud）</div>';
      return;
    }
    var rows = toolRow("运行状态", "阶段 " + esc(pathmask.phase || "未知") +
      " · " + (pathmask.loaded ? "已加载" : "未加载") +
      (pathmask.currentKmi ? " · KMI " + esc(pathmask.currentKmi) : ""), "");
    rows += toolRow("自动加载", pathmask.autoLoadEnabled
      ? "开机自动应用" + (pathmask.autoLoadDelaySeconds ? "（延迟 " + pathmask.autoLoadDelaySeconds + " 秒）" : "")
      : "未开启", toolCheckbox("data-tool-toggle", "pathmaskAutoLoad", pathmask.autoLoadEnabled));
    rows += toolRow("路径统计", "已保存 " + pathmask.savedCount + " 条 · 已激活 " + pathmask.activeCount +
      " 条 · 已解析 " + pathmask.resolvedCount + " 条" +
      (pathmask.unresolvedTargetCount ? " · 未解析 " + pathmask.unresolvedTargetCount + " 条" : ""), "");
    var targets = (pathmask.targetPaths || []).slice(0, 12);
    if (targets.length) {
      rows += '<div class="tool-hint">目标路径：' + targets.map(function (path) {
        return "<span class=\"mono\">" + esc(path) + "</span>";
      }).join(" · ") + ((pathmask.targetPaths.length > targets.length) ? " 等 " + pathmask.targetPaths.length + " 条" : "") + "</div>";
    }
    if (pathmask.requiresReboot) rows += '<div class="tool-hint">需要重启后生效</div>';
    if (pathmask.requiresReload) rows += '<div class="tool-hint">需要重新应用配置</div>';
    if (pathmask.lastErrorMessage) {
      rows += '<div class="tool-hint">最近错误：' + esc(pathmask.lastErrorCode ? pathmask.lastErrorCode + " " : "") +
        esc(pathmask.lastErrorMessage) + "</div>";
    }
    rows += '<div class="row"><div class="row-main"><div class="row-title">操作</div>' +
      '<div class="row-detail">应用=按已保存配置热重载；卸载=清空内核隐藏路径；删除=删除保存的配置</div></div>' +
      '<div class="row-actions">' +
      '<button class="btn small" type="button" data-tool-action="pathmask-logs">日志</button>' +
      '<button class="btn small" type="button" data-tool-action="pathmask-apply">应用</button>' +
      '<button class="btn small" type="button" data-tool-action="pathmask-unload">卸载</button>' +
      '<button class="btn small danger" type="button" data-tool-action="pathmask-delete">删除配置</button>' +
      "</div></div>";
    node.innerHTML = rows;
  }

  function renderCpuSpoof(cpu) {
    var node = el("cpuSpoofBody");
    if (!node) return;
    if (!cpu || cpu.available === false) {
      node.innerHTML = '<div class="state">读取不到 CPU 伪装状态（ksud 不可用？）</div>';
      return;
    }
    if (!cpu.supported) {
      node.innerHTML = '<div class="state">当前内核/环境不支持 CPU 伪装' +
        (cpu.error ? "：" + esc(String(cpu.error)) : "") + "</div>";
      return;
    }
    var rows = toolRow("运行状态", (cpu.enabled ? "已启用" : "未启用") + " · " +
      (cpu.applied ? "已生效" : "未生效") + (cpu.configured ? " · 已配置目标" : ""), "");
    rows += toolRow("当前型号", esc(cpu.current || "未知"), "");
    rows += toolRow("原始型号", esc(cpu.original || "未知") + (cpu.manufacturer ? " · " + esc(cpu.manufacturer) : ""), "");
    rows += '<div class="row"><div class="row-main"><div class="row-title">目标型号</div>' +
      '<div class="row-detail">' + (cpu.target ? "当前目标：" + esc(cpu.target) : "尚未设置") + "</div></div>" +
      '<div class="row-actions"><input id="cpuSpoofModel" class="tool-target" type="text" placeholder="例如 SM-S9280" value="' +
      esc(cpu.target || "") + '" aria-label="目标 CPU 型号">' +
      '<button class="btn small" type="button" data-tool-action="cpu-target">保存</button></div></div>';
    rows += '<div class="row"><div class="row-main"><div class="row-title">开关</div>' +
      '<div class="row-detail">启用后按目标型号伪装，还原会恢复真实信息</div></div>' +
      '<div class="row-actions">' +
      '<button class="btn small primary" type="button" data-tool-action="cpu-enable">启用</button>' +
      '<button class="btn small" type="button" data-tool-action="cpu-disable">停用</button>' +
      '<button class="btn small danger" type="button" data-tool-action="cpu-reset">还原默认</button>' +
      "</div></div>";
    if (cpu.error) rows += '<div class="tool-hint">错误：' + esc(String(cpu.error)) + "</div>";
    node.innerHTML = rows;
  }

  function renderLanguage(language) {
    var select = el("languageSelect");
    if (!select || !language) return;
    var supported = language.supported || [];
    select.innerHTML = supported.map(function (item) {
      return '<option value="' + esc(item.tag) + '"' + (item.tag === language.current ? " selected" : "") +
        ">" + esc(item.label) + "</option>";
    }).join("");
    select.dataset.current = language.current;
  }

  function runToolAction(kind, payload, confirmText) {
    if (confirmText && !window.confirm(confirmText)) return Promise.resolve();
    return kpmPost("/api/tools/" + kind, payload || {}, 40000).then(function (result) {
      notify("已执行：" + kind);
      record("工具箱：" + kind);
      return result;
    }).catch(function (error) {
      notify("执行失败：" + (error.message || "未知错误"), true);
      throw error;
    });
  }

  function afterToolAction() {
    return loadTools().catch(function () { /* 卡片内已提示 */ });
  }

  var REBOOT_OPTIONS = [
    { mode: "system", symbol: "↻", title: "正常重启", detail: "完整重启 Android 与内核", confirm: "确认正常重启设备？所有正在运行的应用会关闭。" },
    { mode: "userspace", symbol: "◫", title: "用户空间重启", detail: "只重启 Android 用户空间，不重启内核", confirm: "确认执行用户空间重启？Android 用户空间和应用会重新启动。" },
    { mode: "soft", symbol: "◎", title: "软重启", detail: "重启 Android 框架，适合模块更新后使用", confirm: "确认软重启 Android 框架？正在运行的应用会重新启动。" },
    { mode: "recovery", symbol: "+", title: "Recovery", detail: "重启进入恢复模式", confirm: "确认重启到 Recovery？设备将离开当前系统。", critical: true },
    { mode: "bootloader", symbol: "B", title: "Bootloader", detail: "重启进入引导加载程序 / Fastboot", confirm: "确认重启到 Bootloader？设备将进入引导加载程序。", critical: true },
    { mode: "download", symbol: "↓", title: "Download 模式", detail: "主要用于支持该模式的三星设备", confirm: "确认进入 Download 模式？不支持该模式的设备可能只会正常重启。", critical: true },
    { mode: "edl", symbol: "!", title: "EDL 紧急下载", detail: "仅适用于支持 EDL 的部分高通设备", confirm: "确认进入 EDL 紧急下载模式？设备可能黑屏，并需要专用工具才能退出。", critical: true }
  ];

  function rebootOption(mode) {
    return REBOOT_OPTIONS.find(function (item) { return item.mode === mode; });
  }

  function renderRebootMenu(status) {
    state.rebootStatus = status || {};
    var available = state.rebootStatus.available !== false && !state.rebootStatus.pending;
    var options = REBOOT_OPTIONS.filter(function (item) {
      return item.mode !== "userspace" || !!state.rebootStatus.userspaceSupported;
    });
    var alert = "";
    if (state.rebootStatus.pending) {
      alert = '<div class="reboot-alert">已有重启请求正在执行，请勿重复操作。</div>';
    } else if (state.rebootStatus.available === false) {
      alert = '<div class="reboot-alert danger">root shell 不可用，当前不能发送重启命令。</div>';
    } else if (state.rebootStatus.lateLoad) {
      alert = '<div class="reboot-alert">当前为晚加载 / 越狱模式。正常重启后可能需要重新执行越狱流程才能恢复 root。</div>';
    }
    var body = alert + '<div class="reboot-list">' + options.map(function (item) {
      return '<button class="reboot-option' + (item.critical ? " critical" : "") + '" type="button" ' +
        'data-reboot-mode="' + item.mode + '"' + (available ? "" : " disabled") + '>' +
        '<span class="reboot-symbol" aria-hidden="true">' + item.symbol + '</span>' +
        '<span class="reboot-copy"><b>' + item.title + '</b><span>' + item.detail + '</span></span>' +
        '<span class="reboot-chevron" aria-hidden="true">›</span></button>';
    }).join("") + "</div>";
    openSheet(
      "重启设备",
      body,
      '<button class="btn small" type="button" data-kpm-dialog="close">取消</button>'
    );
  }

  function openRebootMenu() {
    var requestId = ++state.rebootMenuRequest;
    openSheet(
      "重启设备",
      '<div class="state"><b>正在检查设备能力</b>请稍候……</div>',
      '<button class="btn small" type="button" data-kpm-dialog="close">取消</button>'
    );
    api("/api/tools/reboot", { timeout: 10000 }).then(function (status) {
      if (requestId !== state.rebootMenuRequest || el("kpmDialog").classList.contains("hidden")) return;
      renderRebootMenu(status);
    }).catch(function (error) {
      if (requestId !== state.rebootMenuRequest || el("kpmDialog").classList.contains("hidden")) return;
      openSheet(
        "重启设备",
        '<div class="state"><b>无法读取重启能力</b>' + esc(error.message || "未知错误") + '</div>',
        '<button class="btn small" type="button" id="rebootRetry">重试</button>' +
          '<button class="btn small" type="button" data-kpm-dialog="close">关闭</button>'
      );
    });
  }

  function requestReboot(mode) {
    var option = rebootOption(mode);
    if (!option || state.rebootPending) return;
    var confirmation = option.confirm;
    if (mode === "system" && state.rebootStatus && state.rebootStatus.lateLoad) {
      confirmation += "\n\n当前为晚加载 / 越狱模式，重启后可能失去 root。";
    }
    if (!window.confirm(confirmation)) return;

    state.rebootPending = true;
    document.querySelectorAll("[data-reboot-mode]").forEach(function (button) { button.disabled = true; });
    var startedAt = Date.now();
    kpmPost("/api/tools/reboot", { mode: mode }, 10000).then(function () {
      closeSheet();
      notify("已发送“" + option.title + "”请求，设备即将重启");
      record("重启请求：" + option.title);
    }).catch(function (error) {
      if (!error.code && Date.now() - startedAt >= 300) {
        closeSheet();
        notify("连接已中断，设备可能正在执行“" + option.title + "”");
        record("重启连接中断：" + option.title);
      } else {
        notify("重启请求失败：" + (error.message || "未知错误") + (error.code ? " [" + error.code + "]" : ""), true);
        record("重启请求失败：" + option.title, true);
        document.querySelectorAll("[data-reboot-mode]").forEach(function (button) { button.disabled = false; });
      }
    }).finally(function () {
      state.rebootPending = false;
    });
  }

  // ------------------------------------------------------ 模块卡片自定义壁纸

  function applyModuleWallpaper(moduleId, card) {
    var meta = assetMeta("modulewall", moduleId);
    var layer = card.querySelector(".bg-layer");
    var dim = card.querySelector(".bg-dim");
    if (!meta) {
      if (layer) layer.remove();
      if (dim) dim.remove();
      card.classList.remove("card-bg-host", "light-text");
      return;
    }
    if (!layer) {
      layer = document.createElement("div");
      layer.className = "bg-layer";
      dim = document.createElement("div");
      dim.className = "bg-dim";
      card.insertBefore(dim, card.firstChild);
      card.insertBefore(layer, dim);
    }
    card.classList.add("card-bg-host", "light-text");
    applyWallpaperStyles(layer, dim, meta, assetUrl("modulewall", moduleId));
  }

  function moduleWallpaperButton(module) {
    var meta = assetMeta("modulewall", module.id);
    var key = "modulewall/" + module.id;
    return '<button class="btn small' + (meta ? " ok" : "") + '" type="button" data-asset-pick="' + esc(key) +
      '">' + (meta ? "换壁纸" : "壁纸") + "</button>" +
      (meta
        ? '<button class="btn small" type="button" data-asset-adjust="' + esc(key) + '">裁剪壁纸</button>' +
          '<button class="btn small danger" type="button" data-asset-clear="' + esc(key) + '">清除壁纸</button>'
        : "");
  }

"""

private const val WEB_MANAGER_PAGE_SCRIPT_TAIL: String = """
  var MANAGER_SETTING_INPUTS = {
    checkModuleUpdate: "managerCheckModuleUpdate",
    showVersionMismatchWarning: "managerVersionWarning",
    showGkiWarning: "managerGkiWarning",
    showHomeSupportCard: "managerSupportCard",
    showHomeLearnCard: "managerLearnCard"
  };

  function renderManagerSettings(manager, error) {
    manager = manager || {};
    state.managerSettings = manager;
    Object.keys(MANAGER_SETTING_INPUTS).forEach(function (key) {
      var input = el(MANAGER_SETTING_INPUTS[key]);
      if (!input) return;
      input.checked = manager[key] !== false;
      input.disabled = false;
    });
    var languages = [
      { tag: "zh-CN", label: "简体中文" }, { tag: "en", label: "English" },
      { tag: "fr", label: "Français" }, { tag: "ru", label: "Русский" },
      { tag: "ja", label: "日本語" }, { tag: "ko", label: "한국어" },
      { tag: "es", label: "Español" }
    ];
    renderLanguage({ current: manager.language || "zh-CN", supported: languages });
    el("managerHomeTitleDetail").textContent = manager.customHomeTitle || "当前跟随默认名称";
    var errorBox = el("managerSettingsError");
    errorBox.classList.toggle("hidden", !error);
    errorBox.querySelector("span").textContent = error || "";
  }

  function updateManagerSetting(key, value, input) {
    if (input) input.disabled = true;
    var payload = {};
    payload[key] = value;
    return kpmPost("/api/settings/manager", payload, 30000).then(function (result) {
      renderManagerSettings(result.manager, "");
      notify("软件管理器设置已同步");
      record("软件管理器设置：" + key);
      return result.manager;
    }).catch(function (error) {
      renderManagerSettings(state.managerSettings, error.message || "同步失败");
      notify("设置同步失败：" + (error.message || "未知错误"), true);
      throw error;
    }).finally(function () {
      if (input) input.disabled = false;
    });
  }

  function openManagerHomeTitleEditor() {
    var title = (state.managerSettings && state.managerSettings.customHomeTitle) || "";
    openSheet(
      "自定义主页顶部名称",
      '<div class="row"><div class="row-main"><div class="row-title">主页顶部名称</div>' +
        '<div class="row-detail">最多 40 个字符，留空可恢复默认名称。</div></div></div>' +
        '<input id="managerHomeTitleInput" class="search" style="width:100%;margin-top:10px" type="text" maxlength="40" value="' + esc(title) + '" placeholder="留空则使用默认名称" aria-label="主页顶部名称">',
      '<button class="btn primary" type="button" id="managerHomeTitleSave">保存</button>' +
        '<button class="btn" type="button" data-kpm-dialog="close">取消</button>'
    );
    el("managerHomeTitleSave").addEventListener("click", function (event) {
      event.currentTarget.disabled = true;
      updateManagerSetting("customHomeTitle", el("managerHomeTitleInput").value.trim()).then(function () {
        closeSheet();
      }).catch(function () { event.currentTarget.disabled = false; });
    });
  }

  function dynamicManagerStatusText(status) {
    if (!status || status.supported === false) return "当前内核不支持";
    if (status.active) return "已配置 · 已被内核识别";
    if (status.configured) return "已配置 · 等待兼容管理器生效";
    return "未配置";
  }

  function loadDynamicManagerSummary() {
    return api("/api/dynamic-manager", { timeout: 15000 }).then(function (data) {
      el("dynamicManagerDetail").textContent = dynamicManagerStatusText(data.status);
      return data.status;
    }).catch(function (error) {
      el("dynamicManagerDetail").textContent = "读取失败：" + (error.message || "未知错误");
      throw error;
    });
  }

  function openDynamicManagerEditor() {
    openSheet("动态管理器", '<div class="state">正在读取状态……</div>', '<button class="btn" type="button" data-kpm-dialog="close">关闭</button>');
    api("/api/dynamic-manager", { timeout: 15000 }).then(function (data) {
      var status = data.status || {};
      var disabled = status.supported === false ? " disabled" : "";
      openSheet(
        "动态管理器",
        '<div class="notice' + (status.supported === false ? " warn" : "") + '"><b>' + esc(dynamicManagerStatusText(status)) + '</b><span>' +
          esc(status.error || "管理当前内核识别的副管理器签名证书。") + '</span></div>' +
          '<div class="row"><div class="row-main"><div class="row-title">证书大小</div><div class="row-detail">256-4096</div></div>' +
          '<input id="dynamicManagerSize" class="tool-target" type="number" min="256" max="4096" value="' + esc(status.certificateSize || "") + '"' + disabled + '></div>' +
          '<div class="row"><div class="row-main"><div class="row-title">证书 SHA-256</div><div class="row-detail">64 位小写十六进制</div></div></div>' +
          '<input id="dynamicManagerHash" class="search mono" style="width:100%;margin-top:10px" type="text" maxlength="64" value="' + esc(status.certificateSha256 || "") + '"' + disabled + '>',
        '<button class="btn danger" type="button" id="dynamicManagerClear"' + disabled + '>清除</button>' +
          '<button class="btn primary" type="button" id="dynamicManagerSave"' + disabled + '>保存</button>' +
          '<button class="btn" type="button" data-kpm-dialog="close">取消</button>'
      );
      if (status.supported === false) return;
      el("dynamicManagerSave").addEventListener("click", function (event) {
        var size = Number(el("dynamicManagerSize").value);
        var hash = el("dynamicManagerHash").value.trim();
        if (!Number.isInteger(size) || size < 256 || size > 4096 || !/^[0-9a-f]{64}$/.test(hash)) {
          notify("请输入 256-4096 的证书大小和 64 位小写 SHA-256", true);
          return;
        }
        event.currentTarget.disabled = true;
        kpmPost("/api/dynamic-manager", { action: "set", certificateSize: size, certificateSha256: hash }, 40000).then(function () {
          closeSheet();
          notify("动态管理器证书已保存");
          return loadDynamicManagerSummary();
        }).catch(function (error) {
          notify("动态管理器更新失败：" + (error.message || "未知错误"), true);
          event.currentTarget.disabled = false;
        });
      });
      el("dynamicManagerClear").addEventListener("click", function (event) {
        if (!window.confirm("清除动态管理器证书并撤销副管理器？")) return;
        event.currentTarget.disabled = true;
        kpmPost("/api/dynamic-manager", { action: "clear" }, 40000).then(function () {
          closeSheet();
          notify("动态管理器已清除");
          return loadDynamicManagerSummary();
        }).catch(function (error) {
          notify("动态管理器清除失败：" + (error.message || "未知错误"), true);
          event.currentTarget.disabled = false;
        });
      });
    }).catch(function (error) {
      el("kpmDialogBody").innerHTML = '<div class="state">读取失败：' + esc(error.message || "未知错误") + '</div>';
    });
  }

  function stealthCodeValid(value) {
    return String(value || "").trim().length > 0;
  }

  function openStealthEditor(enableAfterSave) {
    var code = (state.stealth && state.stealth.code) || "*#*#4211#*#*";
    var warning = enableAfterSave
      ? '<div class="notice warn"><b>启用后软件管理器会伪装为未安装</b><span>超级用户、模块、KPM 和设置页面会隐藏。之后只能在网页管理器输入密令或通过拨号密令关闭。</span></div>'
      : "";
    openSheet(
      enableAfterSave ? "启用隐身模式" : "设置隐身密令",
      warning + '<div class="row"><div class="row-main"><div class="row-title">隐身密令</div>' +
        '<div class="row-detail">密令不限制长度或字符；使用拨号关闭时输入 *#*#密令#*#*。</div></div></div>' +
        '<input id="stealthCodeInput" class="search mono" style="width:100%;margin-top:10px" type="text" ' +
        'autocomplete="off" value="' + esc(code) + '" aria-label="隐身密令">',
      '<button class="btn primary" type="button" id="stealthSave">保存</button>' +
        '<button class="btn" type="button" data-kpm-dialog="close">取消</button>'
    );
    el("stealthSave").addEventListener("click", function () {
      var input = el("stealthCodeInput");
      var requestedCode = input.value.trim();
      if (!stealthCodeValid(requestedCode)) {
        notify("密令不能为空", true);
        input.focus();
        return;
      }
      var button = el("stealthSave");
      button.disabled = true;
      kpmPost("/api/stealth", {
        enabled: enableAfterSave || !!state.stealth.enabled,
        code: requestedCode
      }, 30000).then(function () {
        closeSheet();
        notify(enableAfterSave ? "隐身模式已启用" : "隐身密令已保存");
        record(enableAfterSave ? "隐身模式：启用" : "隐身密令：更新");
        return loadSettings();
      }).catch(function (error) {
        notify("隐身模式更新失败：" + (error.message || "未知错误"), true);
        button.disabled = false;
      });
    });
  }

  function openStealthDisableEditor() {
    openSheet(
      "关闭隐身模式",
      '<div class="notice warn"><b>需要验证隐身密令</b><span>请输入启用隐身模式时保存的密令。</span></div>' +
        '<input id="stealthDisableCode" class="search mono" style="width:100%;margin-top:10px" type="password" ' +
        'autocomplete="off" value="" aria-label="隐身密令">',
      '<button class="btn danger" type="button" id="stealthDisable">验证并关闭</button>' +
        '<button class="btn" type="button" data-kpm-dialog="close">取消</button>'
    );
    el("stealthDisableCode").focus();
    el("stealthDisable").addEventListener("click", function () {
      var input = el("stealthDisableCode");
      var code = input.value.trim();
      if (!stealthCodeValid(code)) {
        notify("密令不能为空", true);
        input.focus();
        return;
      }
      var button = el("stealthDisable");
      button.disabled = true;
      kpmPost("/api/stealth", { enabled: false, code: code }, 20000).then(function () {
        closeSheet();
        notify("隐身模式已关闭，密令备份仍保留");
        record("隐身模式：关闭");
        return loadSettings();
      }).catch(function (error) {
        notify("隐身模式关闭失败：" + (error.message || "未知错误"), true);
        button.disabled = false;
        input.focus();
        input.select();
      });
    });
  }

  function loadSettings() {
    el("settingsError").classList.add("hidden");
    return api("/api/settings", { timeout: 15000 }).then(function (data) {
      el("autoStart").checked = !!data.autoStart;
      el("settingsAddress").textContent = "127.0.0.1:" + (data.port || "—") + (data.loopback ? " · 仅本机访问" : "");
      el("settingsApi").textContent = "v" + (data.apiVersion || "-");
      el("settingsService").textContent = data.running === false ? "未运行" : "运行中";
      el("settingsUptime").textContent = data.running === false ? "暂不可用" : formatUptime(data.uptimeSeconds);
      applyTheme(data.theme);
      el("themeSelect").value = state.theme;
      renderLauncher(data.launcher);
      renderPortSettings(data);
      var stealth = data.stealth || {};
      state.stealth = stealth;
      el("stealthDetail").textContent = stealth.enabled
        ? "已启用 · 软件管理器主页伪装为未安装"
        : "未启用" + (stealth.codeBackedUp ? " · 密令备份仍保留" : "");
      el("stealthToggle").checked = !!stealth.enabled;
      el("stealthToggle").disabled = false;
      el("stealthCodeDetail").textContent = stealth.code || "*#*#4211#*#*";
      renderManagerSettings(data.manager, data.managerSettingsError || "");
      loadDynamicManagerSummary().catch(function () { /* 行内已提示 */ });
      state.assets = data.assets || { wallpapers: {}, navIcons: {} };
      state.assetCatalog = data.assetCatalog || state.assetCatalog;
      applyAssets();
      return true;
    }).catch(function (error) {
      el("settingsErrorText").textContent = error.message || "无法读取设置";
      el("settingsError").classList.remove("hidden");
      return false;
    });
  }

  function loadHome(force) {
    return Promise.all([
      loadSettings(),
      loadStatus(),
      loadModules(!!force),
      loadDevice(!!force),
      loadSuperuserSummary(!!force)
    ]);
  }

  // ------------------------------------------------------------ 超级用户列表

  function visibleApps() {
    var query = state.appQuery.trim().toLowerCase();
    return state.apps.filter(function (app) {
      if (query) {
        var label = String(app.label || "").toLowerCase();
        var pkg = String(app.packageName || "").toLowerCase();
        if (label.indexOf(query) < 0 && pkg.indexOf(query) < 0 && String(app.uid).indexOf(query) < 0) {
          return false;
        }
      }
      if (state.appFilter === "granted") return !!app.allowSu;
      if (state.appFilter === "not-granted") return !app.allowSu;
      return true;
    }).sort(function (a, b) {
      if (state.appSort === "uid") return Number(a.uid) - Number(b.uid);
      if (state.appSort === "status") {
        return (Number(!!b.allowSu) - Number(!!a.allowSu)) ||
          String(a.label).localeCompare(String(b.label), "zh-CN");
      }
      return String(a.label).localeCompare(String(b.label), "zh-CN");
    });
  }

  function renderSuperusers() {
    var box = el("superusers");
    var apps = visibleApps();
    el("superuserStats").textContent = "UID " + state.appStats.uidCount + " 个 · 应用 " +
      state.appStats.totalApps + " 个 · 已授权 " + state.appStats.authorizedCount + " 个" +
      (state.appStats.source === "root" ? " · 来源：Root 服务"
        : state.appStats.source === "local" ? " · 来源：本机包管理器（兜底）"
        : state.appStats.source === "root+local" ? " · 来源：本机与 Root 合并" : "");
    if (!state.appLoaded) {
      box.innerHTML = state.appLoading
        ? '<div class="state"><b>正在读取应用列表</b>首次读取需要查询 Root 服务，可能需要十几秒。</div>'
        : '<div class="state">尚未读取应用列表。</div>';
      return;
    }
    if (!apps.length) {
      box.innerHTML = '<div class="state"><b>没有匹配的应用</b>' +
        (state.apps.length ? "请调整搜索或筛选条件。" : "请确认 Root 服务可用后重试。") + "</div>";
      return;
    }
    box.innerHTML = apps.map(function (app) {
      var busy = state.busy["su:" + app.uid] ? " disabled" : "";
      var tags = [];
      if (app.sharedUid) tags.push('<span class="tag warn">共享 UID ' + Number(app.appCount || 1) + "</span>");
      if (app.customProfile) tags.push('<span class="tag accent">自定义配置</span>');
      if (app.isSystem) tags.push('<span class="tag">系统</span>');
      if (app.userId) tags.push('<span class="tag">用户 ' + Number(app.userId) + "</span>");
      var action = app.manageable === false
        ? '<span class="tag">不可修改</span>'
        : '<input type="checkbox" data-app-uid="' + esc(app.uid) + '"' +
          (app.allowSu ? " checked" : "") + busy +
          ' aria-label="' + (app.allowSu ? "关闭" : "开启") + " " + esc(app.label) + ' 的 Root 权限">';
      return '<article class="item superuser-item">' +
        '<span class="av">' + esc((String(app.label || app.packageName || "?").trim()[0] || "?").toUpperCase()) +
        '<img class="img" alt="" loading="lazy" src="' + PREFIX + "/api/icon/" + encodeURIComponent(app.packageName) + '"></span>' +
        '<div class="item-main"><div class="item-title"><span class="name">' + esc(app.label) + "</span>" +
        (app.allowSu ? '<span class="tag ok">已授权</span>' : "") + "</div>" +
        '<div class="item-meta"><span class="mono">' + esc(app.packageName) + "</span><span>·</span><span>UID " +
        esc(app.uid) + "</span>" + tags.join("") + "</div></div>" +
        '<div class="item-actions">' + action + "</div></article>";
    }).join("");
    attachIconFallback(box);
  }

  function changeSuperuser(uid, action) {
    var key = "su:" + uid;
    if (state.busy[key]) return;
    if (action === "revoke" && !window.confirm("关闭该应用的 Root 权限？")) {
      renderSuperusers();
      return;
    }
    state.busy[key] = true;
    renderSuperusers();
    api("/api/superuser/" + encodeURIComponent(uid) + "/" + action, { method: "POST", timeout: 60000 })
      .then(function () {
        notify(action === "grant" ? "已允许使用 Root" : "已撤销 Root 权限");
        record((action === "grant" ? "授权：" : "撤销：") + "UID " + uid);
        return Promise.all([loadSuperusers(true), loadStatus()]);
      })
      .catch(function (error) {
        notify(error.message, true);
        record("授权操作失败：" + error.message, true);
        renderSuperusers();
      })
      .finally(function () {
        delete state.busy[key];
        renderSuperusers();
      });
  }

  // ---------------------------------------------------------------- 模块列表

  function moduleMatches(module) {
    var query = state.moduleQuery.trim().toLowerCase();
    if (query) {
      var name = String(module.name || "").toLowerCase();
      var id = String(module.id || "").toLowerCase();
      if (name.indexOf(query) < 0 && id.indexOf(query) < 0) return false;
    }
    switch (state.moduleFilter) {
      case "enabled": return !!module.enabled && !module.remove;
      case "disabled": return !module.enabled && !module.remove;
      case "webui": return !!module.webui;
      case "action": return !!module.action;
      case "pending": return !!module.remove;
      default: return true;
    }
  }

  function visibleModules() {
    var rank = function (module) { return module.remove ? 0 : module.enabled ? 1 : 2; };
    return state.modules.filter(moduleMatches).sort(function (a, b) {
      if (state.moduleSort === "status") {
        return rank(a) - rank(b) || String(a.name).localeCompare(String(b.name), "zh-CN");
      }
      if (state.moduleSort === "version") {
        return String(b.version).localeCompare(String(a.version), undefined, { numeric: true }) ||
          String(a.name).localeCompare(String(b.name), "zh-CN");
      }
      return String(a.name).localeCompare(String(b.name), "zh-CN");
    });
  }

  function moduleIconHtml(module) {
    var letter = esc((String(module.name || module.id || "?").trim()[0] || "?").toUpperCase());
    var src = module.hasIcon
      ? '<img class="img" alt="" loading="lazy" src="' + PREFIX + "/api/modules/" + encodeURIComponent(module.id) + '/icon">'
      : "";
    return '<span class="av">' + letter + src + "</span>";
  }

  function moduleTags(module) {
    var tags = [];
    if (module.remove) tags.push('<span class="tag danger">待重启移除</span>');
    else if (module.enabled) tags.push('<span class="tag ok">已启用</span>');
    else tags.push('<span class="tag">已停用</span>');
    if (module.metamodule) tags.push('<span class="tag accent">元模块</span>');
    if (module.update) tags.push('<span class="tag warn">有更新</span>');
    if (module.webui) tags.push('<span class="tag accent">WebUI</span>');
    if (module.action) tags.push('<span class="tag">脚本</span>');
    return tags.join("");
  }

  function moduleActions(module) {
    var id = encodeURIComponent(module.id);
    var busy = state.busy[module.id] ? " disabled" : "";
    if (module.remove) {
      return '<div class="item-actions">' +
        '<button class="btn small" type="button" data-module="' + esc(id) + '" data-action="undo-uninstall"' + busy + '>撤销卸载</button>' +
        "</div>";
    }
    var buttons = [];
    if (module.webui && module.enabled) {
      buttons.push('<button class="btn small primary" type="button" data-module="' + esc(id) + '" data-action="webui">打开 WebUI</button>');
    }
    if (module.action && module.enabled) {
      var running = module.actionJobId ? "运行中…" : "执行";
      buttons.push('<button class="btn small' + (module.actionJobId ? " ok" : "") + '" type="button" data-module="' + esc(id) +
        '" data-action="action"' + busy + ">" + running + "</button>");
    }
    buttons.push('<button class="btn small" type="button" data-module="' + esc(id) + '" data-action="' +
      (module.enabled ? "disable" : "enable") + '"' + busy + ">" + (module.enabled ? "停用" : "启用") + "</button>");
    buttons.push('<button class="btn small danger" type="button" data-module="' + esc(id) + '" data-action="uninstall"' + busy + ">卸载</button>");
    buttons.push(moduleWallpaperButton(module));
    return '<div class="item-actions">' + buttons.join("") + "</div>";
  }

  function renderModules() {
    var box = el("modules");
    var modules = visibleModules();
    el("moduleCount").textContent = "显示 " + modules.length + " / " + state.modules.length + " 个";
    if (!state.moduleLoaded) {
      box.innerHTML = '<div class="state">正在读取模块列表……</div>';
      return;
    }
    if (!state.modules.length) {
      box.innerHTML = '<div class="state"><b>没有已安装模块</b>本机 /data/adb/modules 下的模块会显示在这里。</div>';
      return;
    }
    if (!modules.length) {
      box.innerHTML = '<div class="state"><b>没有匹配的模块</b>请调整搜索或筛选条件。</div>';
      return;
    }
    box.innerHTML = modules.map(function (module) {
      var meta = '<div class="item-meta"><span class="mono">' + esc(module.id) + "</span><span>·</span><span>" +
        esc(module.version) + "</span>" + (module.author ? "<span>·</span><span>" + esc(module.author) + "</span>" : "") +
        "</div>";
      var desc = module.description ? '<div class="item-desc">' + esc(module.description) + "</div>" : "";
      return '<article class="item" data-module-id="' + esc(module.id) + '">' + moduleIconHtml(module) +
        '<div class="item-main">' +
        '<div class="item-title"><span class="name">' + esc(module.name) + "</span>" + moduleTags(module) + "</div>" +
        meta + desc + "</div>" + moduleActions(module) + "</article>";
    }).join("");
    attachIconFallback(box);
    // 模块卡片壁纸（与原生模块页同款，按模块 id 各存一张）
    box.querySelectorAll("article.item[data-module-id]").forEach(function (card) {
      applyModuleWallpaper(card.getAttribute("data-module-id"), card);
    });
  }

  function attachIconFallback(scope) {
    scope.querySelectorAll("img.img").forEach(function (img) {
      img.addEventListener("error", function () { img.remove(); });
    });
  }

  function changeModule(id, action) {
    if (action === "webui") { openWebUi(id); return; }
    if (action === "action") { runModuleAction(id); return; }
    if (state.busy[id]) return;
    var messages = {
      enable: ["模块已启用", "启用该模块？重启后生效。"],
      disable: ["模块已停用", "停用该模块？重启后生效。"],
      uninstall: ["已安排卸载，重启后完成", "确定卸载该模块？通常需要重启后完成。"],
      "undo-uninstall": ["已撤销卸载", "撤销卸载并保留该模块？"]
    };
    var entry = messages[action];
    if (entry && !window.confirm(entry[1])) return;
    state.busy[id] = true;
    renderModules();
    api("/api/modules/" + encodeURIComponent(id) + "/" + action, { method: "POST" })
      .then(function () {
        notify(entry ? entry[0] : "操作已完成");
        record((entry ? entry[0] : "模块操作") + "：" + id);
        return Promise.all([loadModules(true), loadStatus()]);
      })
      .catch(function (error) {
        notify(error.message, true);
        record("模块操作失败：" + error.message, true);
      })
      .finally(function () {
        delete state.busy[id];
        renderModules();
      });
  }

  /**
   * Opens the module WebUI in a new tab.
   *
   * The tab is created **synchronously** in the click handler: a `window.open`
   * that happens after an await runs outside the user-gesture context and is
   * killed by mobile popup blockers, which is what made the button look dead.
   * The preflight then runs in the background purely to explain failures, and
   * the server answers a failed navigation with a readable HTML page.
   */
  function openWebUi(id) {
    var url = PREFIX + "/webui/" + encodeURIComponent(id) + "/";
    var win = null;
    try { win = window.open(url, "_blank"); } catch (_) { win = null; }
    if (!win) {
      notify("浏览器拦截了新标签页，正在当前页打开");
      record("打开 WebUI：" + id);
      location.href = url;
      return;
    }
    try { win.opener = null; } catch (_) { }
    notify("已在新标签页打开模块 WebUI");
    record("打开 WebUI：" + id);
    api("/api/webui/module-info?module=" + encodeURIComponent(id), { timeout: 30000 })
      .catch(function (error) {
        var message = "模块 WebUI 预检失败：" + (error.message || "未知错误") +
          (error.code ? " [" + error.code + "]" : "") + "（可在设置页运行诊断）";
        notify(message, true);
        record(message, true);
      });
  }

  // ------------------------------------------------------------ 执行控制台

  function openConsole(module, jobId) {
    state.jobModule = module;
    state.job = jobId ? { id: jobId, offset: 0, truncatedNotified: false } : null;
    el("sheetTitle").textContent = "执行：" + (module ? module.name : "模块脚本");
    el("consoleOut").textContent = jobId ? "正在读取输出……\n" : "正在启动脚本……\n";
    el("sheet").classList.remove("hidden");
    el("sheetState").textContent = "准备中";
    el("sheetState").className = "tag";
    el("sheetCancel").disabled = false;
    if (jobId) pollJob();
  }

  function closeConsole() {
    clearTimeout(state.jobTimer);
    state.jobTimer = null;
    el("sheet").classList.add("hidden");
    state.job = null;
  }

  function appendOutput(text) {
    var box = el("consoleOut");
    var stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 24;
    box.textContent += text;
    if (stick) box.scrollTop = box.scrollHeight;
  }

  function jobStateLabel(job) {
    if (job.running) return ["运行中", "tag warn"];
    if (job.state === "succeeded") return ["执行完成", "tag ok"];
    if (job.state === "cancelled") return ["已中止", "tag"];
    return ["失败（退出码 " + (job.exitCode === null || job.exitCode === undefined ? "?" : job.exitCode) + "）", "tag danger"];
  }

  function pollJob() {
    if (!state.job) return;
    var activeJob = state.job;
    var activeJobId = activeJob.id;
    api("/api/jobs/" + encodeURIComponent(activeJobId) + "?offset=" + activeJob.offset, { timeout: 20000 })
      .then(function (job) {
        if (!state.job || state.job.id !== activeJobId) return;
        if (job.output) {
          appendOutput(job.output);
        }
        state.job.offset = job.offset;
        if (job.truncated && !state.job.truncatedNotified) {
          appendOutput("\n[输出过多，已截断]\n");
          state.job.truncatedNotified = true;
        }
        var label = jobStateLabel(job);
        el("sheetState").textContent = label[0];
        el("sheetState").className = label[1];
        el("sheetCancel").disabled = !job.running;
        if (job.running) {
          state.jobTimer = setTimeout(pollJob, 700);
        } else {
          if (job.output === "" && job.exitCode !== 0) appendOutput("（无输出）\n");
          if (state.jobModule) record("脚本执行结束：" + state.jobModule.id + " · " + label[0]);
          loadModules(true);
        }
      })
      .catch(function (error) {
        if (!state.job || state.job.id !== activeJobId) return;
        appendOutput("\n[轮询失败] " + error.message + "\n");
        state.jobTimer = setTimeout(pollJob, 2500);
      });
  }

  function runModuleAction(id) {
    var module = state.modules.find(function (item) { return item.id === id; });
    openConsole(module || { id: id, name: id }, null);
    api("/api/modules/" + encodeURIComponent(id) + "/action", { method: "POST", timeout: 25000 })
      .then(function (data) {
        if (el("sheet").classList.contains("hidden")) return;
        state.job = { id: data.jobId, offset: 0, truncatedNotified: false };
        pollJob();
      })
      .catch(function (error) {
        el("sheetState").textContent = "启动失败";
        el("sheetState").className = "tag danger";
        appendOutput("[错误] " + error.message + (error.code ? " [" + error.code + "]" : "") + "\n");
        el("sheetCancel").disabled = true;
      });
  }

  function cancelJob() {
    if (!state.job) return;
    api("/api/jobs/" + encodeURIComponent(state.job.id) + "/cancel", { method: "POST" })
      .then(function () {
        notify("已请求中止执行");
        record("中止脚本执行：" + (state.jobModule ? state.jobModule.id : ""));
      })
      .catch(function (error) { notify(error.message, true); });
  }

  // ---------------------------------------------------------------- 设置/诊断

  function changeAutoStart(enabled) {
    return api("/api/settings/auto-start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: enabled }),
      timeout: 15000
    }).then(function () {
      notify(enabled ? "已开启开机自动启动" : "已关闭开机自动启动");
      record(enabled ? "开启开机自动启动" : "关闭开机自动启动");
    }).catch(function (error) {
      el("autoStart").checked = !enabled;
      notify(error.message, true);
    });
  }

  function setAutoRefresh(enabled) {
    state.autoRefresh = enabled;
    el("autoRefreshToggle").checked = enabled;
    clearInterval(state.timer);
    state.timer = null;
    if (enabled) {
      state.timer = setInterval(function () {
        loadStatus();
        if (state.view === "modules") loadModules(false);
      }, 10000);
    }
    notify(enabled ? "已开启自动刷新" : "已关闭自动刷新");
  }

  function clearCache() {
    api("/api/settings/cache", { method: "POST", timeout: 30000 }).then(function () {
      notify("缓存已清空");
      record("清空缓存");
      state.moduleLoaded = false;
      state.appLoaded = false;
      state.summaryLoaded = false;
      state.kpmLoaded = false;
      state.kpm = null;
      state.apps = [];
      state.appStats = { uidCount: 0, totalApps: 0, authorizedCount: 0, sharedUidCount: 0, source: "" };
      return loadHome(true);
    }).catch(function (error) { notify(error.message, true); });
  }

  function formatDiagnostics(data) {
    var lines = [];
    lines.push("接口版本 v" + (data.apiVersion || "-") + "  端口 " + (data.port || "-") +
      "  Android SDK " + (data.androidSdk || "-"));
    lines.push("管理器 " + (data.managerPackage || "-"));
    lines.push("服务运行 " + (data.serverRunning ? "是" : "否") + "  运行时长 " + formatUptime(data.uptimeSeconds));
    lines.push("ksud " + (data.ksudPath || "-") + (data.ksudExists
      ? "（存在 " + Math.round(Number(data.ksudSize || 0) / 1048576) + " MB）" : "（不存在）"));
    lines.push("root shell 普通=" + (data.shellPlainRoot ? "可用" : "不可用") +
      " 全局挂载=" + (data.shellGlobalRoot ? "可用" : "不可用") +
      " rootAvailable=" + (data.rootAvailable ? "true" : "false"));
    var modules = data.moduleSnapshot;
    lines.push("模块快照 " + (modules
      ? (modules.count + " 个，更新于 " + Math.round(Number(modules.ageMillis || 0) / 1000) + " 秒前")
      : "无（尚未成功读取或查询超时）"));
    var apps = data.appSnapshot;
    lines.push("授权快照 " + (apps
      ? (apps.source + " 来源 " + apps.totalApps + " 个应用 / " + apps.entries + " 个 UID，更新于 " +
        Math.round(Number(apps.ageMillis || 0) / 1000) + " 秒前")
      : "无（尚未成功读取或查询超时）"));
    lines.push("执行任务 " + (data.hasRunningAction ? "有正在运行的脚本" : "无"));
    lines.push("");
    lines.push("最近日志：");
    (data.log || []).forEach(function (entry) {
      lines.push("  [" + entry.level + "] " + entry.tag + " · " + entry.message);
    });
    return lines.join("\n");
  }

  function runDiagnostics() {
    el("diagnostics").textContent = "正在运行诊断……";
    return api("/api/diagnostics", { timeout: 45000 }).then(function (data) {
      el("diagnostics").textContent = formatDiagnostics(data);
      record("运行诊断");
      return true;
    }).catch(function (error) {
      el("diagnostics").textContent = "诊断失败：" + (error.message || "未知错误");
      return false;
    });
  }

  function copyDiagnostics() {
    var text = el("diagnostics").textContent;
    if (!text) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { notify("诊断结果已复制"); },
        function () { window.prompt("诊断结果", text); });
    } else {
      window.prompt("诊断结果", text);
    }
  }

  function copyAddress() {
    var text = accessAddress();
    var done = function () { notify("访问地址已复制"); record("复制访问地址"); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { window.prompt("复制访问地址", text); });
    } else {
      window.prompt("复制访问地址", text);
    }
  }

  function openNativeSusfsManager() {
    var button = el("susfsShortcut");
    button.disabled = true;
    kpmPost("/api/tools/native-susfs", {}, 20000).then(function (data) {
      var url = String(data.url || "");
      if (!url) throw new Error("ksud 网页管理器没有返回访问地址");
      window.location.assign(url + "#susfs");
    }).catch(function (error) {
      notify("无法打开 SUSFS 管理：" + (error.message || "未知错误"), true);
      button.disabled = false;
    });
  }

  // ------------------------------------------------------------------- 视图

  function setView(view, push) {
    state.view = view;
    document.body.dataset.currentView = view;
    ["home", "superuser", "modules", "kpm", "settings"].forEach(function (name) {
      el("view-" + name).classList.toggle("hidden", name !== view);
    });
    document.querySelectorAll("[data-view]").forEach(function (button) {
      button.classList.toggle("active", button.dataset.view === view);
    });
    if (push !== false) {
      try { history.replaceState(null, "", "#" + view); } catch (_) { }
    }
    if (view === "superuser" && !state.appLoaded) loadSuperusers(false);
    if (view === "modules" && !state.moduleLoaded) loadModules(false);
    if (view === "kpm" && !state.kpmLoaded) loadKpm(false);
    if (view === "settings") {
      loadSettings();
      if (!state.featuresLoaded) loadFeatures().catch(function () { /* 卡片内已提示 */ });
      if (!state.toolsLoaded) loadTools().catch(function () { /* 卡片内已提示 */ });
      runDiagnostics();
    }
    window.scrollTo(0, 0);
  }

  function loadAll(force) {
    el("refreshAll").disabled = true;
    return loadHome(!!force).finally(function () { el("refreshAll").disabled = false; });
  }

  function bind() {
    document.querySelectorAll("[data-view]").forEach(function (button) {
      button.addEventListener("click", function () { setView(button.dataset.view); });
    });
    document.querySelectorAll("[data-go]").forEach(function (button) {
      button.addEventListener("click", function () { setView(button.dataset.go); });
    });
    document.addEventListener("click", function (event) {
      var copy = event.target.closest("[data-copy-row]");
      if (copy) { copyFromRow(copy); return; }
      var dialogAction = event.target.closest("[data-kpm-dialog]");
      if (dialogAction) {
        if (dialogAction.dataset.kpmDialog === "close") closeSheet();
        return;
      }
      var rebootAction = event.target.closest("[data-reboot-mode]");
      if (rebootAction) { requestReboot(rebootAction.dataset.rebootMode); return; }
      if (event.target.closest("#rebootRetry")) { openRebootMenu(); return; }
      if (event.target.closest("#kpmControlRun")) { runKpmControl(); return; }
      if (event.target.closest("#kpmImportConfirm")) { runKpmImport(); return; }
      if (event.target.closest("#kpmRemoveConfirm")) {
        var removeTarget = state.kpmRemoveTarget;
        closeSheet();
        state.kpmRemoveTarget = null;
        if (removeTarget) runKpmAction("remove", removeTarget.id, removeTarget.name);
        return;
      }
      var toolToggle = event.target.closest("[data-tool-toggle]");
      if (toolToggle) {
        var toggleKind = toolToggle.dataset.toolToggle;
        var toggleOn = toolToggle.checked;
        if (toggleKind === "builtinMount") {
          runToolAction("builtin-mount", { action: "enabled", value: toggleOn }, null)
            .then(afterToolAction).catch(afterToolAction);
        } else if (toggleKind === "kpatch") {
          runToolAction("kpatch", { enabled: toggleOn }, null).then(afterToolAction).catch(afterToolAction);
        } else if (toggleKind === "pathmaskAutoLoad") {
          var delay = "0";
          if (toggleOn) {
            delay = window.prompt("开机延迟多少秒后应用？（0 = 立即）", "0");
            if (delay === null) {
              toolToggle.checked = false;
              return;
            }
          }
          runToolAction("pathmask", { action: "autoLoad", enabled: toggleOn, delaySeconds: Number(delay) || 0 }, null)
            .then(afterToolAction).catch(afterToolAction);
        }
        return;
      }
      var toolSelect = event.target.closest("[data-tool-select]");
      if (toolSelect) {
        var selectKind = toolSelect.dataset.toolSelect;
        var selectValue = toolSelect.value;
        if (selectKind === "builtinMountMode") {
          runToolAction("builtin-mount", { action: "mode", value: selectValue }, null)
            .then(afterToolAction).catch(afterToolAction);
        } else if (selectKind === "builtinMountVariant") {
          runToolAction("builtin-mount", { action: "variant", value: selectValue }, null)
            .then(afterToolAction).catch(afterToolAction);
        }
        return;
      }
      var toolAction = event.target.closest("[data-tool-action]");
      if (toolAction) {
        var actionKind = toolAction.dataset.toolAction;
        if (actionKind === "pathmask-logs") {
          runToolAction("pathmask", { action: "logs" }, null).then(function (result) {
            el("pathmaskLogs").innerHTML = '<div class="tool-hint">pathmask 日志</div>' +
              '<pre class="console">' + esc((result && result.logs) || "（没有日志）") + "</pre>";
          }).catch(function () { /* 已提示 */ });
        } else if (actionKind === "pathmask-apply") {
          runToolAction("pathmask", { action: "apply" }, null).then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "pathmask-unload") {
          runToolAction("pathmask", { action: "unload" }, "卸载 pathmask 并清空内核隐藏路径？")
            .then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "pathmask-delete") {
          runToolAction("pathmask", { action: "delete" }, "删除已保存的 pathmask 配置？此操作不可撤销。")
            .then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "cpu-target") {
          var model = (el("cpuSpoofModel") || {}).value || "";
          runToolAction("cpu-spoof", { action: "target", model: model }, null).then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "cpu-enable") {
          runToolAction("cpu-spoof", { action: "enable" }, null).then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "cpu-disable") {
          runToolAction("cpu-spoof", { action: "disable" }, null).then(afterToolAction).catch(afterToolAction);
        } else if (actionKind === "cpu-reset") {
          runToolAction("cpu-spoof", { action: "reset" }, "还原真实 CPU 信息？").then(afterToolAction).catch(afterToolAction);
        }
        return;
      }
      var featureSwitch = event.target.closest("[data-feature]");
      if (featureSwitch) { setFeature(featureSwitch.dataset.feature, featureSwitch.checked, featureSwitch); return; }
      var assetPick = event.target.closest("[data-asset-pick]");
      if (assetPick) { startAssetPick(assetPick.dataset.assetPick); return; }
      var assetAdjust = event.target.closest("[data-asset-adjust]");
      if (assetAdjust) { openAssetAdjust(assetAdjust.dataset.assetAdjust); return; }
      var assetClear = event.target.closest("[data-asset-clear]");
      if (assetClear) { clearAsset(assetClear.dataset.assetClear); return; }
      if (event.target.closest("#assetAdjustApply")) { saveAssetAdjust(); return; }
      var kpmButton = event.target.closest("[data-kpm]");
      if (kpmButton && !kpmButton.disabled) {
        var kpmAction = kpmButton.dataset.kpm;
        var kpmId = kpmButton.dataset.id;
        var kpmName = kpmButton.dataset.name || kpmId;
        if (kpmAction === "control") {
          openKpmControlDialog(kpmId, kpmName, kpmButton.dataset.args || "");
        } else if (kpmAction === "remove") {
          openKpmRemoveDialog(kpmId, kpmName);
        } else {
          runKpmAction(kpmAction, kpmId, kpmName);
        }
        return;
      }
      var excludeButton = event.target.closest("[data-kpm-exclude]");
      if (excludeButton && !excludeButton.disabled) {
        kpmToggleExclude(
          excludeButton.dataset.kpmExclude,
          Number(excludeButton.dataset.uid),
          excludeButton.dataset.enabled === "1"
        );
        return;
      }
      var button = event.target.closest("[data-retry]");
      if (!button) return;
      button.disabled = true;
      var kind = button.dataset.retry;
      var request = kind === "modules" ? loadModules(true)
        : kind === "superuser" ? loadSuperusers(true)
          : kind === "kpm" ? loadKpm(true)
            : kind === "settings" ? loadSettings() : loadAll(true);
      Promise.resolve(request).finally(function () {
        if (button.isConnected) button.disabled = false;
      });
    });
    el("refreshAll").addEventListener("click", function () {
      loadAll(true);
      if (state.view === "superuser") loadSuperusers(true);
      if (state.view === "kpm") loadKpm(true);
      if (state.view === "settings") loadSettings();
    });
    el("kpmRefresh").addEventListener("click", function () { loadKpm(true); });
    el("kpmSearch").addEventListener("input", function (event) {
      state.kpmQuery = event.target.value;
      renderKpmEntries();
    });
    el("kpmPolicy").addEventListener("change", function (event) {
      var enabled = event.target.checked;
      kpmPost("/api/kpm/policy", { enabled: enabled }).then(function (result) {
        if (result.success) {
          notify(enabled ? "KPM 加载已开启" : "KPM 加载已关闭");
          record("KPM 开关：" + (enabled ? "开" : "关"));
        } else {
          notify("开关操作失败：" + (result.error || "未知错误"), true);
        }
        return loadKpm(true);
      }).catch(function (error) {
        notify("开关操作失败：" + (error.message || "未知错误"), true);
        el("kpmPolicy").checked = !enabled;
      });
    });
    el("kpmImportPick").addEventListener("click", function () { el("kpmImportInput").click(); });
    el("kpmImportInput").addEventListener("change", function (event) {
      var file = event.target.files && event.target.files[0];
      if (file) openKpmImportDialog(file);
      event.target.value = "";
    });
    el("kpmExcludeOpen").addEventListener("click", openKpmExcludePanel);
    el("kpmExcludeClose").addEventListener("click", function () {
      el("kpmExcludePanel").classList.add("hidden");
    });
    el("kpmExcludeSearch").addEventListener("input", function (event) {
      state.kpmExcludeQuery = event.target.value;
      renderKpmExcludeList();
    });
    el("kpmDialogClose").addEventListener("click", closeSheet);
    el("themeSelect").addEventListener("change", function (event) {
      var theme = event.target.value;
      var previousTheme = state.theme;
      applyTheme(theme);
      kpmPost("/api/settings/theme", { theme: theme }, 20000).then(function () {
        notify("界面主题已切换：" + (theme === "auto" ? "跟随系统" : theme === "light" ? "浅色" : "深色"));
        record("界面主题：" + theme);
      }).catch(function (error) {
        applyTheme(previousTheme);
        el("themeSelect").value = previousTheme;
        notify("主题保存失败：" + (error.message || "未知错误"), true);
      });
    });
    [["refreshFeaturesRoot", "featureRowsRoot"], ["refreshFeaturesMount", "featureRowsMount"]].forEach(function (pair) {
      var button = el(pair[0]);
      if (!button) return;
      button.addEventListener("click", function () {
        button.disabled = true;
        loadFeatures().catch(function () { /* 卡片内已提示 */ })
          .finally(function () { button.disabled = false; });
      });
    });
    [["refreshBuiltinMount", "builtinMountBody"], ["refreshKPatch", "kpatchBody"],
      ["refreshPathmask", "pathmaskBody"], ["refreshCpuSpoof", "cpuSpoofBody"]].forEach(function (pair) {
      var button = el(pair[0]);
      if (!button) return;
      button.addEventListener("click", function () {
        button.disabled = true;
        el(pair[1]).innerHTML = '<div class="state">读取中……</div>';
        loadTools().catch(function () { /* 卡片内已提示 */ })
          .finally(function () { button.disabled = false; });
      });
    });
    el("launcherHidden").addEventListener("change", function (event) {
      var hidden = event.target.checked;
      if (hidden && !window.confirm("隐藏管理器桌面图标？\n\n隐藏后桌面不再显示管理器，只能从本控制台进入；需要恢复时在本页关掉这个开关即可。")) {
        event.target.checked = false;
        return;
      }
      kpmPost("/api/settings/launcher", { hidden: hidden }, 30000).then(function (result) {
        notify(hidden ? "管理器桌面图标已隐藏（可在此页恢复）" : "管理器桌面图标已恢复");
        record("桌面图标：" + (hidden ? "隐藏" : "显示"));
        renderLauncher(result && result.launcher);
      }).catch(function (error) {
        notify("桌面图标切换失败：" + (error.message || "未知错误"), true);
        return loadSettings();
      });
    });
    el("stealthToggle").addEventListener("change", function (event) {
      var input = event.target;
      if (input.checked) {
        input.checked = false;
        openStealthEditor(true);
        return;
      }
      input.checked = true;
      openStealthDisableEditor();
    });
    el("stealthCodeEdit").addEventListener("click", function () { openStealthEditor(false); });
    el("portMode").addEventListener("change", function (event) {
      var mode = event.target.value;
      var port = Number((el("fixedPortInput") || {}).value) || 0;
      if (mode === "fixed" && (port < 1024 || port > 65535)) {
        notify("固定端口请填 1024-65535", true);
        event.target.value = "random";
        return;
      }
      kpmPost("/api/settings/port", { mode: mode, port: port }, 20000).then(function () {
        notify(mode === "fixed" ? "已设为固定端口 " + port + "，重启服务后生效" : "已改回随机端口，重启服务后生效");
        record("端口模式：" + mode);
        return loadSettings();
      }).catch(function (error) {
        notify("端口设置失败：" + (error.message || "未知错误"), true);
        return loadSettings();
      });
    });
    el("applyPort").addEventListener("click", function () {
      var port = Number((el("fixedPortInput") || {}).value) || 0;
      if (port < 1024 || port > 65535) {
        notify("固定端口请填 1024-65535", true);
        return;
      }
      el("portMode").value = "fixed";
      kpmPost("/api/settings/port", { mode: "fixed", port: port }, 20000).then(function () {
        notify("已设为固定端口 " + port + "，重启服务后生效");
        record("固定端口：" + port);
        return loadSettings();
      }).catch(function (error) {
        notify("端口设置失败：" + (error.message || "未知错误"), true);
        return loadSettings();
      });
    });
    el("languageSelect").addEventListener("change", function (event) {
      var tag = event.target.value;
      updateManagerSetting("language", tag, event.target).then(function () {
        event.target.dataset.current = tag;
        record("管理器语言：" + tag);
      }).catch(function () { /* 状态已回滚 */ });
    });
    document.querySelectorAll("[data-manager-setting]").forEach(function (input) {
      input.addEventListener("change", function (event) {
        updateManagerSetting(event.target.dataset.managerSetting, event.target.checked, event.target)
          .catch(function () { /* 状态已回滚 */ });
      });
    });
    el("managerHomeTitleEdit").addEventListener("click", openManagerHomeTitleEditor);
    el("dynamicManagerOpen").addEventListener("click", openDynamicManagerEditor);
    el("rebootMenu").addEventListener("click", openRebootMenu);
    el("susfsShortcut").addEventListener("click", openNativeSusfsManager);
    el("rebootMenuSettings").addEventListener("click", openRebootMenu);
    el("assetFileInput").addEventListener("change", function (event) {
      var file = event.target.files && event.target.files[0];
      if (file) uploadAsset(file);
      event.target.value = "";
    });
    el("clearCache").addEventListener("click", clearCache);
    el("runDiagnostics").addEventListener("click", runDiagnostics);
    el("copyDiagnostics").addEventListener("click", copyDiagnostics);
    el("copyAddress").addEventListener("click", copyAddress);
    el("lkmCard").addEventListener("click", function () { loadDevice(true); });
    el("lkmCard").addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        loadDevice(true);
      }
    });
    el("autoStart").addEventListener("change", function (event) { changeAutoStart(event.target.checked); });
    el("autoRefreshToggle").addEventListener("change", function (event) { setAutoRefresh(event.target.checked); });
    var compact = flag("compact", false);
    var reduce = flag("reduceMotion", false);
    el("compactMode").checked = compact;
    el("reduceMotion").checked = reduce;
    document.body.classList.toggle("compact", compact);
    document.body.classList.toggle("reduce-motion", reduce);
    el("compactMode").addEventListener("change", function (event) {
      setFlag("compact", event.target.checked);
      document.body.classList.toggle("compact", event.target.checked);
    });
    el("reduceMotion").addEventListener("change", function (event) {
      setFlag("reduceMotion", event.target.checked);
      document.body.classList.toggle("reduce-motion", event.target.checked);
    });
    el("moduleSearch").addEventListener("input", function (event) {
      state.moduleQuery = event.target.value;
      renderModules();
    });
    el("moduleSort").addEventListener("change", function (event) {
      state.moduleSort = event.target.value;
      renderModules();
    });
    document.querySelectorAll("#moduleChips [data-filter]").forEach(function (chip) {
      chip.addEventListener("click", function () {
        state.moduleFilter = chip.dataset.filter;
        document.querySelectorAll("#moduleChips [data-filter]").forEach(function (item) {
          item.classList.toggle("active", item === chip);
        });
        renderModules();
      });
    });
    el("moduleRefreshButton").addEventListener("click", function () { loadModules(true); });
    el("superuserSearch").addEventListener("input", function (event) {
      state.appQuery = event.target.value;
      renderSuperusers();
    });
    el("superuserFilter").addEventListener("change", function (event) {
      state.appFilter = event.target.value;
      renderSuperusers();
    });
    el("appSort").addEventListener("change", function (event) {
      state.appSort = event.target.value;
      renderSuperusers();
    });
    el("superuserRefreshButton").addEventListener("click", function () { loadSuperusers(true); });
    el("modules").addEventListener("click", function (event) {
      var button = event.target.closest("button[data-module]");
      if (!button) return;
      changeModule(decodeURIComponent(button.dataset.module), button.dataset.action);
    });
    el("superusers").addEventListener("change", function (event) {
      var input = event.target.closest("input[data-app-uid]");
      if (!input) return;
      changeSuperuser(input.dataset.appUid, input.checked ? "grant" : "revoke");
    });
    el("sheetClose").addEventListener("click", closeConsole);
    el("sheetCancel").addEventListener("click", cancelJob);
    el("sheetCopy").addEventListener("click", function () {
      var text = el("consoleOut").textContent;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { notify("输出已复制"); }, function () { window.prompt("脚本输出", text); });
      } else {
        window.prompt("脚本输出", text);
      }
    });
    el("sheetRerun").addEventListener("click", function () {
      if (state.jobModule) runModuleAction(state.jobModule.id);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key !== "Escape") return;
      if (!el("sheet").classList.contains("hidden")) { closeConsole(); return; }
      if (!el("kpmDialog").classList.contains("hidden")) closeSheet();
    });
  }

  bind();
  bindCategories();
  var initial = (location.hash || "").replace("#", "");
  setView(
    initial === "superuser" || initial === "modules" || initial === "kpm" || initial === "settings"
      ? initial
      : "home",
    false
  );
  loadAll(false);
  record("网页管理器已加载");
})();
</script>
</body>
</html>
""""""

/**
 * 完整页面 = 骨架 + 主体。必须在运行时拼接：JVM 常量池单个 UTF-8 条目上限 65535 字节，
 * 而整页（中文 + 脚本）已超过该上限，编译期折叠会在使用点生成非法常量。
 */
internal val WEB_MANAGER_PAGE: String = listOf(
    WEB_MANAGER_PAGE_HEAD,
    WEB_MANAGER_PAGE_MARKUP,
    WEB_MANAGER_PAGE_SCRIPT_HEAD,
    WEB_MANAGER_PAGE_SCRIPT_TOOLS,
    WEB_MANAGER_PAGE_SCRIPT_TAIL,
).joinToString("")

/**
 * 浏览器导航到模块 WebUI 但服务端无法提供入口文档时显示的说明页。
 * `__MODULE_ID__` / `__REASON__` 由 WebManagerServer 替换（已做 HTML 转义）。
 */
internal const val WEB_MANAGER_WEBUI_ERROR_PAGE: String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>模块 WebUI 无法打开 · ApkeSU</title>
<style>
:root{color-scheme:light dark;--bg:#f5f6f8;--card:#fff;--ink:#161a1f;--muted:#6b7480;--line:#e3e6ea;--danger:#c0392b;--danger-soft:#fdecea}
@media(prefers-color-scheme:dark){:root{--bg:#101215;--card:#191c21;--ink:#e7eaee;--muted:#9aa4b0;--line:#2a2f36;--danger:#ff8f80;--danger-soft:#3c2320}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--ink);font:15px/1.6 system-ui,-apple-system,"Noto Sans SC",sans-serif;padding:24px}
.card{max-width:470px;background:var(--card);border:1px solid var(--line);border-radius:16px;padding:22px}
.mark{width:44px;height:44px;margin-bottom:12px;border-radius:13px;display:grid;place-items:center;font-size:22px;font-weight:700;background:var(--danger-soft);color:var(--danger)}
h1{margin:0 0 8px;font-size:17px}
p{margin:0 0 10px;color:var(--muted);font-size:13.5px}
.reason{margin:10px 0;padding:10px 12px;border-radius:10px;background:var(--danger-soft);color:var(--danger);font-size:13px;word-break:break-all}
button{margin-top:6px;min-height:38px;padding:8px 16px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:inherit;font-weight:600;cursor:pointer}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px}
</style>
</head>
<body>
<div class="card">
  <div class="mark">!</div>
  <h1>模块 WebUI 无法打开</h1>
  <p>模块：<code>__MODULE_ID__</code></p>
  <div class="reason">__REASON__</div>
  <p>常见原因：模块未启用、缺少 <code>webroot/index.html</code>、root shell 不可用。可回到网页管理器「设置 → 诊断」查看 root shell 与模块快照来源。</p>
  <button type="button" onclick="if (history.length > 1) { history.back(); } else { location.reload(); }">返回</button>
</div>
</body>
</html>
"""

/**
 * Served instead of raw JSON when a browser navigates to the console with an
 * expired token - the session token rotates every time the service starts, so a
 * bookmark or a browser-restored tab can hold a stale one.
 */
internal const val WEB_MANAGER_TOKEN_PAGE: String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>访问令牌已失效 · ApkeSU（web）</title>
<style>
:root{color-scheme:light dark;--bg:#f5f6f8;--card:#fff;--ink:#161a1f;--muted:#6b7480;--line:#e3e6ea;--danger:#c0392b;--danger-soft:#fdecea}
@media(prefers-color-scheme:dark){:root{--bg:#101215;--card:#191c21;--ink:#e7eaee;--muted:#9aa4b0;--line:#2a2f36;--danger:#ff8f80;--danger-soft:#3c2320}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--ink);font:15px/1.6 system-ui,-apple-system,"Noto Sans SC",sans-serif;padding:24px}
.card{max-width:420px;background:var(--card);border:1px solid var(--line);border-radius:16px;padding:22px;text-align:center}
.mark{width:44px;height:44px;margin:0 auto 12px;border-radius:13px;display:grid;place-items:center;font-size:20px;font-weight:700;background:var(--danger-soft);color:var(--danger)}
h1{font-size:17px;margin:0 0 8px}
p{margin:0;color:var(--muted);font-size:13.5px}
</style>
</head>
<body>
<div class="card">
<div class="mark" aria-hidden="true">!</div>
<h1>访问令牌已失效</h1>
<p>网页管理器每次运行都会生成新的访问令牌。请在 ApkeSU 管理器中重新打开「网页管理器」，或重新复制访问地址。</p>
</div>
</body>
</html>
"""
