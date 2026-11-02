/** 控制台页面模块 · governance */
import * as core from '../core.js';
import { toBackendTime, renderPager } from '../shared.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 2：待审核队列
   * ============================================================ */
  function renderAudit() {
    if (!canViewAuditQueue()) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '待审核队列仅对平台管理员、租户管理员和审核员开放') + '</div>';
      return;
    }
    $('#content').innerHTML =
      '<div class="card">' +
      '  <div class="card-head">' +
      '    <div class="card-title">待审核标签值 <span class="count-chip" id="pending-count">--</span>' +
      '      <span class="badge badge-gray">只读</span></div>' +
      '    <div class="card-actions"><button class="btn btn-ghost btn-sm" id="pending-refresh">刷新</button></div>' +
      '  </div>' +
      '  <div class="card-body">' +
      '    <div class="notice notice-info">标签值审核已停用：平台只记录打标流水，' +
      '      <span class="code-inline">tag_value_latest</span> 由外部程序维护。</div>' +
      '  </div>' +
      '  <div id="pending-table"></div>' +
      '</div>';
    $('#pending-refresh').addEventListener('click', loadPending);
    loadPending();
  }

  function loadPending() {
    $('#pending-table').innerHTML = tableLoading(8, 5);
    api('/audit/pending').then(function (rows) {
      rows = rows || [];
      core.markNavBadgeLoaded(rows.length);
      if ($('#pending-count')) $('#pending-count').textContent = rows.length;
      if ($('#pending-count')) $('#pending-count').textContent = rows.length;
      if (!rows.length) {
        $('#pending-table').innerHTML = emptyState('暂无待审核标签值', '敏感标签写入后，待审核流水会出现在这里');
        return;
      }
      var html = '<table><thead><tr><th>租户</th><th>对象</th><th>标签</th><th>值</th>' +
        '<th>业务时间</th><th>操作员</th><th>来源</th><th>打标理由</th></tr></thead><tbody>';
      rows.forEach(function (v) {
        html += '<tr>' +
          '<td>' + esc(v.tenant_id) + '</td>' +
          '<td class="mono">' + esc(v.object_type) + ' / ' + esc(v.object_id) + '</td>' +
          '<td class="mono">' + esc(v.tag_code) + '</td>' +
          '<td class="cell-wrap"><b>' + esc(v.tag_value) + '</b></td>' +
          '<td class="nowrap">' + dash(v.biz_time) + '</td>' +
          '<td>' + dash(v.operator_name) + '<div class="cell-sub">' + dash(v.operator_id) + '</div></td>' +
          '<td>' + dash(v.source) + '</td>' +
          '<td class="cell-wrap">' + (v.remark
            ? '<span title="' + esc(v.remark) + '">' + esc(v.remark) + '</span>' : '--') + '</td></tr>';
      });
      $('#pending-table').innerHTML = tableWrap(html + '</tbody></table>');
    }).catch(function (e) {
      $('#pending-table').innerHTML = emptyState('加载失败', e.message);
    });
  }


  /* ============================================================
   * 页面 5：租户与凭证
   * ============================================================ */
  function renderTenants() {
    if (!canViewTenants()) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '租户与凭证仅对平台管理员和租户管理员开放') + '</div>';
      return;
    }
    $('#content').innerHTML =
      '<div id="tenant-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head">' +
      '    <div class="card-title">租户与接入凭证 <span class="count-chip" id="tenant-count">--</span></div>' +
      '    <div class="card-actions">' +
      '      <button class="btn btn-ghost btn-sm" id="tenant-refresh">刷新</button>' +
      (isAdmin() ? '      <button class="btn btn-primary btn-sm" id="tenant-create">＋ 新建租户</button>' : '') +
      '    </div>' +
      '  </div>' +
      '<div class="notice notice-info">点击租户行可管理该租户的全部接入凭证（Secret 仅展示一次）。</div>' +
      '  <div id="tenant-table"></div>' +
      '</div>';
    $('#tenant-refresh').addEventListener('click', loadTenants);
    if ($('#tenant-create')) $('#tenant-create').addEventListener('click', openCreateTenant);
    loadTenants();
  }

  function loadTenants() {
    $('#tenant-table').innerHTML = tableLoading(9, 4);
    var statsHost = $('#tenant-stats');
    if (statsHost) {
      statsHost.innerHTML = statCards([
        { label: '租户总数', value: '--', icon: 'building' }, { label: '已启用', value: '--', icon: 'check' },
        { label: '已停用', value: '--', icon: 'pause' }, { label: '接入凭证', value: '--', icon: 'key' }
      ]);
    }
    Promise.all([api('/tenants'), api('/credentials')]).then(function (res) {
      var rows = res[0] || [];
      var creds = res[1] || [];
      var credCount = {};
      creds.forEach(function (c) { credCount[c.tenant_id] = (credCount[c.tenant_id] || 0) + 1; });
      if ($('#tenant-count')) $('#tenant-count').textContent = rows.length;
      var enabled = rows.filter(function (t) { return t.status === 'enabled'; }).length;
      if (statsHost) {
        statsHost.innerHTML = statCards([
          { label: '租户总数', value: rows.length, hint: '全部接入系统', icon: 'building' },
          { label: '已启用', value: enabled, hint: '可正常调用', tone: 'green', icon: 'check' },
          { label: '已停用', value: rows.length - enabled, hint: '调用被拦截', tone: 'gray', icon: 'pause' },
          { label: '接入凭证', value: creds.length, hint: 'AppKey 总数', icon: 'key' }
        ]);
      }
      if (!rows.length) {
        $('#tenant-table').innerHTML = emptyState('暂无租户', '新建租户后为其生成接入凭证');
        return;
      }
      var html = '<table><thead><tr><th>tenant_id</th><th>租户名称</th><th>接入系统</th>' +
        '<th>状态</th><th>凭证数</th><th>写入 QPS</th><th>读取 QPS</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (t) {
        var op = '<span class="muted">--</span>';
        if (isAdmin()) {
          op = '<button class="btn btn-ghost btn-sm" data-gencred="' + esc(t.tenant_id) + '">＋ 凭证</button> ' +
            (t.status === 'enabled'
              ? '<button class="btn btn-ghost btn-sm" data-disable="' + esc(t.tenant_id) + '">停用</button>'
              : '<button class="btn btn-ghost btn-sm" data-enable="' + esc(t.tenant_id) + '">启用</button>');
        }
        html += '<tr class="row-click" data-row="' + esc(t.tenant_id) + '" title="点击管理该租户的接入凭证">' +
          '<td class="mono">' + esc(t.tenant_id) + '</td>' +
          '<td><div class="cell-strong">' + esc(t.tenant_name) + '</div></td>' +
          '<td>' + dash(t.access_system) + '</td>' +
          '<td>' + badge(t.status) + '</td>' +
          '<td><span class="count-chip">' + (credCount[t.tenant_id] || 0) + '</span></td>' +
          '<td>' + t.write_qps + '</td><td>' + t.read_qps + '</td>' +
          '<td class="nowrap">' + dash(t.created_at) + '</td>' +
          '<td class="nowrap"><div class="row-actions">' + op + '</div></td></tr>';
      });
      $('#tenant-table').innerHTML = tableWrap(html + '</tbody></table>');
      var byId = {};
      rows.forEach(function (t) { byId[t.tenant_id] = t; });
      $('#tenant-table').querySelectorAll('tr[data-row]').forEach(function (tr) {
        tr.addEventListener('click', function (e) {
          if (e.target.tagName === 'BUTTON') return;
          var t = byId[tr.getAttribute('data-row')];
          if (t) openTenantCredentials(t);
        });
      });
      $('#tenant-table').querySelectorAll('[data-gencred]').forEach(function (btn) {
        btn.addEventListener('click', function () { openCreateCredential(btn.getAttribute('data-gencred')); });
      });
      $('#tenant-table').querySelectorAll('[data-enable],[data-disable]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var enable = btn.hasAttribute('data-enable');
          var tenantId = btn.getAttribute(enable ? 'data-enable' : 'data-disable');
          changeTenantStatus(tenantId, enable ? 'enabled' : 'disabled');
        });
      });
    }).catch(function (e) {
      $('#tenant-table').innerHTML = emptyState('加载失败', e.message);
    });
  }

  /* 点击租户行：弹窗管理该租户的全部接入凭证 */
  function openTenantCredentials(tenant) {
    api('/credentials' + qs({ tenantId: tenant.tenant_id })).then(function (rows) {
      rows = rows || [];
      var html = '<div class="cred-meta">' + esc(tenant.tenant_name) +
        ' · 接入系统 ' + dash(tenant.access_system) +
        ' · ' + badge(tenant.status) +
        ' · 凭证 <b>' + rows.length + '</b> 个</div>';
      if (!rows.length) {
        html += emptyState('该租户暂无凭证', '点击下方「生成新凭证」创建 AppKey');
      } else {
        html += '<div class="table-wrap"><table><thead><tr><th>app_key</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
        rows.forEach(function (c) {
          var op = '<button class="btn btn-ghost btn-sm" data-cred-copy="' + esc(c.app_key) + '">复制</button>';
          if (isAdmin()) {
            op += ' <button class="btn btn-ghost btn-sm" data-cred-reset="' + esc(c.app_key) + '">重置密钥</button>' +
              (c.status === 'enabled'
                ? ' <button class="btn btn-ghost btn-sm" data-cred-disable="' + esc(c.app_key) + '">停用</button>'
                : ' <button class="btn btn-ghost btn-sm" data-cred-enable="' + esc(c.app_key) + '">启用</button>');
          }
          html += '<tr>' +
            '<td class="mono">' + esc(c.app_key) + '</td>' +
            '<td>' + badge(c.status) + '</td>' +
            '<td class="nowrap">' + dash(c.created_at) + '</td>' +
            '<td class="nowrap"><div class="row-actions">' + op + '</div></td></tr>';
        });
        html += '</tbody></table></div>';
      }
      var buttons = [{ text: '关闭', onClick: closeModal }];
      if (isAdmin()) {
        buttons.push({
          text: '＋ 生成新凭证', cls: 'btn-primary', onClick: function () { openCreateCredential(tenant.tenant_id); }
        });
      }
      openModal('接入凭证 · ' + tenant.tenant_id, html, buttons, 'lg');
      var scope = $('#modal-body');
      scope.querySelectorAll('[data-cred-copy]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          copyText(btn.getAttribute('data-cred-copy'), 'AppKey 已复制到剪贴板');
        });
      });
      scope.querySelectorAll('[data-cred-reset]').forEach(function (btn) {
        btn.addEventListener('click', function () { resetCredential(btn.getAttribute('data-cred-reset')); });
      });
      scope.querySelectorAll('[data-cred-enable],[data-cred-disable]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var enable = btn.hasAttribute('data-cred-enable');
          changeCredentialStatus(btn.getAttribute(enable ? 'data-cred-enable' : 'data-cred-disable'),
            enable ? 'enabled' : 'disabled',
            function () { openTenantCredentials(tenant); });
        });
      });
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function changeTenantStatus(tenantId, status) {
    api('/tenants/' + encodeURIComponent(tenantId) + '/status', { method: 'POST', body: { status: status } })
      .then(function () { toast(tenantId + ' 已' + (status === 'enabled' ? '启用' : '停用'), 'ok'); loadTenants(); })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  function openCreateTenant() {
    openModal('新建租户',
      '<div class="form-grid">' +
      '  <div class="field"><span>tenant_id <i class="req">*</i></span><input id="new-tenant-id" placeholder="^[a-z][a-z0-9_]{2,63}$"></div>' +
      '  <div class="field"><span>租户名称 <i class="req">*</i></span><input id="new-tenant-name" placeholder="如：保险理赔平台"></div>' +
      '  <div class="field"><span>接入系统</span><input id="new-tenant-system" placeholder="如：理赔作业台"></div>' +
      '  <div class="field"><span>写入 QPS 配额</span><input id="new-tenant-wqps" type="number" min="0" value="2000"></div>' +
      '  <div class="field"><span>读取 QPS 配额</span><input id="new-tenant-rqps" type="number" min="0" value="5000"></div>' +
      '</div>' +
      '<div class="form-tip">创建后点击租户行可管理该租户的接入凭证（AppKey / Secret，Secret 仅展示一次）。</div>',
      [
        { text: '取消', onClick: closeModal },
        {
          text: '创建', cls: 'btn-primary', onClick: function () {
            var btn = this;
            var tenantId = $('#new-tenant-id').value.trim();
            var name = $('#new-tenant-name').value.trim();
            if (!tenantId || !name) { toast('tenant_id 与租户名称不能为空', 'err'); return; }
            setBusy(btn, true);
            api('/tenants', { method: 'POST', body: {
              tenant_id: tenantId,
              tenant_name: name,
              access_system: $('#new-tenant-system').value.trim() || null,
              write_qps: parseInt($('#new-tenant-wqps').value || '2000', 10),
              read_qps: parseInt($('#new-tenant-rqps').value || '5000', 10)
            } }).then(function () {
              closeModal();
              toast('租户 ' + tenantId + ' 创建成功', 'ok');
              loadTenants();
            }).catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          }
        }
      ]);
  }

  function openCreateCredential(prefillTenantId) {
    openModal('新建接入凭证',
      '<div class="field"><span>租户 <i class="req">*</i></span><input id="new-cred-tenant" placeholder="risk_control" value="' + esc(prefillTenantId || '') + '"></div>' +
      '<div class="form-tip">生成后 Secret 仅展示一次，请立即保存到调用方配置。</div>',
      [
        { text: '取消', onClick: closeModal },
        {
          text: '生成', cls: 'btn-primary', onClick: function () {
            var btn = this;
            var tenantId = $('#new-cred-tenant').value.trim();
            if (!tenantId) { toast('租户不能为空', 'err'); return; }
            setBusy(btn, true);
            api('/credentials', { method: 'POST', body: { tenant_id: tenantId } }).then(function (c) {
              showSecretModal('凭证创建成功', c);
              loadTenants();
            }).catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          }
        }
      ]);
  }

  function resetCredential(appKey) {
    openModal('重置密钥', '<p>确认重置 <span class="mono">' + esc(appKey) + '</span> 的密钥？</p>' +
      '<p class="warn-text">重置后旧密钥立即失效，调用方需同步更新。</p>',
      [
        { text: '取消', onClick: closeModal },
        {
          text: '确认重置', cls: 'btn-danger', onClick: function () {
            var btn = this;
            setBusy(btn, true);
            api('/credentials/' + encodeURIComponent(appKey) + '/reset', { method: 'POST' })
              .then(function (c) { showSecretModal('密钥已重置', c); loadTenants(); })
              .catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          }
        }
      ]);
  }

  function showSecretModal(title, secret) {
    var appKey = secret.app_key || secret.appKey || '';
    openModal(title,
      '<div class="field"><span>app_key</span>' +
      '<div class="secret-row"><div class="secret-box">' + esc(appKey) + '</div>' +
      '<button type="button" class="btn btn-ghost btn-sm" id="copy-app-key">复制</button></div></div>' +
      '<div class="field" style="margin-top:16px"><span>secret（仅此一次展示）</span>' +
      '<div class="secret-box" id="secret-value">' + esc(secret.secret) + '</div></div>' +
      '<p class="warn-text">请立即复制保存。关闭后无法再次查看明文。</p>',
      [
        { text: '关闭', onClick: closeModal },
        {
          text: '复制 Secret', cls: 'btn-primary', onClick: function () {
            copyText($('#secret-value').textContent, 'Secret 已复制到剪贴板');
          }
        }
      ]);
    $('#copy-app-key').addEventListener('click', function () {
      copyText(appKey, 'AppKey 已复制到剪贴板');
    });
  }

  function changeCredentialStatus(appKey, status, afterRefresh) {
    api('/credentials/' + encodeURIComponent(appKey) + '/status', { method: 'POST', body: { status: status } })
      .then(function () {
        toast(appKey + ' 已' + (status === 'enabled' ? '启用' : '停用'), 'ok');
        loadTenants();
        if (afterRefresh) afterRefresh();
      })
      .catch(function (e) { toast(e.message, 'err'); });
  }


  /* ============================================================
   * 页面 6：审计日志
   * ============================================================ */
  var logsState = { page: 1, size: 20 };

  var LOG_ACTIONS = [
    ['TAG_REGISTER', '注册标签'], ['TAG_STATUS_CHANGE', '标签上下线'],
    ['TAG_UPDATE', '修改标签定义'], ['TAG_ROLLBACK', '回滚标签定义'],
    ['TAG_MARKET_LIST', '标签上架市场'], ['TAG_MARKET_APPLY', '申请市场标签'],
    ['TAG_MARKET_APPROVE', '市场申请通过'], ['TAG_MARKET_REJECT', '市场申请驳回'],
    ['TAG_ARCHIVE', '归档标签'], ['TAG_RESTORE', '恢复归档标签'],
    ['TAG_WRITABLE_CHANGE', '写入开关变更'], ['TAG_AUDIT_APPROVE', '审核通过'],
    ['TAG_AUDIT_REJECT', '审核驳回'], ['TENANT_CREATE', '创建租户'],
    ['TENANT_STATUS_CHANGE', '租户启停'], ['CREDENTIAL_CREATE', '创建凭证'],
    ['CREDENTIAL_RESET', '重置密钥'], ['CREDENTIAL_STATUS_CHANGE', '凭证启停'],
    ['USER_CREATE', '创建用户'], ['USER_STATUS_CHANGE', '用户启停'],
    ['USER_ROLE_CHANGE', '用户角色变更'], ['USER_PASSWORD_RESET', '重置用户密码'],
    ['USER_PASSWORD_CHANGE', '修改本人密码']
  ];

  function logActionLabel(code) {
    for (var i = 0; i < LOG_ACTIONS.length; i++) {
      if (LOG_ACTIONS[i][0] === code) return LOG_ACTIONS[i][1];
    }
    return '';
  }

  function renderLogs() {
    if (!canViewAuditLogs()) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '审计日志仅对平台管理员、租户管理员和审核员开放') + '</div>';
      return;
    }
    logsState.page = 1;
    var options = LOG_ACTIONS.map(function (a) {
      return '<option value="' + a[0] + '">' + a[1] + '</option>';
    }).join('');
    $('#content').innerHTML =
      '<div class="card">' +
      '  <div class="card-head">' +
      '    <div class="card-title">审计日志 <span class="count-chip" id="log-count">--</span></div>' +
      '    <div class="card-actions"><button class="btn btn-ghost btn-sm" id="log-refresh">刷新</button></div>' +
      '  </div>' +
      '  <div class="card-body">' +
      '    <div class="filter-grid">' +
      '      <div class="field"><span>动作类型</span><select id="log-action">' +
      '        <option value="">全部动作</option>' + options + '</select></div>' +
      '      <div class="field"><span>租户</span><input id="log-tenant" placeholder="全部租户"></div>' +
      '      <div class="field" data-type="datetime"><span>开始时间</span><input id="log-from" type="datetime-local"></div>' +
      '      <div class="field" data-type="datetime"><span>结束时间</span><input id="log-to" type="datetime-local"></div>' +
      '    </div>' +
      '    <div class="filter-actions">' +
      '      <span class="muted">记录标签治理、租户与凭证等关键操作，按时间倒序</span>' +
      '      <div class="row-actions">' +
      '        <button class="btn btn-ghost" id="log-reset">重置</button>' +
      '        <button class="btn btn-primary" id="log-search">查询</button>' +
      '      </div>' +
      '    </div>' +
      '  </div>' +
      '  <div id="logs-table"></div>' +
      '  <div class="pager" id="logs-pager"></div>' +
      '</div>';
    $('#log-search').addEventListener('click', function () { logsState.page = 1; loadLogs(); });
    $('#log-refresh').addEventListener('click', loadLogs);
    $('#log-reset').addEventListener('click', function () {
      ['log-action', 'log-tenant', 'log-from', 'log-to'].forEach(function (id) { $('#' + id).value = ''; });
      logsState.page = 1;
      loadLogs();
    });
    $('#content').querySelectorAll('.filter-grid input').forEach(function (input) {
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { logsState.page = 1; loadLogs(); }
      });
    });
    loadLogs();
  }

  function loadLogs() {
    $('#logs-table').innerHTML = tableLoading(9, 7);
    api('/audit-logs' + qs({
      actionType: $('#log-action').value, tenantId: $('#log-tenant').value.trim(),
      from: toBackendTime($('#log-from').value), to: toBackendTime($('#log-to').value),
      page: logsState.page, size: logsState.size
    })).then(function (page) {
      if ($('#log-count')) $('#log-count').textContent = page.total || 0;
      if (!page.records || !page.records.length) {
        $('#logs-table').innerHTML = emptyState('暂无审计日志', '调整动作、租户或时间范围后重试');
      } else {
        var html = '<table><thead><tr><th>时间</th><th>操作人</th><th>动作</th>' +
          '<th>租户</th><th>对象</th><th>标签</th><th>详情</th><th>结果</th><th>IP</th></tr></thead><tbody>';
        page.records.forEach(function (l) {
          var label = logActionLabel(l.action_type);
          var objectText = (l.object_type || l.object_id)
            ? dash(l.object_type) + ' / ' + dash(l.object_id) : '--';
          var detail = l.detail
            ? '<span class="cell-detail" title="' + esc(l.detail) + '">' + esc(l.detail) + '</span>'
            : '--';
          html += '<tr>' +
            '<td class="nowrap">' + dash(l.created_at) + '</td>' +
            '<td><div class="cell-strong">' + dash(l.actor) + '</div>' +
            (l.actor_role ? '<div class="cell-sub">' + esc(ROLE_LABEL[l.actor_role] || l.actor_role) + '</div>' : '') + '</td>' +
            '<td><div>' + esc(label || l.action_type || '--') + '</div>' +
            (label ? '<div class="cell-sub mono">' + esc(l.action_type) + '</div>' : '') + '</td>' +
            '<td>' + dash(l.tenant_id) + '</td>' +
            '<td class="mono">' + objectText + '</td>' +
            '<td class="mono">' + dash(l.tag_code) + '</td>' +
            '<td>' + detail + '</td>' +
            '<td>' + badge(l.result) + '</td>' +
            '<td class="mono">' + dash(l.source_ip) + '</td></tr>';
        });
        $('#logs-table').innerHTML = tableWrap(html + '</tbody></table>');
      }
      renderPager('#logs-pager', page.total, logsState, loadLogs);
    }).catch(function (e) {
      $('#logs-table').innerHTML = emptyState('加载失败', e.message);
    });
  }


  /* ============================================================
   * 页面 7：用户管理（仅管理员）
   * ============================================================ */
  var usersState = { rows: [] };

  function roleBadge(role) {
    return '<span class="badge ' + (ROLE_BADGE[role] || 'badge-gray') + '">' +
      esc(ROLE_LABEL[role] || role || '--') + '</span>';
  }

  function roleOptions(current, tenantScoped) {
    var roles = tenantScoped
      ? ['AUDITOR', 'VIEWER', 'TAGGER', 'TASK_MANAGER']
      : ['ADMIN', 'TENANT_ADMIN', 'AUDITOR', 'VIEWER', 'TAGGER', 'TASK_MANAGER'];
    return roles.map(function (role) {
      return '<option value="' + role + '"' + (role === current ? ' selected' : '') + '>' +
        ROLE_LABEL[role] + '</option>';
    }).join('');
  }

  function findUser(username) {
    for (var i = 0; i < usersState.rows.length; i++) {
      if (usersState.rows[i].username === username) return usersState.rows[i];
    }
    return null;
  }

  function userDisplayName(user) {
    return user ? (user.display_name || user.username || '--') : '--';
  }

  function randomPassword() {
    var chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
    var out = 'Aa1';
    var buffer = new Uint32Array(9);
    (window.crypto || window.msCrypto).getRandomValues(buffer);
    for (var i = 0; i < buffer.length; i++) out += chars.charAt(buffer[i] % chars.length);
    return out.split('').sort(function () { return Math.random() - 0.5; }).join('');
  }

  function validatePassword(password) {
    if (!password || password.length < 8 || password.length > 64) return '密码长度需为 8-64 位';
    if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) return '密码需同时包含字母和数字';
    return '';
  }

  function renderUsers() {
    if (!canManageUsers()) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '用户管理仅对平台管理员和租户管理员开放') + '</div>';
      return;
    }
    $('#content').innerHTML =
      '<div id="user-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head">' +
      '    <div class="card-title">控制台用户 <span class="count-chip" id="user-count">--</span></div>' +
      '    <div class="card-actions">' +
      '      <button class="btn btn-ghost btn-sm" id="user-refresh">刷新</button>' +
      '      <button class="btn btn-primary btn-sm" id="user-create">＋ 新建用户</button>' +
      '    </div>' +
      '  </div>' +
      '  <div class="notice notice-info">' +
      (isAdmin()
        ? '平台管理员可创建账号、分配租户管理员、调整角色与重置密码。每个租户最多一个租户管理员；停用、重置密码或调整角色后，该用户已登录的会话会立即失效。'
        : '租户管理员只能管理本租户的审核员、只读、打标员和任务管理员账号；不能创建或操作平台管理员/租户管理员。停用、重置密码或调整角色后，该用户已登录的会话会立即失效。') +
      '</div>' +
      '  <div id="user-table"></div>' +
      '</div>';
    $('#user-create').addEventListener('click', openCreateUser);
    $('#user-refresh').addEventListener('click', loadUsers);
    loadUsers();
  }

  function loadUsers() {
    $('#user-table').innerHTML = tableLoading(7, 5);
    api('/users').then(function (rows) {
      usersState.rows = rows || [];
      var total = usersState.rows.length;
      var enabled = usersState.rows.filter(function (u) { return u.status === 'enabled'; }).length;
      var taggers = usersState.rows.filter(function (u) { return u.role === 'TAGGER'; }).length;
      var managers = usersState.rows.filter(function (u) { return u.role === 'TASK_MANAGER'; }).length;
      var tenantAdmins = usersState.rows.filter(function (u) { return u.role === 'TENANT_ADMIN'; }).length;
      if ($('#user-stats')) {
        $('#user-stats').innerHTML = statCards([
          { label: '用户总数', value: total, hint: isAdmin() ? '全部租户账号' : '本租户账号', icon: 'user' },
          { label: '已启用', value: enabled, hint: '已停用 ' + (total - enabled), tone: 'green', icon: 'check' },
          { label: '打标员', value: taggers, hint: 'TAGGER', tone: 'amber', icon: 'tag' },
          { label: '租户管理员', value: tenantAdmins, hint: '任务管理员 ' + managers + ' 个', tone: 'blue', icon: 'shield' }
        ]);
      }
      if ($('#user-count')) $('#user-count').textContent = total;
      if (!total) {
        $('#user-table').innerHTML = emptyState('暂无控制台用户', '点击右上角新建用户');
        return;
      }
      var me = state.user ? state.user.username : '';
      var html = '<table><thead><tr><th>用户</th><th>角色</th><th>租户 / 操作员</th><th>班组</th><th>状态</th>' +
        '<th>创建时间</th><th>操作</th></tr></thead><tbody>';
      usersState.rows.forEach(function (u) {
        var self = u.username === me;
        var manageable = isAdmin() || (isTenantAdmin() && u.tenant_id === workTenantId()
          && ['ADMIN', 'TENANT_ADMIN'].indexOf(u.role) < 0);
        var actions = self
          ? '<span class="muted">通过右上角修改密码</span>'
          : manageable
            ? '<div class="row-actions">' +
              '<button class="btn btn-link btn-sm" data-reset-user="' + esc(u.username) + '">重置密码</button>' +
              '<button class="btn btn-link btn-sm" data-role-user="' + esc(u.username) + '">改角色</button>' +
              (u.status === 'enabled'
                ? '<button class="btn btn-danger btn-sm" data-status-user="' + esc(u.username) + '" data-status="disabled">停用</button>'
                : '<button class="btn btn-link btn-sm" data-status-user="' + esc(u.username) + '" data-status="enabled">启用</button>') +
              '</div>'
            : '<span class="muted">无权限</span>';
        html += '<tr>' +
          '<td><div class="cell-strong">' + esc(userDisplayName(u)) +
          (self ? ' <span class="badge badge-blue">当前登录</span>' : '') + '</div>' +
          '<div class="cell-sub mono">' + esc(u.username) + '</div></td>' +
          '<td>' + roleBadge(u.role) + '</td>' +
          '<td>' + dash(u.tenant_id) + '<div class="cell-sub mono">' + dash(u.operator_id) +
          (u.operator_name ? ' · ' + esc(u.operator_name) : '') + '</div></td>' +
          '<td>' + dash(u.team) + '<div class="cell-sub">' + dash(u.operator_org) + '</div></td>' +
          '<td>' + badge(u.status) + '</td>' +
          '<td class="nowrap">' + dash(u.created_at) + '</td>' +
          '<td>' + actions + '</td></tr>';
      });
      $('#user-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#user-table').querySelectorAll('[data-reset-user]').forEach(function (btn) {
        btn.addEventListener('click', function () { openResetPassword(btn.getAttribute('data-reset-user')); });
      });
      $('#user-table').querySelectorAll('[data-role-user]').forEach(function (btn) {
        btn.addEventListener('click', function () { openUserRole(btn.getAttribute('data-role-user')); });
      });
      $('#user-table').querySelectorAll('[data-status-user]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          confirmUserStatus(btn.getAttribute('data-status-user'), btn.getAttribute('data-status'));
        });
      });
    }).catch(function (e) {
      $('#user-table').innerHTML = emptyState('加载失败', e.message);
    });
  }

  function openCreateUser() {
    api('/tenants').then(function (tenants) {
      tenants = tenants || [];
      var tenantOptions = tenants.map(function (t) {
        return '<option value="' + esc(t.tenant_id) + '"' + (t.tenant_id === workTenantId() ? ' selected' : '') + '>' +
          esc(t.tenant_name) + ' · ' + esc(t.tenant_id) + '</option>';
      }).join('');
      var tenantField = isAdmin()
        ? '<div class="field"><span>租户</span><select id="new-tenant">' + tenantOptions + '</select></div>'
        : '<div class="field"><span>租户</span><div class="readonly-box">' + esc(workTenantId()) + '</div></div>';
      var body =
        '<div class="form-grid">' +
        '  <div class="field"><span>用户名</span><input id="new-username" autocomplete="off" placeholder="如 risk_analyst"></div>' +
        '  <div class="field"><span>显示名称</span><input id="new-display-name" autocomplete="off" placeholder="如 风控分析师"></div>' +
        '  <div class="field"><span>角色</span><select id="new-role">' + roleOptions('TAGGER', !isAdmin()) + '</select></div>' +
        tenantField +
        '  <div class="field"><span>操作员 ID</span><input id="new-operator" autocomplete="off" placeholder="如 CS0427"></div>' +
        '  <div class="field"><span>操作员姓名</span><input id="new-operator-name" autocomplete="off" placeholder="默认取显示名称"></div>' +
        '  <div class="field"><span>组织</span><input id="new-operator-org" autocomplete="off" placeholder="如 客服中心"></div>' +
        '  <div class="field"><span>班组</span><input id="new-team" autocomplete="off" placeholder="如 客服一组"></div>' +
        '  <div class="field"><span>初始密码</span><div class="input-row">' +
        '    <input id="new-password" type="text" autocomplete="new-password" placeholder="8-64 位，含字母和数字">' +
        '    <button type="button" class="btn btn-ghost" id="new-password-generate">生成</button></div></div>' +
        '  <div class="field"><span>确认初始密码</span><input id="new-password2" type="text" autocomplete="new-password"></div>' +
        '</div>' +
        '<p class="warn-text">打标员（TAGGER）与任务管理员（TASK_MANAGER）必须绑定租户和操作员 ID；操作员身份只取服务端会话，用于标签流水与审计。' +
        (isAdmin() ? 'TENANT_ADMIN 必须绑定租户，每个租户最多一个。' : '租户管理员只能创建本租户的审核员、只读、打标员和任务管理员。') + '</p>';
      openModal('新建用户', body, [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '创建用户', cls: 'btn-primary', onClick: function () {
            var username = $('#new-username').value.trim();
            var displayName = $('#new-display-name').value.trim();
            var role = $('#new-role').value;
            var password = $('#new-password').value;
            var password2 = $('#new-password2').value;
            var operatorId = $('#new-operator').value.trim();
            if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(username)) {
              toast('用户名需以小写字母开头，长度 3-64 位', 'err'); return;
            }
            if (!displayName) { toast('请填写显示名称', 'err'); return; }
            if ((role === 'TAGGER' || role === 'TASK_MANAGER') && !operatorId) {
              toast('打标员/任务管理员必须填写操作员 ID', 'err'); return;
            }
            var msg = validatePassword(password);
            if (msg) { toast(msg, 'err'); return; }
            if (password !== password2) { toast('两次输入的密码不一致', 'err'); return; }
            var btn = this;
            setBusy(btn, true);
            api('/users', { method: 'POST', body: {
              username: username, display_name: displayName, password: password, role: role,
              tenant_id: isAdmin() ? ($('#new-tenant') ? $('#new-tenant').value : null) : workTenantId(),
              operator_id: operatorId || null,
              operator_name: $('#new-operator-name').value.trim() || null,
              operator_org: $('#new-operator-org').value.trim() || null,
              team: $('#new-team').value.trim() || null
            } }).then(function (created) {
              closeModal();
              loadUsers();
              showPasswordModal('用户已创建', created, password);
              toast('用户已创建', 'ok');
            }).catch(function (e) {
              setBusy(btn, false);
              toast(e.message, 'err');
            });
          } }
      ], 'lg');
      $('#new-password-generate').addEventListener('click', function () {
        var password = randomPassword();
        $('#new-password').value = password;
        $('#new-password2').value = password;
      });
    }).catch(function (e) { toast('租户列表加载失败：' + e.message, 'err'); });
  }

  function openResetPassword(username) {
    var user = findUser(username);
    if (!user) return;
    var body =
      '<div class="field"><span>目标用户</span><div class="readonly-box">' +
      esc(userDisplayName(user)) + ' · <span class="mono">' + esc(user.username) + '</span></div></div>' +
      '<div class="field" style="margin-top:14px"><span>新密码</span><div class="input-row">' +
      '  <input id="reset-password" type="text" autocomplete="new-password" placeholder="8-64 位，含字母和数字">' +
      '  <button type="button" class="btn btn-ghost" id="reset-password-generate">生成</button></div></div>' +
      '<div class="field" style="margin-top:14px"><span>确认新密码</span>' +
      '  <input id="reset-password2" type="text" autocomplete="new-password"></div>' +
      '<p class="warn-text">提交后该用户全部登录会话立即失效，需使用新密码重新登录。</p>';
    openModal('重置密码 · ' + userDisplayName(user), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认重置', cls: 'btn-primary', onClick: function () {
          var password = $('#reset-password').value;
          var password2 = $('#reset-password2').value;
          var msg = validatePassword(password);
          if (msg) { toast(msg, 'err'); return; }
          if (password !== password2) { toast('两次输入的密码不一致', 'err'); return; }
          var btn = this;
          setBusy(btn, true);
          api('/users/' + encodeURIComponent(user.username) + '/password', {
            method: 'POST', body: { new_password: password }
          }).then(function () {
            closeModal();
            loadUsers();
            showPasswordModal('密码已重置', user, password);
          }).catch(function (e) {
            setBusy(btn, false);
            toast(e.message, 'err');
          });
        } }
    ]);
    $('#reset-password-generate').addEventListener('click', function () {
      var password = randomPassword();
      $('#reset-password').value = password;
      $('#reset-password2').value = password;
    });
  }

  function openUserRole(username) {
    var user = findUser(username);
    if (!user) return;
    var body =
      '<div class="field"><span>目标用户</span><div class="readonly-box">' +
      esc(userDisplayName(user)) + ' · <span class="mono">' + esc(user.username) + '</span></div></div>' +
      '<div class="field" style="margin-top:14px"><span>新角色</span><select id="user-role-select">' +
      roleOptions(user.role, !isAdmin()) + '</select></div>' +
      '<p class="warn-text">角色变更后，该用户当前登录会话将立即失效，需要用新角色重新登录。</p>';
    openModal('变更角色 · ' + userDisplayName(user), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认变更', cls: 'btn-primary', onClick: function () {
          var role = $('#user-role-select').value;
          if (role === user.role) { closeModal(); return; }
          var btn = this;
          setBusy(btn, true);
          api('/users/' + encodeURIComponent(user.username) + '/role', {
            method: 'POST', body: { role: role }
          }).then(function () {
            closeModal();
            toast('角色已更新', 'ok');
            loadUsers();
          }).catch(function (e) {
            setBusy(btn, false);
            toast(e.message, 'err');
          });
        } }
    ]);
  }

  function confirmUserStatus(username, status) {
    var user = findUser(username);
    if (!user) return;
    var enable = status === 'enabled';
    var body = '<p>确认' + (enable ? '启用' : '停用') + '账号 <b class="mono">' +
      esc(user.username) + '</b>？</p>' +
      (enable ? '' : '<p class="warn-text">停用后该用户将立即退出登录，且无法再次登录。</p>');
    openModal((enable ? '启用' : '停用') + '用户 · ' + userDisplayName(user), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: enable ? '确认启用' : '确认停用', cls: enable ? 'btn-primary' : 'btn-danger',
        onClick: function () {
          var btn = this;
          setBusy(btn, true);
          api('/users/' + encodeURIComponent(user.username) + '/status', {
            method: 'POST', body: { status: status }
          }).then(function () {
            closeModal();
            toast(enable ? '用户已启用' : '用户已停用', 'ok');
            loadUsers();
          }).catch(function (e) {
            setBusy(btn, false);
            toast(e.message, 'err');
          });
        } }
    ]);
  }

  function showPasswordModal(title, user, password) {
    var body =
      '<div class="field"><span>用户</span><div class="readonly-box">' +
      esc(userDisplayName(user)) + ' · <span class="mono">' + esc(user.username) + '</span></div></div>' +
      '<div class="field" style="margin-top:16px"><span>新密码（仅本次展示）</span>' +
      '  <div class="secret-box" id="new-password-value">' + esc(password) + '</div></div>' +
      '<p class="warn-text">该用户的登录会话已失效，请通过安全渠道告知新密码。关闭弹窗后将无法再次查看。</p>';
    openModal(title, body, [
      { text: '关闭', cls: 'btn-ghost', onClick: closeModal },
      { text: '复制密码', cls: 'btn-primary', onClick: function () { copyText(password, '新密码已复制到剪贴板'); } }
    ]);
  }

  function openChangeOwnPassword() {
    if (!state.user) return;
    var body =
      '<div class="field"><span>当前密码</span><input id="cp-old" type="password" autocomplete="current-password"></div>' +
      '<div class="field" style="margin-top:14px"><span>新密码</span>' +
      '  <input id="cp-new" type="password" autocomplete="new-password" placeholder="8-64 位，含字母和数字"></div>' +
      '<div class="field" style="margin-top:14px"><span>确认新密码</span>' +
      '  <input id="cp-new2" type="password" autocomplete="new-password"></div>' +
      '<p class="warn-text">修改成功后当前登录会话将失效，需要使用新密码重新登录。</p>';
    openModal('修改密码 · ' + userDisplayName(state.user), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认修改', cls: 'btn-primary', onClick: function () {
          var oldPassword = $('#cp-old').value;
          var newPassword = $('#cp-new').value;
          var newPassword2 = $('#cp-new2').value;
          if (!oldPassword) { toast('请输入当前密码', 'err'); return; }
          var msg = validatePassword(newPassword);
          if (msg) { toast(msg, 'err'); return; }
          if (newPassword !== newPassword2) { toast('两次输入的新密码不一致', 'err'); return; }
          if (oldPassword === newPassword) { toast('新密码不能与当前密码相同', 'err'); return; }
          var btn = this;
          setBusy(btn, true);
          api('/auth/password', { method: 'POST', body: {
            old_password: oldPassword, new_password: newPassword
          } }).then(function () {
            closeModal();
            var username = state.user ? state.user.username : '';
            signOut(true);
            if (username) localStorage.setItem('tp_console_username', username);
            toast('密码已修改，请使用新密码重新登录', 'ok');
          }).catch(function (e) {
            setBusy(btn, false);
            toast(e.message, 'err');
          });
        } }
    ]);
  }


  /* ============================================================
   * 页面 14：角色与权限（只读矩阵）
   * ============================================================ */
  var roleCatalogState = { data: null };

  function renderRoles() {
    if (!canViewRoles()) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '角色与权限仅对平台管理员和租户管理员开放') + '</div>';
      return;
    }
    $('#content').innerHTML = '<div class="card"><div class="card-head">' +
      '<div class="card-title">内置角色与权限</div></div>' +
      '<div class="card-body"><div class="empty loading"><span class="spinner"></span>' +
      '<div class="empty-title">加载中</div><div class="empty-desc">正在读取权限矩阵</div></div></div></div>';
    api('/roles/permissions').then(function (data) {
      roleCatalogState.data = data;
      renderRoleCatalog();
    }).catch(function (e) {
      $('#content').innerHTML = '<div class="card">' + emptyState('加载失败', e.message) + '</div>';
    });
  }

  function renderRoleCatalog() {
    var data = roleCatalogState.data;
    if (!data) return;
    var roles = data.roles || [];
    var pageGroups = data.page_groups || [];
    var operationGroups = data.operation_groups || [];
    var pageItems = pageGroups.reduce(function (n, g) { return n + (g.items || []).length; }, 0);
    var operationItems = operationGroups.reduce(function (n, g) { return n + (g.items || []).length; }, 0);
    var tenantRoles = roles.filter(function (r) { return r.tenant_scoped; }).length;
    var adminOnly = operationGroups.reduce(function (n, g) {
      return n + (g.items || []).filter(function (i) {
        return (i.roles || []).length === 1 && i.roles[0] === 'ADMIN';
      }).length;
    }, 0);

    var roleCards = roles.map(function (r) {
      var tags = ['<span class="role-tag">' + (r.tenant_scoped ? '租户级' : '平台级') + '</span>'];
      if (r.one_per_tenant) tags.push('<span class="role-tag warn">每租户限 1 个</span>');
      if (r.assignable_by_tenant_admin) tags.push('<span class="role-tag">租户管理员可分配</span>');
      return '<div class="role-card">' +
        '<div class="role-card-head"><div><div class="role-card-name">' + esc(r.name) + '</div>' +
        '<div class="role-card-code mono">' + esc(r.code) + '</div></div>' +
        '<span class="badge ' + (ROLE_BADGE[r.code] || 'badge-gray') + '">' + esc(r.name) + '</span></div>' +
        '<p class="role-card-desc">' + esc(r.description) + '</p>' +
        '<div class="role-tags">' + tags.join('') + '</div></div>';
    }).join('');

    var html =
      statCards([
        { label: '内置角色', value: roles.length, hint: '固定角色，不支持自定义', icon: 'shield' },
        { label: '权限点', value: pageItems + operationItems, hint: '页面 ' + pageItems + ' · 操作 ' + operationItems, icon: 'check' },
        { label: '租户级角色', value: tenantRoles, hint: '数据范围限定单租户', tone: 'blue', icon: 'user' },
        { label: '平台专属操作', value: adminOnly, hint: '仅平台管理员可执行', tone: 'amber', icon: 'key' }
      ]) +
      '<div class="card"><div class="card-head"><div class="card-title">角色说明 <span class="count-chip">' +
      roles.length + '</span></div><div class="card-actions"><span class="muted">内置角色 · 只读</span></div></div>' +
      '<div class="role-cards">' + roleCards + '</div></div>' +
      pageGroups.map(function (g) { return roleMatrixCard(g, roles); }).join('') +
      operationGroups.map(function (g) { return roleMatrixCard(g, roles); }).join('') +
      '<div class="notice notice-info role-catalog-note">本页为只读权限说明，不能在此修改角色或权限；用户分配仍使用内置角色。' +
      '租户管理员只能分配 <span class="code-inline">审核员 / 只读 / 打标员 / 任务管理员</span>，' +
      '且只能管理本租户账号；自定义角色与权限点将在后续版本评估。</div>';

    $('#content').innerHTML = html;
  }

  function roleMatrixCard(group, roles) {
    var items = group.items || [];
    var head = '<tr><th class="role-perm-col">' +
      '<span>' + esc(group.name) + '</span><small>' + esc(group.description || '') + '</small></th>';
    roles.forEach(function (r) {
      head += '<th class="role-matrix-col"><span>' + esc(r.name) + '</span>' +
        '<small class="mono">' + esc(r.code) + '</small></th>';
    });
    head += '</tr>';
    var body = items.map(function (item) {
      var row = '<tr><td class="role-perm-col"><div class="role-perm-name">' + esc(item.name) + '</div>' +
        '<div class="role-perm-desc">' + esc(item.description || '') + '</div></td>';
      roles.forEach(function (r) {
        var allowed = (item.roles || []).indexOf(r.code) >= 0;
        row += '<td class="role-perm-cell">' +
          (allowed ? '<span class="perm-yes" title="允许">✓</span>' : '<span class="perm-no" title="不允许">—</span>') +
          '</td>';
      });
      return row + '</tr>';
    }).join('');
    return '<div class="card role-matrix-card"><div class="card-head">' +
      '<div class="card-title">' + esc(group.name) + ' <span class="count-chip">' + items.length + '</span></div>' +
      '<div class="card-actions"><span class="perm-legend"><i class="perm-yes">✓</i>允许 ' +
      '<i class="perm-no">—</i>不允许</span></div></div>' +
      '<div class="role-matrix-wrap"><table class="role-matrix"><thead>' + head + '</thead><tbody>' + body +
      '</tbody></table></div></div>';
  }


  export { renderAudit, renderTenants, renderLogs, renderUsers, renderRoles, openChangeOwnPassword };
