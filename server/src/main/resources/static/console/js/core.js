/* 标签画像平台 · 控制台共享核心 */
/* ============================================================
 * 标签画像平台 · 管理控制台前端
 * 单页应用（hash 路由）；会话 Token 存 localStorage（跨标签页/浏览器重启保留，
 * 服务端 TTL 72h + 活跃滑动续期），经 X-Console-Token 头透传。
 * ============================================================ */
  var TOKEN_KEY = 'tp_console_token';
  var USER_KEY = 'tp_console_user';

  /* 读会话：优先 localStorage；老会话若只在 sessionStorage（升级前）则迁移过来 */
  function readSession(key) {
    var value = localStorage.getItem(key);
    if (value) return value;
    var legacy = sessionStorage.getItem(key);
    if (legacy) {
      localStorage.setItem(key, legacy);
      sessionStorage.removeItem(key);
    }
    return legacy || '';
  }

  var state = {
    token: readSession(TOKEN_KEY),
    user: JSON.parse(readSession(USER_KEY) || 'null'),
    route: 'tags',
    workTenant: localStorage.getItem('tp_work_tenant') || 'risk_control',
    permissions: [],
    menus: null
  };

  var PAGE_TITLES = {
    workbench: '打标工作台', 'my-tasks': '我的任务', tasks: '任务管理', 'task-monitor': '任务监控',
    dashboard: '数据看板', tags: '标签字典', 'tag-compose': '标签组合', 'tag-viewer': '标签查看', market: '标签市场', audit: '待审核队列', records: '打标记录',
    profile: '对象画像', tenants: '租户与凭证', users: '用户管理', roles: '角色与权限',
    logs: '审计日志', docs: '接口文档', monitor: '接入监控'
  };

  var PAGE_SUBTITLES = {
    workbench: '搜索对象、查看画像与待同步值，按标签字典动态打标',
    'my-tasks': '我的待办对象与任务进度',
    tasks: '任务创建、对象导入、派发、审核与流转',
    'task-monitor': '任务进度、逾期、审核积压与人员工作量',
    dashboard: '外部租户的开放 API 调用、打标写入与标签查询概览',
    tags: '标签定义、编辑、上下线、归档与写入开关',
    'tag-compose': '用已有标签编排派生标签，预检后发布；值在画像中实时计算',
    'tag-viewer': '按标签查看原始标签与组合标签的当前值、打标记录',
    market: '浏览可复用标签，申请使用或管理上架',
    audit: '敏感标签待审核值（只读）',
    records: '按对象与时间检索打标流水',
    profile: '查看对象当前标签画像',
    tenants: '租户配额与接入凭证管理',
    users: '控制台账号、角色与密码管理',
    roles: '内置角色可访问的页面与操作矩阵（只读）',
    logs: '平台操作审计追溯',
    docs: '开放 API 接入说明',
    monitor: 'Kafka 等写入通道的运行状态、未消费积压与死信'
  };

  var STATUS_BADGE = {
    effective: ['badge-green', '生效'], pending_audit: ['badge-orange', '待审核'],
    revoked: ['badge-red', '已作废'], expired: ['badge-gray', '已过期'],
    online: ['badge-green', '已上线'], offline: ['badge-gray', '已下线'],
    archived: ['badge-gray', '已归档'],
    reviewing: ['badge-blue', '审核中'], approved: ['badge-green', '已通过'],
    rejected: ['badge-red', '已驳回'], enabled: ['badge-green', '启用'],
    disabled: ['badge-red', '停用'], accepted: ['badge-green', '受理'],
    not_found: ['badge-gray', '不存在'], none: ['badge-gray', '未采集'],
    success: ['badge-green', '成功'], failed: ['badge-red', '失败'],
    draft: ['badge-gray', '草稿'], published: ['badge-blue', '已发布'],
    in_progress: ['badge-blue', '进行中'], completed: ['badge-green', '已完成'],
    verified: ['badge-purple', '已验收'],
    cancelled: ['badge-gray', '已取消'], pending: ['badge-orange', '待领取'],
    claimed: ['badge-blue', '已领取'], submitted: ['badge-orange', '待审核'],
    skipped: ['badge-gray', '已跳过'], pending_review: ['badge-orange', '待审核'],
    syncing: ['badge-blue', '待同步']
  };

  var ROLE_LABEL = {
    ADMIN: '管理员', TENANT_ADMIN: '租户管理员', AUDITOR: '审核员', VIEWER: '只读',
    TAGGER: '打标员', TASK_MANAGER: '任务管理员'
  };
  var ROLE_BADGE = {
    ADMIN: 'badge-blue', TENANT_ADMIN: 'badge-purple', AUDITOR: 'badge-orange', VIEWER: 'badge-gray',
    TAGGER: 'badge-green', TASK_MANAGER: 'badge-blue'
  };
  var PRIORITY_LABEL = { low: '低', normal: '普通', high: '高', urgent: '紧急' };
  var PRIORITY_BADGE = { low: 'badge-gray', normal: 'badge-gray', high: 'badge-orange', urgent: 'badge-red' };

  /* ---------------- 基础工具 ---------------- */
  function $(sel) { return document.querySelector(sel); }

  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function dash(value) {
    return (value === null || value === undefined || value === '') ? '--' : esc(value);
  }

  function badge(status) {
    if (!status) return '--';
    var item = STATUS_BADGE[status] || ['badge-gray', status];
    return '<span class="badge ' + item[0] + '">' + esc(item[1]) + '</span>';
  }

  function emptyState(title, desc) {
    return '<div class="empty">' +
      '<div class="empty-title">' + esc(title) + '</div>' +
      (desc ? '<div class="empty-desc">' + esc(desc) + '</div>' : '') +
      '</div>';
  }

  function tableWrap(html) {
    return '<div class="table-wrap">' + html + '</div>';
  }

  function tableLoading(cols, rows) {
    rows = rows || 6;
    var html = '<div class="table-wrap"><table class="skeleton-table" aria-hidden="true"><thead><tr>';
    for (var c = 0; c < cols; c++) html += '<th><span class="sk sk-head"></span></th>';
    html += '</tr></thead><tbody>';
    for (var r = 0; r < rows; r++) {
      html += '<tr>';
      for (var i = 0; i < cols; i++) {
        html += '<td><span class="sk" style="width:' + (42 + ((r * 7 + i * 13) % 46)) + '%"></span></td>';
      }
      html += '</tr>';
    }
    return html + '</tbody></table></div>';
  }

  var STAT_ICONS = {
    tag: '<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1.2"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
    pause: '<circle cx="12" cy="12" r="9"/><path d="M10 9v6M14 9v6"/>',
    building: '<path d="M4 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"/><path d="M16 9h2a2 2 0 0 1 2 2v10"/><path d="M8 7h4M8 11h4M8 15h4M8 19h4"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M10.85 12.15L19 4M18 5l2 2M15 8l2 2"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/>',
    users: '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c0-3.3 2.7-5.2 6-5.2s6 1.9 6 5.2"/><path d="M16 5.5a3 3 0 0 1 0 5.8"/><path d="M18 14.6c2 .7 3 2.4 3 4.4"/>',
    search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
    shield: '<path d="M12 3l7 3v5c0 5-3.5 8-7 10-3.5-2-7-5-7-10V6z"/><path d="M9.5 12l1.8 1.8 3.4-3.6"/>'
  };

  function statIcon(name) {
    var path = STAT_ICONS[name];
    if (!path) return '';
    return '<span class="stat-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' +
      path + '</svg></span>';
  }

  function statCards(items) {
    return '<div class="stat-row">' + items.map(function (it) {
      var raw = (it.value === null || it.value === undefined) ? '' : String(it.value);
      var empty = raw === '' || raw === '--' || raw === '-';
      return '<div class="stat' + (it.tone ? ' tone-' + it.tone : '') + '">' +
        '<div class="stat-top">' + (it.icon ? statIcon(it.icon) : '') +
        '<div class="stat-label">' + esc(it.label) + '</div></div>' +
        '<div class="stat-value' + (empty ? ' is-empty' : '') + '">' + esc(it.value) + '</div>' +
        (it.hint ? '<div class="stat-hint">' + esc(it.hint) + '</div>' : '') +
        '</div>';
    }).join('') + '</div>';
  }

  /**
   * 复制到剪贴板。
   * Clipboard API 只在安全上下文（HTTPS / localhost）可用；测试环境是 http://内网IP，
   * navigator.clipboard 为 undefined，必须回退到隐藏 textarea + execCommand 的传统方式，
   * 两者都不可用时弹窗展示明文让用户手动 Ctrl+C，而不是只报一句「复制失败」。
   */
  function copyText(text, okMessage) {
    var value = (text === null || text === undefined) ? '' : String(text);
    var ok = function () { toast(okMessage || '已复制到剪贴板', 'ok'); };
    if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
      navigator.clipboard.writeText(value).then(ok).catch(function () {
        // 权限被拒（部分浏览器要求用户手势）时再走传统复制
        if (legacyCopy(value)) { ok(); } else { showCopyFallback(value); }
      });
      return;
    }
    if (legacyCopy(value)) { ok(); } else { showCopyFallback(value); }
  }

  /** 传统复制：隐藏 textarea + execCommand('copy')，非安全上下文下依然可用 */
  function legacyCopy(value) {
    var ta = document.createElement('textarea');
    ta.value = value;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '0';
    ta.style.left = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    var copied = false;
    try {
      ta.focus();
      ta.select();
      ta.setSelectionRange(0, value.length);
      copied = document.execCommand('copy');
    } catch (e) {
      copied = false;
    }
    document.body.removeChild(ta);
    return copied;
  }

  /** 兜底弹窗：明文已选中，直接 Ctrl+C / ⌘+C 即可 */
  function showCopyFallback(value) {
    openModal('手动复制',
      '<div class="field"><label>浏览器拒绝写入剪贴板，内容已选中，按 Ctrl+C（macOS 为 ⌘+C）复制</label>' +
      '<textarea id="copy-fallback" class="mono" rows="3" readonly></textarea></div>',
      [{ text: '关闭', cls: 'btn-ghost', onClick: closeModal }], 'sm');
    var el = $('#copy-fallback');
    if (el) {
      el.value = value;
      el.focus();
      el.select();
    }
  }

  function setBusy(btn, busy) {
    if (!btn) return;
    btn.disabled = !!busy;
    btn.classList.toggle('is-loading', !!busy);
  }

  var toastTimer = null;
  function toast(message, type) {
    var el = $('#toast');
    el.textContent = message;
    el.className = 'toast ' + (type === 'err' ? 'err' : type === 'ok' ? 'ok' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.add('hidden'); }, 3200);
  }

  function openModal(title, bodyHtml, buttons, size) {
    $('#modal-title').textContent = title;
    $('#modal-body').innerHTML = bodyHtml;
    $('#modal .modal').classList.toggle('modal-lg', size === 'lg');
    $('#modal .modal').classList.toggle('modal-xl', size === 'xl');
    var foot = $('#modal-foot');
    foot.innerHTML = '';
    (buttons || []).forEach(function (b) {
      var btn = document.createElement('button');
      btn.className = 'btn ' + (b.cls || 'btn-ghost');
      btn.textContent = b.text;
      btn.onclick = b.onClick;
      foot.appendChild(btn);
    });
    $('#modal').classList.remove('hidden');
    document.body.classList.add('modal-open');
    setTimeout(function () {
      var first = $('#modal-body .ui-select-trigger, #modal-body .ui-date-trigger, #modal-body input:not([type="checkbox"]):not(.ui-native), #modal-body select:not(.ui-native)');
      if (first) first.focus();
    }, 40);
  }

  function closeModal() {
    $('#modal').classList.add('hidden');
    document.body.classList.remove('modal-open');
  }

  /* ---------------- API 封装 ---------------- */
  function api(path, options) {
    options = options || {};
    var headers = { 'Content-Type': 'application/json' };
    if (state.token) headers['X-Console-Token'] = state.token;
    if (isAdmin() && state.workTenant) headers['X-Console-Tenant'] = state.workTenant;
    var fetchOpts = {
      method: options.method || 'GET',
      headers: headers,
      body: options.body ? JSON.stringify(options.body) : undefined
    };
    // 可选超时：长耗时操作（如同步 latest）避免永远停在「执行中」
    var controller = null, timer = null;
    if (options.timeoutMs && typeof AbortController !== 'undefined') {
      controller = new AbortController();
      fetchOpts.signal = controller.signal;
      timer = setTimeout(function () { controller.abort(); }, options.timeoutMs);
    }
    return fetch('/api/v1/console' + path, fetchOpts)
      // 只兜住 fetch 本身的网络层失败（连接被拒、请求被取消、断网）。
      // HTTP 4xx/5xx 不会走这里，仍由下面的 JSON 解析给出业务错误码。
      .catch(function (e) {
        if (e && e.name === 'AbortError') {
          throw new Error('请求超时（超过 ' + Math.round(options.timeoutMs / 1000) +
            ' 秒），服务可能仍在执行，请稍后刷新确认结果');
        }
        throw new Error('网络连接中断或服务不可达，请检查网络后重试');
      })
      .then(function (resp) {
        return resp.json().catch(function () {
          return { code: -1, message: 'HTTP ' + resp.status + '（响应非 JSON）' };
        }).then(function (data) {
          if (data.code === 0) return data.data;
          // 会话失效 / 凭证错误：立即清理本地登录态
          if (data.code === 1009 || data.code === 401 || data.code === 4002 || data.code === 4004) {
            signOut(true);
          }
          throw new Error(data.message || ('请求失败 code=' + data.code));
        });
      })
      .finally(function () { if (timer) clearTimeout(timer); });
  }

  function qs(params) {
    var parts = [];
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v !== null && v !== undefined && v !== '') parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    });
    return parts.length ? '?' + parts.join('&') : '';
  }

  /* ---------------- 登录 / 登出 ---------------- */
  function loadPermissions() {
    return api('/auth/permissions').then(function (data) {
      state.permissions = (data && data.permissions) || [];
      state.menus = (data && data.menus) || [];
      applyNavPermissions();
    }).catch(function () {
      state.permissions = [];
      state.menus = null;
      applyNavPermissions();
    });
  }

  function loadAppVersion() {
    fetch('/api/v1/console/system/version').then(function (resp) { return resp.json(); })
      .then(function (data) {
        if (!data || data.code !== 0 || !data.data || !data.data.version) return;
        var version = String(data.data.version).replace(/^v/i, '');
        document.querySelectorAll('.app-version').forEach(function (el) {
          el.textContent = 'v' + version;
        });
      }).catch(function () { /* 版本信息不可用时保留占位符 */ });
  }

  function showLogin() {
    $('#login-view').classList.remove('hidden');
    $('#app-view').classList.add('hidden');
    setTimeout(function () {
      var input = $('#login-username');
      if (input && !input.value) input.focus();
    }, 60);
  }

  var navBadgeLoaded = false;
  var navBadgeLoading = false;

  function updateNavPending(count) {
    var el = $('#nav-pending-count');
    if (!el) return;
    var n = Number(count) || 0;
    if (n <= 0) {
      el.textContent = '0';
      el.classList.add('hidden');
      return;
    }
    el.textContent = n > 99 ? '99+' : String(n);
    el.classList.remove('hidden');
  }

  function markNavBadgeLoaded(count) {
    navBadgeLoaded = true;
    updateNavPending(count);
  }

  function loadNavBadges() {
    if (!state.token || !canViewAuditQueue() || navBadgeLoaded || navBadgeLoading) return;
    navBadgeLoading = true;
    api('/audit/pending').then(function (rows) {
      navBadgeLoaded = true;
      updateNavPending((rows || []).length);
    }).catch(function () { /* 无权限或接口异常时不展示徽标 */ })
      .finally(function () { navBadgeLoading = false; });
  }

  function showApp() {
    $('#login-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
    var name = state.user ? state.user.display_name || state.user.displayName : '';
    var role = state.user ? state.user.role : '';
    var roleText = role ? (ROLE_LABEL[role] || role) : '';
    $('#user-info').textContent = name + (roleText ? '（' + roleText + '）' : '');
    $('#user-avatar').textContent = (name || '?').charAt(0).toUpperCase();
    var initial = (name || '?').charAt(0).toUpperCase();
    var lg = $('#user-avatar-lg');
    if (lg) lg.textContent = initial;
    var ddName = $('#user-dd-name');
    if (ddName) ddName.textContent = name || '—';
    var ddRole = $('#user-dd-role');
    if (ddRole) ddRole.textContent = roleText || '—';
    applyNavPermissions();
    bindHealthChip();
    if (!navBadgeLoaded) loadNavBadges();
  }

  // 顶部用户菜单：点击开合 / 点外部关闭 / ESC 关闭 / 选中菜单项后关闭
  function setUserMenu(open) {
    var dd = $('#user-dropdown');
    var trigger = $('#user-trigger');
    if (!dd || !trigger) return;
    dd.classList.toggle('hidden', !open);
    trigger.setAttribute('aria-expanded', open ? 'true' : 'false');
    trigger.classList.toggle('is-open', open);
  }

  function bindUserMenu() {
    var trigger = $('#user-trigger');
    var dd = $('#user-dropdown');
    if (!trigger || !dd) return;
    trigger.addEventListener('click', function (e) {
      e.stopPropagation();
      setUserMenu(dd.classList.contains('hidden'));
    });
    dd.addEventListener('click', function (e) {
      e.stopPropagation();
      if (e.target.closest('.user-dd-item')) setUserMenu(false);
    });
    document.addEventListener('click', function () { setUserMenu(false); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') setUserMenu(false);
    });
  }

  // 顶栏健康徽章：每 30s 探测 /actuator/health —— UP 绿「服务正常」/ DOWN 红「服务异常」/ 不可达灰「连接中断」
  var healthTimer = null;

  function renderHealth(stateName) {
    var chip = $('#sys-chip');
    var text = $('#sys-chip-text');
    if (!chip || !text) return;
    chip.classList.remove('is-ok', 'is-bad', 'is-idle');
    if (stateName === 'ok') {
      chip.classList.add('is-ok');
      text.textContent = '服务正常';
      chip.title = '平台健康检查通过（每 30 秒自动检测）';
    } else if (stateName === 'bad') {
      chip.classList.add('is-bad');
      text.textContent = '服务异常';
      chip.title = '平台健康检查未通过（数据库/存储等依赖可能异常），请联系管理员';
    } else {
      chip.classList.add('is-idle');
      text.textContent = '连接中断';
      chip.title = '无法连接服务端，请检查网络或等待服务恢复';
    }
  }

  function probeHealth() {
    if (document.hidden) return;
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var abortTimer = ctrl ? setTimeout(function () { ctrl.abort(); }, 5000) : null;
    fetch('/actuator/health', { cache: 'no-store', signal: ctrl ? ctrl.signal : undefined })
      .then(function (resp) {
        if (!resp.ok) { renderHealth('bad'); return; }
        resp.json().then(function (data) {
          renderHealth(data && data.status === 'UP' ? 'ok' : 'bad');
        }).catch(function () { renderHealth('idle'); });
      })
      .catch(function () { renderHealth('idle'); })
      .finally(function () { if (abortTimer) clearTimeout(abortTimer); });
  }

  function bindHealthChip() {
    if (healthTimer) return;
    probeHealth();
    healthTimer = setInterval(probeHealth, 30000);
  }

  function applyNavPermissions() {
    var role = state.user ? state.user.role : '';
    var menuSet = state.menus ? state.menus : null;
    $('#nav').querySelectorAll('a[data-nav]').forEach(function (el) {
      var nav = el.getAttribute('data-nav');
      var visible;
      if (menuSet) {
        visible = menuSet.indexOf(nav) >= 0;
      } else {
        var roles = (el.getAttribute('data-roles') || '').split(',');
        visible = !el.hasAttribute('data-roles') || roles.indexOf(role) >= 0;
      }
      el.classList.toggle('hidden', !visible);
    });
    $('#nav').querySelectorAll('.nav-group').forEach(function (group) {
      var next = group.nextElementSibling;
      var hasVisible = false;
      while (next && !next.classList.contains('nav-group')) {
        if (next.tagName === 'A' && !next.classList.contains('hidden')) { hasVisible = true; break; }
        next = next.nextElementSibling;
      }
      group.classList.toggle('hidden', !hasVisible);
    });
  }

  function signIn(token, user) {
    state.token = token;
    state.user = user;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  var signOutHooks = [];

  function onSignOut(fn) {
    if (typeof fn === 'function') signOutHooks.push(fn);
  }

  function signOut(silent) {
    signOutHooks.forEach(function (fn) { try { fn(); } catch (e) { /* ignore */ } });
    state.token = '';
    state.user = null;
    state.permissions = [];
    state.menus = null;
    navBadgeLoaded = false;
    navBadgeLoading = false;
    updateNavPending(0);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    if (!silent) toast('已退出登录');
    showLogin();
  }

  function bindLogin(onAuthenticated) {
    var remembered = localStorage.getItem('tp_console_username');
    if (remembered) $('#login-username').value = remembered;

    $('#login-pw-toggle').addEventListener('click', function () {
      var input = $('#login-password');
      var visible = input.type === 'text';
      var label = visible ? '显示密码' : '隐藏密码';
      input.type = visible ? 'password' : 'text';
      this.classList.toggle('is-visible', !visible);
      this.setAttribute('aria-pressed', String(!visible));
      this.setAttribute('aria-label', label);
      this.title = label;
      input.focus();
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !$('#modal').classList.contains('hidden')) closeModal();
    });

    $('#login-form').addEventListener('submit', function (e) {
      e.preventDefault();
      var err = $('#login-error');
      err.classList.add('hidden');
      $('#login-submit').disabled = true;
      fetch('/api/v1/console/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: $('#login-username').value, password: $('#login-password').value })
      }).then(function (r) { return r.json(); }).then(function (data) {
        if (data.code !== 0) throw new Error(data.message || '登录失败');
        signIn(data.data.token, data.data);
        localStorage.setItem('tp_console_username', $('#login-username').value || data.data.username || '');
        $('#login-password').value = '';
        loadPermissions().finally(function () {
          showApp();
          if (!location.hash) location.hash = '#/tags';
          onAuthenticated();
          toast('欢迎回来，' + data.data.display_name, 'ok');
        });
      }).catch(function (e) {
        err.textContent = e.message;
        err.classList.remove('hidden');
      }).finally(function () { $('#login-submit').disabled = false; });
    });
    $('#btn-logout').addEventListener('click', function () {
      api('/auth/logout', { method: 'POST' }).catch(function () {}).finally(function () { signOut(); });
    });
    bindUserMenu();
    $('#modal-close').addEventListener('click', closeModal);
    $('#modal').addEventListener('click', function (e) { if (e.target === $('#modal')) closeModal(); });
  }

  function isAdmin() { return state.user && state.user.role === 'ADMIN'; }
  function isTenantAdmin() { return state.user && state.user.role === 'TENANT_ADMIN'; }
  function permissionsLoaded() { return !!(state.permissions && state.permissions.length); }
  function can(permission) {
    if (!permissionsLoaded()) return false;
    return state.permissions.indexOf(permission) >= 0;
  }
  function canByPermission(permission, fallbackRoles) {
    if (permissionsLoaded()) return can(permission);
    return !!(state.user && fallbackRoles.indexOf(state.user.role) >= 0);
  }
  function canManageUsers() {
    return canByPermission('op.user.manage', ['ADMIN', 'TENANT_ADMIN']);
  }
  function canViewAuditQueue() {
    return canByPermission('page.audit_pending', ['ADMIN', 'TENANT_ADMIN', 'AUDITOR']);
  }
  function canViewAuditLogs() {
    return canByPermission('page.logs', ['ADMIN', 'TENANT_ADMIN', 'AUDITOR']);
  }
  function canViewTenants() {
    return canByPermission('page.tenants', ['ADMIN', 'TENANT_ADMIN']);
  }
  function canViewRoles() {
    return canByPermission('page.roles', ['ADMIN', 'TENANT_ADMIN']);
  }
  function canTag() {
    return canByPermission('op.workbench.submit', ['ADMIN', 'TENANT_ADMIN', 'TAGGER', 'TASK_MANAGER']);
  }
  function canReview() {
    return canByPermission('op.task.review', ['ADMIN', 'TENANT_ADMIN', 'AUDITOR', 'TASK_MANAGER']);
  }
  function canManageTasks() {
    return canByPermission('op.task.manage', ['ADMIN', 'TENANT_ADMIN', 'TASK_MANAGER']);
  }
  function canAcceptTasks() {
    return canByPermission('op.task.accept', ['ADMIN', 'TENANT_ADMIN']);
  }
  function canManageTagDefinitions() {
    return canByPermission('op.tag.manage', ['ADMIN', 'TENANT_ADMIN']);
  }
  function workTenantId() {
    if (isAdmin()) return state.workTenant;
    return state.user ? (state.user.tenantId || state.user.tenant_id || '') : '';
  }
  function setWorkTenant(tenant) {
    state.workTenant = tenant || 'risk_control';
    localStorage.setItem('tp_work_tenant', state.workTenant);
  }



  function fillTenantSelect(selectId) {
    var sel = document.getElementById(selectId);
    if (!sel) return Promise.resolve();
    return api('/tenants').then(function (rows) {
      rows = rows || [];
      var html = rows.map(function (t) {
        return '<option value="' + esc(t.tenant_id) + '">' + esc(t.tenant_name) + ' · ' + esc(t.tenant_id) + '</option>';
      }).join('');
      if (!rows.some(function (t) { return t.tenant_id === state.workTenant; })) {
        html = '<option value="' + esc(state.workTenant) + '">' + esc(state.workTenant) + '</option>' + html;
      }
      sel.innerHTML = html;
      sel.value = state.workTenant;
    }).catch(function () {});
  }

  function fmtDt(value) {
    if (!value) return '--';
    return esc(String(value).replace('T', ' ').slice(0, 19));
  }

  function parseJsonValue(value, fallback) {
    if (!value) return fallback;
    try { return JSON.parse(value); } catch (e) { return fallback; }
  }


  export { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, markNavBadgeLoaded, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canAcceptTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fillTenantSelect, fmtDt, parseJsonValue };
