/** 控制台页面模块 · analytics */
import * as core from '../core.js';
import { toBackendTime, renderPager } from '../shared.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 3：打标记录
   * ============================================================ */
  var recordsState = { page: 1, size: 20 };

  function renderRecords() {
    recordsState.page = 1;
    // 手动同步 latest：流水列表本身就是同步的输入，放在这里比放在只读的画像页更贴语义
    var syncBtn = can('op.latest.sync')
      ? '<button class="btn btn-ghost" id="rec-sync-latest" title="把当前筛选范围内的打标流水折叠成最新值写入 latest；手动执行，不自动">同步 latest</button>'
      : '';
    $('#content').innerHTML =
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">打标流水检索</div>' +
      '    <div class="card-actions"><span class="muted">按对象与时间范围查询</span></div></div>' +
      '  <div class="card-body">' +
      '    <div class="filter-grid cols-4">' +
      '      <div class="field"><span>租户 <i class="req">*</i></span><input id="rec-tenant" value="' + esc(workTenantId() || 'risk_control') + '"></div>' +
      '      <div class="field"><span>操作员</span><input id="rec-operator" placeholder="OP1001"></div>' +
      '      <div class="field"><span>对象类型</span><input id="rec-otype" placeholder="全部类型，如 driver"></div>' +
      '      <div class="field"><span>对象 ID</span><input id="rec-oid" placeholder="DRV00001"></div>' +
      '      <div class="field"><span>标签</span><input id="rec-tag" placeholder="risk_level"></div>' +
      '      <div class="field" data-type="datetime"><span>开始时间</span><input id="rec-from" type="datetime-local"></div>' +
      '      <div class="field" data-type="datetime"><span>结束时间</span><input id="rec-to" type="datetime-local"></div>' +
      '    </div>' +
      '    <div class="filter-actions">' +
      '      <span class="muted">时间范围留空表示不限</span>' +
      '      <div class="row-actions">' +
      syncBtn +
      '        <button class="btn btn-ghost" id="rec-reset">重置</button>' +
      '        <button class="btn btn-primary" id="rec-search">查询</button>' +
      '      </div>' +
      '    </div>' +
      '  </div>' +
      '  <div id="records-table"></div>' +
      '  <div class="pager" id="records-pager"></div>' +
      '</div>';
    $('#rec-search').addEventListener('click', function () { recordsState.page = 1; loadRecords(); });
    if ($('#rec-sync-latest')) {
      $('#rec-sync-latest').addEventListener('click', function () {
        openSyncLatest($('#rec-tenant').value.trim(), $('#rec-otype').value.trim(), $('#rec-oid').value.trim(),
          function () { recordsState.page = 1; loadRecords(); });
      });
    }
    $('#rec-reset').addEventListener('click', function () {
      $('#rec-tenant').value = workTenantId() || 'risk_control';
      ['rec-operator', 'rec-otype', 'rec-oid', 'rec-tag', 'rec-from', 'rec-to'].forEach(function (id) {
        $('#' + id).value = '';
      });
      recordsState.page = 1;
      loadRecords();
    });
    $('#rec-tenant').closest('.card').querySelectorAll('.filter-grid input').forEach(function (input) {
      input.addEventListener('keydown', function (e) { if (e.key === 'Enter') $('#rec-search').click(); });
    });
    loadRecords();
  }

  function loadRecords() {
    var tenant = $('#rec-tenant').value.trim();
    if (!tenant) { toast('租户不能为空', 'err'); return; }
    $('#records-table').innerHTML = tableLoading(8, 6);
    api('/tag-values/history' + qs({
      tenantId: tenant, operatorId: $('#rec-operator').value.trim(), objectType: $('#rec-otype').value.trim(),
      objectId: $('#rec-oid').value.trim(), tagCode: $('#rec-tag').value.trim(),
      from: toBackendTime($('#rec-from').value), to: toBackendTime($('#rec-to').value),
      page: recordsState.page, size: recordsState.size
    })).then(function (page) {
      if (!page.records || !page.records.length) {
        $('#records-table').innerHTML = emptyState('无打标记录', '调整租户、对象或时间范围后重试');
      } else {
        var html = '<table><thead><tr><th>对象</th><th>标签</th><th>值</th><th>状态</th>' +
          '<th>业务时间</th><th>操作时间</th><th>操作员</th><th>来源</th></tr></thead><tbody>';
        page.records.forEach(function (r) {
          html += '<tr>' +
            '<td class="mono">' + esc(r.object_type) + ' / ' + esc(r.object_id) + '</td>' +
            '<td class="mono">' + esc(r.tag_code) + '</td>' +
            '<td class="cell-wrap"><b>' + esc(r.tag_value) + '</b>' +
              (page.units && page.units[r.tag_code]
                ? ' <span class="cell-sub">单位 ' + esc(page.units[r.tag_code]) + '</span>' : '') + '</td>' +
            '<td>' + badge(r.status) + '</td>' +
            '<td class="nowrap">' + dash(r.biz_time) + '</td>' +
            '<td class="nowrap">' + dash(r.operate_time) + '</td>' +
            '<td>' + dash(r.operator_name) + '<div class="cell-sub">' + dash(r.operator_id) + '</div></td>' +
            '<td class="nowrap">' + dash(r.source) + '</td></tr>';
        });
        $('#records-table').innerHTML = tableWrap(html + '</tbody></table>');
      }
      renderPager('#records-pager', page.total, recordsState, loadRecords);
    }).catch(function (e) {
      $('#records-table').innerHTML = emptyState('加载失败', e.message);
    });
  }


  /* ============================================================
   * 页面 4：对象画像
   * ============================================================ */
  var profileState = { data: null, filter: 'all', keyword: '' };

  function hasNoValue(t) {
    return t.status === 'none' || t.tag_value === null || t.tag_value === undefined || t.tag_value === '';
  }

  function renderProfile() {
    $('#content').innerHTML =
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">对象画像查询</div>' +
      '    <div class="card-actions"><span class="muted">实时读取标签当前值</span></div></div>' +
      '  <div class="card-body">' +
      '    <div class="filter-grid">' +
      '      <div class="field"><span>租户 <i class="req">*</i></span><input id="pf-tenant" value="' + esc(workTenantId() || 'risk_control') + '"></div>' +
      '      <div class="field"><span>对象类型 <i class="req">*</i></span>' +
      '        <select id="pf-otype"><option value="driver">driver 司机</option>' +
      '        <option value="vehicle">vehicle 车辆</option></select></div>' +
      '      <div class="field"><span>对象 ID <i class="req">*</i></span>' +
      '        <div class="oid-combo" id="pf-oid-combo">' +
      '          <input id="pf-oid" value="DRV85001" placeholder="DRV85001" autocomplete="off" title="可直接输入，也可从已有对象中下拉选择">' +
      '          <svg class="oid-caret" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>' +
      '          <div class="oid-menu hidden" id="pf-oid-menu"></div>' +
      '        </div></div>' +
      '      <div class="field"><span>包含待审核</span>' +
      '        <label class="check-line"><input id="pf-pending" type="checkbox" checked>' +
      '        <span>显示 pending_audit 值</span></label></div>' +
      '    </div>' +
      '    <div class="filter-actions">' +
      '      <div class="quick-row"><span>示例</span>' +
      '        <button type="button" class="chip" data-example="driver|DRV85001">司机 DRV85001</button>' +
      '        <button type="button" class="chip" data-example="vehicle|VH10086">车辆 VH10086</button>' +
      '      </div>' +
      '      <button class="btn btn-primary" id="pf-search">查询画像</button>' +
      '    </div>' +
      '  </div>' +
      '  <div id="profile-result">' + emptyState('尚未查询', '输入对象 ID，或点击示例快速查看对象当前标签画像') + '</div>' +
      '</div>';
    $('#pf-search').addEventListener('click', loadProfile);
    $('#pf-otype').addEventListener('change', function () {
      $('#pf-oid').placeholder = this.value === 'driver' ? 'DRV85001' : 'VH10086';
      $('#pf-oid').value = this.value === 'driver' ? 'DRV85001' : 'VH10086';
      oidOptions = [];
      loadObjectOptions('');
    });
    $('#pf-tenant').addEventListener('change', function () { oidOptions = []; loadObjectOptions(''); });
    $('#pf-oid').addEventListener('focus', function () {
      if (!$('#pf-oid-menu').classList.contains('hidden')) return;
      if (oidOptions.length) renderOidMenu('');
      else loadObjectOptions('');
    });
    $('#pf-oid').addEventListener('click', function () {
      if ($('#pf-oid-menu').classList.contains('hidden')) renderOidMenu('');
    });
    $('#pf-oid').addEventListener('input', function () {
      var kw = this.value.trim();
      renderOidMenu(kw);
      clearTimeout(oidTimer);
      oidTimer = setTimeout(function () { loadObjectOptions(kw); }, 300);
    });
    $('#pf-oid-menu').addEventListener('mousedown', function (e) {
      var item = e.target.closest('.oid-menu-item');
      if (!item) return;
      e.preventDefault();
      selectOid(item.getAttribute('data-oid'));
    });
    $('#pf-oid').addEventListener('keydown', function (e) {
      var menu = $('#pf-oid-menu');
      var open = menu && !menu.classList.contains('hidden');
      var items = open ? Array.prototype.slice.call(menu.querySelectorAll('.oid-menu-item')) : [];
      if (e.key === 'ArrowDown' && open && items.length) {
        e.preventDefault();
        oidActiveIdx = Math.min(oidActiveIdx + 1, items.length - 1);
        paintOidActive(items);
        return;
      }
      if (e.key === 'ArrowUp' && open && items.length) {
        e.preventDefault();
        oidActiveIdx = Math.max(oidActiveIdx - 1, 0);
        paintOidActive(items);
        return;
      }
      if (e.key === 'Enter') {
        if (open && oidActiveIdx >= 0 && items[oidActiveIdx]) {
          e.preventDefault();
          selectOid(items[oidActiveIdx].getAttribute('data-oid'));
        } else {
          closeOidMenu();
          loadProfile();
        }
        return;
      }
      if (e.key === 'Escape') closeOidMenu();
    });
    if (!oidDocBound) {
      oidDocBound = true;
      document.addEventListener('click', function (e) {
        var combo = $('#pf-oid-combo');
        if (combo && !combo.contains(e.target)) closeOidMenu();
      });
    }
    $('#pf-tenant').addEventListener('keydown', function (e) { if (e.key === 'Enter') loadProfile(); });
    document.querySelectorAll('[data-example]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var parts = btn.getAttribute('data-example').split('|');
        $('#pf-otype').value = parts[0];
        $('#pf-tenant').value = workTenantId() || 'risk_control';
        $('#pf-oid').value = parts[1];
        $('#pf-oid').placeholder = parts[0] === 'driver' ? 'DRV85001' : 'VH10086';
        closeOidMenu();
        loadProfile();
      });
    });
    loadObjectOptions('');
  }

  /* 对象 ID 自建下拉（datalist 单击不展开且样式不可控）：缓存 + 本地过滤 + 远程关键字刷新 */
  var oidOptions = [];
  var oidActiveIdx = -1;
  var oidDocBound = false;
  var oidTimer = null;

  function closeOidMenu() {
    var menu = $('#pf-oid-menu');
    if (menu) menu.classList.add('hidden');
    var combo = $('#pf-oid-combo');
    if (combo) combo.classList.remove('is-open');
    oidActiveIdx = -1;
  }

  function paintOidActive(items) {
    items.forEach(function (el, i) { el.classList.toggle('is-active', i === oidActiveIdx); });
    var cur = items[oidActiveIdx];
    if (cur && cur.scrollIntoView) cur.scrollIntoView({ block: 'nearest' });
  }

  function renderOidMenu(filter) {
    var menu = $('#pf-oid-menu');
    if (!menu) return;
    var kw = (filter || '').trim().toLowerCase();
    var rows = oidOptions.filter(function (r) {
      return !kw || String(r.object_id || '').toLowerCase().indexOf(kw) >= 0;
    });
    if (!rows.length) {
      menu.innerHTML = '<div class="oid-menu-empty">' +
        (oidOptions.length ? '无匹配对象，可直接输入' : '该类型暂无对象数据，可直接输入') + '</div>';
      menu.classList.remove('hidden');
      var comboEl = $('#pf-oid-combo');
      if (comboEl) comboEl.classList.add('is-open');
      oidActiveIdx = -1;
      return;
    }
    menu.innerHTML = rows.slice(0, 200).map(function (r) {
      var time = r.last_seen ? String(r.last_seen).slice(5, 16).replace('T', ' ') : '';
      var meta = r.object_name ? esc(r.object_name) + (time ? ' · ' + esc(time) : '') : (time ? esc(time) : '');
      return '<div class="oid-menu-item" data-oid="' + esc(r.object_id) + '">' +
        '<span class="mono">' + esc(r.object_id) + '</span>' +
        (meta ? '<span class="oid-meta">' + meta + '</span>' : '') + '</div>';
    }).join('');
    menu.classList.remove('hidden');
    var comboEl2 = $('#pf-oid-combo');
    if (comboEl2) comboEl2.classList.add('is-open');
    oidActiveIdx = -1;
  }

  function selectOid(oid) {
    var input = $('#pf-oid');
    if (!input || !oid) return;
    input.value = oid;
    closeOidMenu();
    loadProfile();
  }

  /** 对象 ID 下拉选项：按租户 + 对象类型拉取已登记对象（关键字过滤，仅作输入辅助，允许自由输入） */
  function loadObjectOptions(keyword) {
    var tenant = $('#pf-tenant') ? $('#pf-tenant').value.trim() : '';
    if (!tenant) return;
    api('/objects/options' + qs({
      tenantId: tenant, objectType: $('#pf-otype').value, keyword: keyword || '', limit: 200
    })).then(function (rows) {
      oidOptions = rows || [];
      var input = $('#pf-oid');
      if (input && document.activeElement === input) renderOidMenu(input.value.trim());
    }).catch(function () { /* 选项拉取失败不影响手输查询 */ });
  }

  /**
   * 手动同步 latest：平台只写打标流水，latest 正常由外部同步程序维护；
   * 没有那个程序时，这里手工触发一次「流水折叠 → latest」，不做定时任务。
   *
   * @param onDone 同步完成后的回调（打标记录页刷新列表 / 画像页重新查询）
   */
  function openSyncLatest(tenant, otype, oid, onDone) {
    if (!tenant) { toast('请先填写租户', 'err'); return; }
    var after = function () { if (typeof onDone === 'function') onDone(); };
    var buttons = [{ text: '取消', cls: 'btn-ghost', onClick: closeModal }];
    if (oid) {
      buttons.push({
        text: '仅当前对象', cls: 'btn-ghost', onClick: function () {
          closeModal();
          runLatestSync(tenant, otype, oid, after);
        }
      });
    }
    buttons.push({
      text: oid ? '整个租户' : '同步整个租户', cls: 'btn-primary', onClick: function () {
        closeModal();
        runLatestSync(tenant, '', '', after);
      }
    });
    openModal('同步 latest',
      '<div class="notice notice-info">平台只写打标流水（history），画像读的是 <span class="code-inline">tag_value_latest</span>；' +
      '正常由外部同步程序维护。这里可以<b>手动触发一次</b>折叠：按「同一对象 + 同一标签取最后一条流水」写入 latest。</div>' +
      '<ul class="doc-list" style="margin-top:10px">' +
      '<li>只写 latest，<b>不改动 history</b>，可重复执行（同键覆盖）</li>' +
      '<li><b>仅本次执行</b>，平台不会定时同步</li>' +
      '<li>只按<b>租户 / 对象类型 / 对象 ID</b> 圈定范围，<b>不套用标签与时间筛选</b></li>' +
      '<li>已作废（revoked）的值原样写入，画像页会自动隐藏</li>' +
      '<li><b>通常 1–3 秒完成；执行期间请勿刷新或关闭页面</b>，否则浏览器会中断请求并提示同步失败</li>' +
      '<li>范围：' + (oid ? esc((otype || '全部类型') + ' / ' + oid) + '，或整个租户 ' : '整个租户 ') +
      '<span class="code-inline">' + esc(tenant) + '</span></li>' +
      '</ul>', buttons, 'sm');
  }

  function runLatestSync(tenant, otype, oid, onDone) {
    var syncBtn = document.getElementById('rec-sync-latest');
    if (syncBtn && syncBtn.disabled) return; // 防连点：一次只跑一个同步
    if (syncBtn) {
      syncBtn.disabled = true;
      syncBtn.textContent = '同步中…';
      syncBtn.title = '正在把打标流水折叠写入 latest，请勿刷新或关闭页面';
    }
    var restore = function () {
      // onDone 可能已重渲染整页，重新取按钮，避免持有失效节点
      var btn = document.getElementById('rec-sync-latest');
      if (btn) {
        btn.disabled = false;
        btn.textContent = '同步 latest';
        btn.title = '把当前筛选范围内的打标流水折叠成最新值写入 latest；手动执行，不自动';
      }
    };
    toast('正在同步 latest，请勿刷新页面…', 'ok');
    api('/tag-values/latest/sync' + qs({ tenantId: tenant, objectType: otype, objectId: oid }),
      { method: 'POST', timeoutMs: 60000 })
      .then(function (r) {
        var n = (r && r.synced) || 0;
        toast(n > 0 ? ('已同步 ' + n + ' 条到 latest') : '没有需要同步的打标流水', 'ok');
        if (typeof onDone === 'function') onDone();
      })
      .catch(function (e) { toast('同步失败：' + e.message, 'err'); })
      .finally(restore);
  }

  function loadProfile() {
    var tenant = $('#pf-tenant').value.trim(), otype = $('#pf-otype').value, oid = $('#pf-oid').value.trim();
    if (!tenant || !otype || !oid) { toast('租户 / 对象类型 / 对象 ID 均不能为空', 'err'); return; }
    $('#profile-result').innerHTML = '<div class="empty loading"><span class="spinner"></span>正在读取画像…</div>';
    api('/objects/' + encodeURIComponent(otype) + '/' + encodeURIComponent(oid) + '/profile' +
      qs({ tenantId: tenant, includePending: $('#pf-pending').checked }))
      .then(function (p) {
        profileState = { data: p, filter: 'all', keyword: '' };
        renderProfileResult();
      }).catch(function (e) {
        profileState = { data: null, filter: 'all', keyword: '' };
        $('#profile-result').innerHTML = emptyState('查询失败', e.message);
      });
  }

  function renderProfileResult() {
    var host = $('#profile-result');
    var p = profileState.data;
    if (!host || !p) return;
    var tags = p.tags || [];
    var counts = { all: tags.length, value: 0, pending: 0, none: 0 };
    tags.forEach(function (t) {
      if (t.status === 'pending_audit') counts.pending++;
      else if (hasNoValue(t)) counts.none++;
      else counts.value++;
    });

    var head =
      '<div class="profile-head">' +
      '  <div class="profile-avatar">' + esc((p.object_type || '?').charAt(0).toUpperCase()) + '</div>' +
      '  <div><div class="profile-id">' + esc(p.object_id) +
      (p.object_name ? ' <span class="profile-name">' + esc(p.object_name) + '</span>' : '') + '</div>' +
      '  <div class="profile-meta">' + esc(p.object_type) + ' · 租户 ' + esc(p.tenant_id) +
      '    · 共 ' + tags.length + ' 个标签</div></div>' +
      '  <div class="profile-stats">' +
      '    <div class="pstat"><b>' + counts.value + '</b><span>有值</span></div>' +
      '    <div class="pstat pending"><b>' + counts.pending + '</b><span>待审核</span></div>' +
      '    <div class="pstat none"><b>' + counts.none + '</b><span>未采集</span></div>' +
      '  </div>' +
      '</div>';

    // 空画像时给出「为什么空、去哪儿补」的指引：latest 由外部同步程序维护，控制台只写流水
    if (!tags.length) {
      host.innerHTML = head +
        emptyState('暂无最新值', 'latest 里还没有该对象的值。若刚打过标，可到「打标记录」页点「同步 latest」把流水折叠过来。');
      return;
    }

    host.innerHTML = head +
      '<div class="profile-toolbar">' +
      '  <div class="chip-row" id="pf-filters">' +
      profileChip('all', '全部', counts.all) + profileChip('value', '有值', counts.value) +
      profileChip('pending', '待审核', counts.pending) + profileChip('none', '未采集', counts.none) +
      '  </div>' +
      '  <input class="input-sm" id="pf-keyword" placeholder="过滤标签 / tag_code" value="' + esc(profileState.keyword) + '">' +
      '</div>' +
      '<div class="tag-grid" id="pf-grid"></div>';

    host.querySelectorAll('[data-pf-filter]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        profileState.filter = btn.getAttribute('data-pf-filter');
        host.querySelectorAll('[data-pf-filter]').forEach(function (b) {
          b.classList.toggle('active', b === btn);
        });
        renderProfileGrid();
      });
    });
    $('#pf-keyword').addEventListener('input', function () {
      profileState.keyword = this.value;
      renderProfileGrid();
    });
    renderProfileGrid();
  }

  function profileChip(key, label, count) {
    return '<button type="button" class="chip' + (profileState.filter === key ? ' active' : '') +
      '" data-pf-filter="' + key + '">' + label + ' <b>' + count + '</b></button>';
  }

  function renderProfileGrid() {
    var grid = $('#pf-grid');
    var p = profileState.data;
    if (!grid || !p) return;
    var keyword = profileState.keyword.trim().toLowerCase();
    var items = (p.tags || []).filter(function (t) {
      if (profileState.filter === 'value' && (t.status === 'pending_audit' || hasNoValue(t))) return false;
      if (profileState.filter === 'pending' && t.status !== 'pending_audit') return false;
      if (profileState.filter === 'none' && !hasNoValue(t)) return false;
      if (keyword) {
        var text = ((t.tag_name || '') + ' ' + (t.tag_code || '')).toLowerCase();
        if (text.indexOf(keyword) < 0) return false;
      }
      return true;
    });
    if (!items.length) {
      grid.innerHTML = emptyState('没有匹配的标签', '调整筛选条件或关键字后重试');
      return;
    }
    grid.innerHTML = items.map(profileTagCard).join('');
  }

  function profileTagCard(t) {
    var noValue = hasNoValue(t);
    var pending = t.status === 'pending_audit';
    var valueHtml;
    if (noValue) {
      valueHtml = '<div class="tag-value none">暂无数据</div>';
    } else if (t.value_multiple) {
      var parts = String(t.tag_value == null ? '' : t.tag_value).split(',')
        .map(function (v) { return v.trim(); }).filter(Boolean);
      valueHtml = '<div class="tag-value-chips" title="' + esc(t.tag_value) + '">' +
        parts.map(function (v) { return '<span class="tag-value-chip">' + esc(v) + '</span>'; }).join('') +
        '</div>';
    } else {
      valueHtml = '<div class="tag-value" title="' + esc(t.tag_value) + '">' + esc(t.tag_value) + '</div>';
    }
    var operator = t.operator_name
      ? esc(t.operator_name) + (t.operator_id ? '（' + esc(t.operator_id) + '）' : '')
      : dash(t.operator_id);
    var source = t.derived ? '查询期计算' : dash(t.source || t.source_mode);
    if (t.confidence !== null && t.confidence !== undefined && t.confidence !== '') {
      source += ' · 置信度 ' + esc(t.confidence);
    }
    var statusClass = noValue ? ' none'
      : pending ? ' pending'
      : t.status === 'revoked' ? ' revoked' : ' effective';
    var expireText = t.expire_time ? dash(t.expire_time) : (noValue ? '--' : '长期有效');
    return '<article class="tag-card' + statusClass + '">' +
      '<div class="tag-top"><div>' +
      '  <div class="tag-name">' + esc(t.tag_name || t.tag_code) +
      (t.derived ? ' <span class="badge badge-blue">派生</span>' : '') + '</div>' +
      '  <div class="tag-code">' + esc(t.tag_code) +
      (t.value_type ? ' · ' + esc(t.value_type) : '') + (t.value_multiple ? ' · 多选' : '') +
      (t.unit ? ' · 单位 ' + esc(t.unit) : '') + '</div>' +
      '</div>' + badge(t.status) + '</div>' +
      valueHtml +
      '<div class="tag-meta">' +
      '  <div class="tag-meta-row"><b>业务时间</b><span>' + dash(t.biz_time) + '</span></div>' +
      '  <div class="tag-meta-row"><b>操作员</b><span>' + operator + '</span></div>' +
      '  <div class="tag-meta-row"><b>来源</b><span>' + source + '</span></div>' +
      '  <div class="tag-meta-row"><b>有效期至</b><span>' + expireText + '</span></div>' +
      (t.remark ? '  <div class="tag-meta-row"><b>备注</b><span>' + esc(t.remark) + '</span></div>' : '') +
      '</div></article>';
  }


  /* ============================================================
   * 页面 8：接口文档（仅标签推送 POST + 画像查询 GET）
   * ============================================================ */
  function canonicalQueryOf(query) {
    return String(query || '').split('&').filter(Boolean).map(function (pair) {
      var i = pair.indexOf('=');
      var k = decodeURIComponent(i < 0 ? pair : pair.slice(0, i));
      var v = decodeURIComponent(i < 0 ? '' : pair.slice(i + 1));
      return { k: k, v: v };
    }).sort(function (a, b) {
      if (a.k !== b.k) return a.k < b.k ? -1 : 1;
      return a.v < b.v ? -1 : (a.v > b.v ? 1 : 0);
    }).map(function (x) { return x.k + '=' + x.v; }).join('&');
  }

  function docCurl(method, path, query, body) {
    var lines = [
      'BASE_URL="' + origin + '"',
      'APP_KEY="AK_RC_8f2a91"',
      'SECRET="rc-secret-8f2a91-demo"',
      'METHOD="' + method + '"',
      'PATH_="' + path + '"',
      'QUERY="' + (query || '') + '"',
      '# 签名使用 canonical query（key/value 字典序、解码原值）；URL 仍用 QUERY 原串',
      'QUERY_CANONICAL="' + canonicalQueryOf(query) + '"',
      body ? "BODY='" + body + "'" : 'BODY=""',
      'TS=$(date +%s%3N); NONCE=$(openssl rand -hex 16)',
      'SIGN=$(printf "%s\\n%s\\n%s\\n%s\\n%s\\n%s" "$METHOD" "$PATH_" "$QUERY_CANONICAL" "$BODY" "$TS" "$NONCE" \\',
      '  | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)',
      'curl -s -X "$METHOD" "${BASE_URL}${PATH_}${QUERY:+?$QUERY}" \\'
    ];
    var headers = '  -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: ${TS}" -H "X-Nonce: ${NONCE}" -H "X-Signature: ${SIGN}"';
    if (body) {
      lines.push(headers + ' \\');
      lines.push('  -H "Content-Type: application/json" -d "$BODY"');
    } else {
      lines.push(headers);
    }
    return lines.join('\n');
  }

  function codeLang(text) {
    // 探测时剥除整行 // 注释（示例允许注释行辅助说明）
    var probe = (text || '').split('\n').filter(function (line) { return !/^\s*\/\//.test(line); }).join('\n').trim();
    if (probe.charAt(0) === '{' || probe.charAt(0) === '[') {
      try { JSON.parse(probe); return 'json'; } catch (e) { /* not json */ }
    }
    if (/(^|\n)\s*(curl|BASE_URL=|APP_KEY=|SECRET=|PATH_=)/.test(text || '')) return 'bash';
    return 'text';
  }

  function highlightJson(text) {
    var out = '';
    var i = 0;
    while (i < text.length) {
      var ch = text.charAt(i);
      if (ch === '/' && text.charAt(i + 1) === '/') {
        var nl = text.indexOf('\n', i);
        if (nl === -1) nl = text.length;
        out += '<span class="tok-comment">' + esc(text.slice(i, nl)) + '</span>';
        i = nl;
        continue;
      }
      if (ch === '"') {
        var j = i + 1;
        while (j < text.length) {
          if (text.charAt(j) === '\\') { j += 2; continue; }
          if (text.charAt(j) === '"') { j += 1; break; }
          j += 1;
        }
        var token = text.slice(i, j);
        var k = j;
        while (k < text.length && /\s/.test(text.charAt(k))) k += 1;
        var cls = text.charAt(k) === ':' ? 'tok-key' : 'tok-str';
        out += '<span class="' + cls + '">' + esc(token) + '</span>';
        i = j;
        continue;
      }
      if (ch === '-' || (ch >= '0' && ch <= '9')) {
        var m = /^-?\d+(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i));
        if (m && m[0].length) {
          out += '<span class="tok-num">' + m[0] + '</span>';
          i += m[0].length;
          continue;
        }
      }
      if (text.startsWith('true', i) || text.startsWith('false', i) || text.startsWith('null', i)) {
        var lit = text.startsWith('true', i) ? 'true' : (text.startsWith('false', i) ? 'false' : 'null');
        out += '<span class="tok-lit">' + lit + '</span>';
        i += lit.length;
        continue;
      }
      out += esc(ch);
      i += 1;
    }
    return out;
  }

  function highlightBash(text) {
    return (text || '').split('\n').map(function (line) {
      if (/^\s*#/.test(line)) {
        return '<span class="tok-comment">' + esc(line) + '</span>';
      }
      var out = '';
      var i = 0;
      while (i < line.length) {
        var ch = line.charAt(i);
        if (ch === '"') {
          var j = i + 1;
          while (j < line.length) {
            if (line.charAt(j) === '\\') { j += 2; continue; }
            if (line.charAt(j) === '"') { j += 1; break; }
            j += 1;
          }
          out += '<span class="tok-str">' + esc(line.slice(i, j)) + '</span>';
          i = j;
          continue;
        }
        if (ch === '#' && (i === 0 || /\s/.test(line.charAt(i - 1)))) {
          out += '<span class="tok-comment">' + esc(line.slice(i)) + '</span>';
          break;
        }
        if (ch === '-' && /[A-Za-z-]/.test(line.charAt(i + 1) || '')) {
          var fm = /^-{1,2}[\w-]+/.exec(line.slice(i));
          if (fm) {
            out += '<span class="tok-flag">' + fm[0] + '</span>';
            i += fm[0].length;
            continue;
          }
        }
        out += esc(ch);
        i += 1;
      }
      return out;
    }).join('\n');
  }

  function docCode(id, text, lang) {
    var l = lang || codeLang(text);
    var body = l === 'json' ? highlightJson(text) : (l === 'bash' ? highlightBash(text) : esc(text));
    return '<div class="code-wrap">' +
      '<button type="button" class="code-copy" data-copy="' + id + '" aria-label="复制代码">' +
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<rect x="5.5" y="5.5" width="8" height="8" rx="1.6"/><path d="M10.5 3.5v-1a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h1"/></svg>' +
      '<span>复制</span></button>' +
      '<pre class="code-block' + (l !== 'text' ? ' is-' + l : '') + '" id="' + id + '">' + body + '</pre>' +
      '</div>';
  }

  function bindDocCopy(root) {
    root.querySelectorAll('[data-copy]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var target = document.getElementById(btn.getAttribute('data-copy'));
        copyText(target ? target.textContent : '', '已复制到剪贴板');
        if (btn.classList.contains('is-copied')) return;
        var label = btn.querySelector('span');
        var original = label ? label.textContent : '';
        if (label) label.textContent = '已复制';
        btn.classList.add('is-copied');
        setTimeout(function () {
          if (label) label.textContent = original || '复制';
          btn.classList.remove('is-copied');
        }, 1600);
      });
    });
  }

  var docsState = { open: 'auth', keyword: '' };
  var docsApis = [];

  function docSection(title, content) {
    return '<div class="doc-section"><h4>' + title + '</h4>' + content + '</div>';
  }

  /* ---------------- 接口文档导出 Markdown ---------------- */

  /** HTML 片段 → Markdown（DOM 遍历式，覆盖 doc-section / doc-note / doc-table / code-block / doc-list / doc-tip） */
  function docsHtmlToMarkdown(html) {
    var root = new DOMParser().parseFromString('<div id="__md_root">' + html + '</div>', 'text/html')
      .getElementById('__md_root');
    return blocksToMd(root).replace(/\n{3,}/g, '\n\n').trim();
  }

  function blocksToMd(node) {
    var out = [];
    Array.prototype.forEach.call(node.childNodes, function (child) {
      var md = blockMd(child);
      if (md) out.push(md);
    });
    return out.join('\n\n');
  }

  function blockMd(el) {
    if (el.nodeType === 3) return el.textContent.trim();
    if (el.nodeType !== 1) return '';
    if (el.tagName === 'BR') return '';
    var cls = el.classList;
    if (cls.contains('doc-section')) {
      var clone = el.cloneNode(true);
      var h = clone.querySelector('h4');
      var title = h ? h.textContent.trim() : '';
      if (h) h.parentNode.removeChild(h);
      return '#### ' + title + '\n\n' + blocksToMd(clone);
    }
    if (cls.contains('doc-note')) {
      return '> ' + inlineMd(el).replace(/\n+/g, '\n> ');
    }
    if (el.tagName === 'TABLE') return tableToMd(el);
    if (el.tagName === 'UL' || el.tagName === 'OL') {
      return Array.prototype.map.call(el.children, function (li, i) {
        var prefix = el.tagName === 'OL' ? (i + 1) + '. ' : '- ';
        return prefix + inlineMd(li).replace(/\s*\n\s*/g, ' ').trim();
      }).join('\n');
    }
    if (cls.contains('code-wrap')) {
      var pre = el.querySelector('pre');
      return pre ? fencePre(pre) : '';
    }
    if (el.tagName === 'PRE') return fencePre(el);
    if (el.tagName === 'H4') return '#### ' + el.textContent.trim();
    return inlineMd(el).replace(/\s*\n\s*/g, ' ').trim();
  }

  /** 行内元素 → Markdown 文本：b→**、code-inline→`、br→换行，其余取文本 */
  function inlineMd(el) {
    var out = '';
    Array.prototype.forEach.call(el.childNodes, function (n) {
      if (n.nodeType === 3) { out += n.textContent; return; }
      if (n.nodeType !== 1) return;
      var tag = (n.tagName || '').toUpperCase();
      if (tag === 'BR') { out += '\n'; return; }
      if (tag === 'SVG' || tag === 'BUTTON') return;
      if (tag === 'CODE' || (n.classList && n.classList.contains('code-inline'))) {
        out += '`' + n.textContent + '`';
        return;
      }
      if (tag === 'B' || tag === 'STRONG') {
        var inner = inlineMd(n).trim();
        out += inner ? '**' + inner + '**' : '';
        return;
      }
      out += inlineMd(n);
    });
    return out;
  }

  function tableToMd(table) {
    var rows = Array.prototype.slice.call(table.querySelectorAll('tr'));
    if (!rows.length) return '';
    var cellText = function (cell) {
      var c = cell.cloneNode(true);
      Array.prototype.forEach.call(c.querySelectorAll('br'), function (br) {
        br.parentNode.replaceChild(document.createTextNode('；'), br);
      });
      return inlineMd(c).replace(/\s*\n\s*/g, ' ').replace(/\|/g, '\\|').trim() || ' ';
    };
    var lines = rows.map(function (tr) {
      return '| ' + Array.prototype.map.call(tr.children, cellText).join(' | ') + ' |';
    });
    lines.splice(1, 0, '|' + Array.prototype.map.call(rows[0].children, function () {
      return ' --- ';
    }).join('|') + '|');
    return lines.join('\n');
  }

  function fencePre(pre) {
    var lang = pre.classList.contains('is-json') ? 'json'
      : (pre.classList.contains('is-bash') ? 'bash' : '');
    return '```' + lang + '\n' + pre.textContent.replace(/\s+$/, '') + '\n```';
  }

  /** 把当前在线文档（鉴权 + 全部接口条目）导出为 Markdown 文件下载 */
  function docsExportDate() {
    var now = new Date();
    return now.getFullYear() + '-' +
      ('0' + (now.getMonth() + 1)).slice(-2) + '-' + ('0' + now.getDate()).slice(-2);
  }

  function downloadBlob(filename, blob) {
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () {
      URL.revokeObjectURL(a.href);
      a.parentNode.removeChild(a);
    }, 0);
  }

  /** 单个接口导出 Markdown：在各接口文档页点「下载 Markdown」，只导出该接口自己的文档 */
  function buildApiMarkdown(api) {
    var origin = location.origin || 'http://localhost:8080';
    var dateStr = docsExportDate();
    var lines = [];
    lines.push('# 标签画像采集平台 · ' + (api.key === 'auth' ? '鉴权与签名（HMAC-SHA256）' : api.name));
    lines.push('');
    lines.push('- Base URL：`' + origin + '`');
    if (api.key !== 'auth') {
      lines.push('- 方法：`' + api.method + '`');
      lines.push('- 路径：`' + api.path + '`');
    }
    lines.push('');
    lines.push('> 导出自控制台在线文档（' + dateStr + '），内容与页面一致；接口契约遵循「字段只增不删」。');
    lines.push('');
    lines.push(api.desc || '');
    lines.push('');
    lines.push(docsHtmlToMarkdown(api.detail));
    return lines.join('\n') + '\n';
  }

  function downloadApiMarkdown(api) {
    var name = (api.key === 'auth' ? '鉴权与签名' : api.method + '-' + api.name)
      .replace(/[\\/:*?"<>|]/g, '');
    downloadBlob('开放API-' + name + '-' + docsExportDate() + '.md',
      new Blob([buildApiMarkdown(api)], { type: 'text/markdown;charset=utf-8' }));
  }

  function docsGroups() {
    var groups = [];
    docsApis.forEach(function (api) {
      var group = groups.filter(function (g) { return g.name === api.group; })[0];
      if (!group) { group = { name: api.group, items: [] }; groups.push(group); }
      group.items.push(api);
    });
    return groups;
  }

  function renderDocsNav() {
    var host = document.getElementById('docs-nav');
    if (!host) return;
    var kw = docsState.keyword.toLowerCase();
    var html = '';
    docsGroups().forEach(function (group) {
      var items = group.items.filter(function (api) {
        return !kw || (api.path + ' ' + api.name + ' ' + api.desc).toLowerCase().indexOf(kw) >= 0;
      });
      if (!items.length) return;
      html += '<div class="docs-group"><div class="docs-group-title">' + group.name + '</div>';
      items.forEach(function (api) {
        var active = docsState.open === api.key;
        html += '<button type="button" class="docs-link' + (active ? ' active' : '') + '" data-doc="' + api.key + '">' +
          (api.key === 'auth'
            ? '<span class="docs-auth-chip">HMAC</span>'
            : '<span class="method method-' + api.method.toLowerCase() + '">' + api.method + '</span>') +
          '<span class="docs-link-main"><b>' + esc(api.name) + '</b>' +
          '<small class="mono">' + esc(api.path) + '</small></span>' +
          '</button>';
      });
      html += '</div>';
    });
    host.innerHTML = html || '<div class="docs-empty">没有匹配的接口</div>';
    host.querySelectorAll('[data-doc]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        docsState.open = btn.getAttribute('data-doc');
        try { history.replaceState(null, '', '#/docs?api=' + encodeURIComponent(docsState.open)); } catch (e) { /* ignore */ }
        renderDocsNav();
        renderDocsDetail();
      });
    });
  }

  function renderDocsDetail() {
    var card = document.getElementById('docs-detail');
    if (!card) return;
    var api = docsApis.filter(function (a) { return a.key === docsState.open; })[0] || docsApis[0];
    card.innerHTML =
      '<div class="docs-detail-head">' +
      '  <div class="docs-detail-title">' +
      (api.key === 'auth'
        ? '<span class="docs-auth-chip">HMAC</span>'
        : '<span class="method method-' + api.method.toLowerCase() + '">' + api.method + '</span>') +
      '    <span class="api-path">' + esc(api.path) + '</span>' +
      '    <span class="api-name">' + esc(api.name) + '</span>' +
      '  </div>' +
      '  <div class="docs-detail-actions">' +
      (api.key === 'auth' ? '' :
        '<button type="button" class="btn btn-ghost btn-sm" data-copy-path="' + esc(api.path) + '">' +
        '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<rect x="5.5" y="5.5" width="8" height="8" rx="1.6"/><path d="M10.5 3.5v-1a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h1"/></svg>' +
        '<span>复制路径</span></button>') +
      '<button type="button" class="btn btn-primary btn-sm" data-download-doc>' +
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M8 2.5v7.2"/><path d="m4.7 6.9 3.3 3.3 3.3-3.3"/><path d="M3 13h10"/></svg>' +
      '<span>下载 Markdown</span></button>' +
      '  </div>' +
      '</div>' +
      '<div class="docs-detail-body">' + api.detail + '</div>';
    card.querySelectorAll('[data-copy-path]').forEach(function (btn) {
      btn.addEventListener('click', function () { copyText(btn.getAttribute('data-copy-path'), '路径已复制'); });
    });
    var dlOne = card.querySelector('[data-download-doc]');
    if (dlOne) dlOne.addEventListener('click', function () { downloadApiMarkdown(api); });
    bindDocCopy(card);
  }

  function renderDocs() {
    var origin = location.origin || 'http://localhost:8080';
    var writePath = '/api/v1/open/tag-values/write';
    var profileBatchPath = '/api/v1/open/objects/profiles';

    var signText = [
      'STRING_TO_SIGN = METHOD + "\\n" + PATH + "\\n" + CANONICAL_QUERY + "\\n" + RAW_BODY + "\\n" + TIMESTAMP + "\\n" + NONCE',
      '',
      'signature = hex_lower( HMAC_SHA256(key = secret, data = STRING_TO_SIGN) )'
    ].join('\n');

    var headerTable =
      '<table class="doc-table"><thead><tr><th>Header</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">X-App-Key</td><td>是</td><td>凭证的 app_key</td></tr>' +
      '<tr><td class="mono">X-Timestamp</td><td>是</td><td>毫秒时间戳，与服务端偏差需在 ±300 秒内</td></tr>' +
      '<tr><td class="mono">X-Nonce</td><td>是</td><td>一次性随机串（16 字节 hex），60 秒内不可重复</td></tr>' +
      '<tr><td class="mono">X-Signature</td><td>是</td><td>HMAC-SHA256 十六进制小写</td></tr>' +
      '<tr><td class="mono">Content-Type</td><td>POST 必填</td><td>application/json</td></tr>' +
      '</tbody></table>';

    // items[] 子字段行：标签推送与 Kafka 接入共用同一份定义，避免两条通道文档漂移
    var writeItemRows =
      '<tr><td class="mono">&nbsp;└ object_type</td><td>是</td><td>已注册对象类型 ∈ driver / vehicle / fleet</td></tr>' +
      '<tr><td class="mono">&nbsp;└ object_id</td><td>是</td><td>对象唯一标识：司机为司机编号（如 <span class="code-inline">DRV…</span>）；<b>车辆为车辆 ID（车联网内部唯一编号），不是车牌号</b>——车牌号放 <span class="code-inline">object_name</span>。<b>平台不校验 ID 格式</b>，非空即可</td></tr>' +
      '<tr><td class="mono">&nbsp;└ object_name</td><td>否</td><td>对象名称：司机姓名 / <b>车辆车牌号</b> / 车队名等，≤256 字符；仅用于展示与流水追溯，不参与对象匹配、幂等与频控</td></tr>' +
      '<tr><td class="mono">&nbsp;└ tag_code</td><td>是</td><td>标签码：须在线（否则 3002）、可写（writable=true，否则 3012）且适用于该对象类型（否则 3011）</td></tr>' +
      '<tr><td class="mono">&nbsp;└ tag_value</td><td>是</td><td><b>一律传字符串</b>（≤2048 字符）：数字传 <span class="code-inline">"12.5"</span>、布尔传 <span class="code-inline">"true"</span>、枚举传选项原文；须通过字典 value_range 值域校验；多选枚举用英文逗号连接（如 <span class="code-inline">"公共道路,港口"</span>）</td></tr>' +
      '<tr><td class="mono">&nbsp;└ operator</td><td>是</td><td>操作员对象：<span class="code-inline">operator_id</span> 必填（≤64 字符，<b>调用方系统内的操作员标识即可，不要求是平台控制台账号</b>——可为外部客服坐席工号 / 系统名）；<span class="code-inline">operator_type</span> 必填 ∈ human（人工）/ system（系统）/ algo（算法）；<span class="code-inline">operator_name / operator_org</span> 可选（建议填真实姓名与组织，便于流水追溯）</td></tr>' +
      '<tr><td class="mono">&nbsp;└ remark</td><td>敏感标签必填</td><td>≤2048 字符；字典 need_value_audit=true 的标签不传返回 3008</td></tr>' +
      '<tr><td class="mono">&nbsp;└ biz_time</td><td>否</td><td>业务时间，格式 <span class="code-inline">yyyy-MM-dd HH:mm:ss</span>；缺省为服务端当前时间</td></tr>' +
      '<tr><td class="mono">&nbsp;└ operate_time</td><td>否</td><td>操作时间，格式同上；缺省为服务端当前时间</td></tr>' +
      '<tr><td class="mono">&nbsp;└ source</td><td>否</td><td>来源标识，≤64 字符</td></tr>' +
      '<tr><td class="mono">&nbsp;└ confidence</td><td>否</td><td>置信度 ∈ 高 / 中 / 低</td></tr>' +
      '<tr><td class="mono">&nbsp;└ evidence_url</td><td>否</td><td>证据链接，≤512 字符</td></tr>' +
      '<tr><td class="mono">&nbsp;└ ext_json</td><td>否</td><td>扩展 JSON，≤2048 字符</td></tr>';

    var writeFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">biz_request_id</td><td>是</td><td>幂等键，全局唯一，≤96 字符；24 小时内重复提交回放首次响应（无论内容是否一致）</td></tr>' +
      '<tr><td class="mono">task_id</td><td>否</td><td>任务数字 ID（<span class="code-inline">task.id</span>，不是 task_code）。<b>不传 = 通用写入通道</b>：任意时间可推，不受任务状态约束，不影响任务进度。<b>传了 = 任务作业通道</b>：任务须处于 published / in_progress / reviewing（已验收返回 3014）；任务为「全部对象」模式时新对象自动纳入任务项。任务行为详见下方要点。</td></tr>' +
      '<tr><td class="mono">items</td><td>是</td><td>写入条目数组，<b>1~1000 条</b>；任一条校验失败则整批拒绝（HTTP 200 + code≠0），message 会指明「第 N 条」及原因。同一批内同一 <span class="code-inline">(object_type, object_id, tag_code)</span> 只能出现一次。以下字段均为 <b>items[] 内每个元素</b>的字段：</td></tr>' +
      writeItemRows +
      '</tbody></table>';

    var writeItemFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      writeItemRows +
      '</tbody></table>';

    var writeBodyPretty = [
      '{',
      '  "biz_request_id": "biz-20261021-0001",',
      '  "task_id": 12,',
      '  "items": [{',
      '    "object_type": "driver",',
      '    "object_id": "DRV85001",',
      '    "object_name": "张伟",',
      '    "tag_code": "driver_relation",',
      '    "tag_value": "公司员工",',
      '    "biz_time": "2026-10-21 10:00:00",',
      '    "operator": {',
      '      "operator_id": "OP_SERVICE01",',
      '      "operator_name": "客服专员",',
      '      "operator_type": "human",',
      '      "operator_org": "客服中心"',
      '    },',
      '    "source": "客服工作台"',
      '  }]',
      '}'
    ].join('\n');

    var writeCurl = [
      'BASE_URL="' + origin + '"',
      'APP_KEY="AK_RC_8f2a91"',
      'SECRET="rc-secret-8f2a91-demo"',
      'PATH_="' + writePath + '"',
      '# 将上方「请求体示例」原样保存为 body.json（签名与发送必须逐字节一致，不要重新格式化）',
      'BODY=$(cat body.json)',
      '',
      'TS=$(date +%s%3N)',
      'NONCE=$(openssl rand -hex 16)',
      "SIGNATURE=$(printf '%s\\n%s\\n%s\\n%s\\n%s\\n%s' POST \"$PATH_\" \"\" \"$BODY\" \"$TS\" \"$NONCE\" \\",
      '  | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)',
      '',
      'curl -s -X POST "$BASE_URL$PATH_" \\',
      '  -H "Content-Type: application/json" \\',
      '  -H "X-App-Key: $APP_KEY" -H "X-Timestamp: $TS" \\',
      '  -H "X-Nonce: $NONCE" -H "X-Signature: $SIGNATURE" \\',
      '  -d "$BODY"'
    ].join('\n');

    var writeResp = [
      '{',
      '  "code": 0,',
      '  "message": "ok",',
      '  "request_id": "2f5ca60674b443178cf5af1a",',
      '  "data": {',
      '    "biz_request_id": "biz-20261021-0001",',
      '    "total": 1,',
      '    "accepted": 1,',
      '    "pending_audit": 0,',
      '    "results": [{',
      '      "index": 0,',
      '      "object_type": "driver",',
      '      "object_id": "DRV85001",',
      '      "tag_code": "driver_relation",',
      '      "status": "accepted",',
      '      "message": null',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var writeDetail =
      '<div class="doc-note">批量写入标签值（单批 1~1000 条，<b>整批校验、整批受理</b>，不会部分成功）。<b>异步生效</b>：响应成功仅表示平台已受理落库，标签值同步到画像查询有秒级延迟；对时效敏感的场景可用「操作员打标记录」回查确认。<br>' +
      '按是否携带 <span class="code-inline">task_id</span> 分两种用法：<b>任务作业通道</b>（带 task_id，写值同时推进任务进度）与<b>通用写入通道</b>（不带 task_id，任意时间可推，用于数据修正、外部补数等非任务场景，不影响任何任务）。</div>' +
      docSection('请求体字段', writeFields) +
      docSection('请求体示例', docCode('doc-write-body', writeBodyPretty)) +
      docSection('调用示例（curl，含签名）', docCode('doc-write-curl', writeCurl)) +
      docSection('响应字段', '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">data.biz_request_id</td><td>回显本次幂等键</td></tr>' +
        '<tr><td class="mono">data.total</td><td>条目总数（= accepted + pending_audit）</td></tr>' +
        '<tr><td class="mono">data.accepted</td><td>直接受理数</td></tr>' +
        '<tr><td class="mono">data.pending_audit</td><td>进入值审核数</td></tr>' +
        '<tr><td class="mono">data.results[]</td><td>每条 item 的受理结果，顺序与请求 items 一致：<span class="code-inline">index</span>（<b>从 0 计数</b>，错误 message 里的「第 N 条」从 1 计数，即 index+1）、object_type / object_id / tag_code、<span class="code-inline">status</span>（accepted 受理 / pending_audit 进入审核）、message（失败原因，成功为 null）</td></tr>' +
        '</tbody></table>') +
      docSection('响应示例', docCode('doc-write-resp', writeResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>幂等</b>：相同 <span class="code-inline">biz_request_id</span> 在 24 小时内重复提交回放<b>首次</b>响应（无论内容是否一致）；处理中返回 3004；处理失败会释放幂等记录，可用同一 ID 修复后重试。</li>' +
        '<li><b>整批受理</b>：任一条不合法则整批拒绝（HTTP 200 + code≠0），不会出现部分成功；message 指明「第 N 条」（N 从 1 起）及原因，results[].index 从 0 起。</li>' +
        '<li><b>敏感标签</b>：字典 need_value_audit=true 的标签 remark 必填（否则 3008），受理后状态为 pending_audit。</li>' +
        '<li><b>对象标识（object_id）</b>：车辆对象的 object_id 是<b>车辆 ID</b>（车联网内部唯一编号），<b>不是车牌号</b>——车牌号填 <span class="code-inline">object_name</span>。同一对象在平台内必须始终使用同一个 object_id（它同时是幂等、频控与任务匹配的键）；若历史接入已用<b>车牌号作为 object_id</b>，请保持原值不变、不要与车辆 ID 混用，避免同一辆车裂成两个对象导致画像断链。</li>' +
        '<li><b>操作员（operator）</b>：<span class="code-inline">operator_id</span> 为<b>调用方系统内的操作员标识</b>（外部坐席工号 / 系统名均可），不要求是平台控制台账号——平台仅用它做<b>频控计数</b>（每操作员 20 QPS）与<b>打标流水留痕</b>，不校验用户是否存在。建议按真实操作员逐一传值（勿所有坐席共用一个固定值，否则共享频控额度且流水无法区分）；带 <span class="code-inline">task_id</span> 时任务项的「操作员」记录为调用方 <b>AppKey</b>（任务监控按 AppKey 归属），<span class="code-inline">operator_id</span> 仍只用于频控与流水。</li>' +
        '<li><b>任务作业通道（带 task_id）</b>：① 任务含多个标签时<b>可分批推</b>，单次只推几个甚至 1 个都受理；② 对象项收到首条写入即置 in_progress，任务<b>必填标签</b>全部写完后自动置 submitted（需审核）或 approved，<b>无需再调进度回传</b>；③ 已 submitted / approved 的对象项再推，值照写但任务项状态不再变化。</li>' +
        '<li><b>通用写入通道（不带 task_id）</b>：任意时间可推（数据修正、外部补数等），不影响任务；若该对象同时在某任务中，任务侧进度不受影响——需要推进任务请带 task_id 重新提交。</li>' +
        '<li><b>频控</b>：IP 100 QPS、操作员 20 QPS、同对象同标签 1 QPS（按秒窗口），超限返回 HTTP 429 + 3005/3006/3007，建议指数退避后用同一 biz_request_id 重试。</li>' +
        '<li><b>常见错误码</b>：3001 标签不存在 / 3002 标签未上线或不可写 / 3008 缺 remark / 3011 标签与对象类型不匹配 / 3012 写入开关关闭 / 3013 值审核已停用 / 3014 任务已验收锁定。</li>' +
        '</ul>');

    var tagsPath = '/api/v1/open/tags';
    var tasksPath = '/api/v1/open/tasks';
    var taskItemsPath = '/api/v1/open/tasks/{task_id}/items';
    var taskItemQueryPath = '/api/v1/open/task-items';
    var taskProgressPath = '/api/v1/open/tasks/{task_id}/progress';
    var recordsPath = '/api/v1/open/tag-records';
    var deletePath = '/api/v1/open/tag-values/delete';

    var tagsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>类型</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">tag_code</td><td>string</td><td>标签编码（唯一标识，写入与查询均使用它）</td></tr>' +
      '<tr><td class="mono">tag_name</td><td>string</td><td>标签名称（中文展示名）</td></tr>' +
      '<tr><td class="mono">category_code</td><td>string</td><td>标签类目（二级维度编码）</td></tr>' +
      '<tr><td class="mono">dim1_code</td><td>string</td><td>一级维度编码；枚举：personality（性格特征）/ driving_ops（驾驶与运营习惯）/ service_interaction（服务交互表现）/ operation_mode（运营模式）/ baseline（人员基础属性）</td></tr>' +
      '<tr><td class="mono">dim1_name</td><td>string</td><td>一级维度<b>中文名称</b>（实时解析自维度字典，无需接入方自建映射）</td></tr>' +
      '<tr><td class="mono">dim2_code</td><td>string</td><td>二级维度编码，与 <span class="code-inline">category_code</span> 同值</td></tr>' +
      '<tr><td class="mono">dim2_name</td><td>string</td><td>二级维度<b>中文名称</b></td></tr>' +
      '<tr><td class="mono">priority</td><td>int</td><td>标签优先级（数值越小越靠前，用于客服侧展示排序）；null = 非人工打标标签（纯统计类）</td></tr>' +
      '<tr><td class="mono">dim1_priority</td><td>int</td><td>所属一级维度的<b>展示优先级</b>（数值越小维度越靠前；与 <span class="code-inline">ext.dim1_priority</span> 同源，这里是数值型便捷字段）；未配置为 null</td></tr>' +
      '<tr><td class="mono">value_type</td><td>string</td><td>boolean / number / enum / string / date</td></tr>' +
      '<tr><td class="mono">unit</td><td>string</td><td><b>计量单位</b>：数值型统计指标的单位（如 小时 / 天 / 次 / 公里）；文本、布尔、枚举类为 null</td></tr>' +
      '<tr><td class="mono">value_range</td><td>string</td><td>原始值域 JSON（enum 为 <span class="code-inline">{"enum":[...]}</span>，number 可含 min/max）</td></tr>' +
      '<tr><td class="mono">options</td><td>string[]</td><td>枚举值列表（已从 value_range 解析，便于下拉渲染）</td></tr>' +
      '<tr><td class="mono">value_multiple</td><td>boolean</td><td>是否多选；多值时写入用英文逗号连接</td></tr>' +
      '<tr><td class="mono">tag_type</td><td>string</td><td>fact / rule / algo / <b>derived</b>（组合标签）</td></tr>' +
      '<tr><td class="mono">derived</td><td>boolean</td><td>是否组合标签；组合标签只在查询期计算，不可写入</td></tr>' +
      '<tr><td class="mono">object_type</td><td>string</td><td>适用对象，逗号分隔；空 = 不限</td></tr>' +
      '<tr><td class="mono">source_mode</td><td>string</td><td>manual / statistics / hybrid / algo / derived</td></tr>' +
      '<tr><td class="mono">need_value_audit</td><td>boolean</td><td>写入是否需审核（true 时 remark 必填）</td></tr>' +
      '<tr><td class="mono">writable</td><td>boolean</td><td>是否允许开放 API 写入；false 的标签推送会被拒</td></tr>' +
      '<tr><td class="mono">value_ttl_days</td><td>int</td><td>值有效期（0 = 不过期）</td></tr>' +
      '<tr><td class="mono">sensitivity</td><td>string</td><td>L1 公开 / L2 内部 / L3 敏感 / L4 高敏</td></tr>' +
      '<tr><td class="mono">status</td><td>string</td><td>仅返回 online</td></tr>' +
      '<tr><td class="mono">version</td><td>int</td><td><b>标签定义版本号</b>：每次修改定义 +1，可用于判断本地字典缓存是否过期</td></tr>' +
      '<tr><td class="mono">updated_at</td><td>datetime</td><td>定义最近更新时间</td></tr>' +
      '<tr><td class="mono">ext</td><td>object&lt;string,string&gt;</td><td><b>租户级扩展信息</b>（key → value，值统一为字符串）：未单独配置时回落属性默认值，未配置且无默认值的不出现；租户可自定义增减，接入方应忽略不认识的 key</td></tr>' +
      '</tbody></table>' +
      '<h4 class="doc-sub">扩展信息 ext 内置属性</h4>' +
      '<table class="doc-table"><thead><tr><th>ext key</th><th>取值</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">show_screen</td><td>主 / 副</td><td><b>标签展示屏幕</b>，默认 <span class="code-inline">副</span>；决定该标签落在客服主屏还是副屏</td></tr>' +
      '<tr><td class="mono">hidden</td><td>true / false</td><td>是否在客服侧隐藏，默认 <span class="code-inline">false</span>；true 时前端不展示该标签</td></tr>' +
      '<tr><td class="mono">script</td><td>文本</td><td><b>标签话术</b>，可能数百字；建议按需渲染，不要放在列表单元格里整段展示</td></tr>' +
      '<tr><td class="mono">risk_priority</td><td>整数</td><td><b>标签风控展示优先级</b>，越小越靠前；未配置则不出现</td></tr>' +
      '<tr><td class="mono">dim1_priority</td><td>整数</td><td>所属<b>一级维度的展示优先级</b>（随维度配置），用于先按维度、再按标签优先级排序；与顶层字段 <span class="code-inline">dim1_priority</span> 同源，此处在 ext 中以字符串承载</td></tr>' +
      '</tbody></table>';

    var tagsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "tags": [{',
      '      "tag_code": "driver_relation",',
      '      "tag_name": "司机关系",',
      '      "category_code": "driver_relation",',
      '      "dim1_code": "operation_mode",',
      '      "dim1_name": "运营模式",',
      '      "dim2_code": "driver_relation",',
      '      "dim2_name": "司机关系",',
      '      "priority": 5,',
      '      "dim1_priority": 4,',
      '      "value_type": "enum",',
      '      "unit": null,',
      '      "value_range": "{\\"enum\\":[\\"公司员工\\",\\"第三方派遣\\",\\"承包或承租\\"]}",',
      '      "options": ["公司员工", "第三方派遣", "承包或承租"],',
      '      "value_multiple": false,',
      '      "tag_type": "fact",',
      '      "derived": false,',
      '      "object_type": "driver",',
      '      "source_mode": "hybrid",',
      '      "need_value_audit": false,',
      '      "writable": true,',
      '      "sensitivity": "L2",',
      '      "status": "online",',
      '      "version": 2,',
      '      "ext": {',
      '        "show_screen": "主",',
      '        "hidden": "false",',
      '        "risk_priority": "5",',
      '        "script": "客服话术：师傅，您跟公司是什么关系？……（完整话术 432 字，此处截断）",',
      '        "dim1_priority": "4"',
      '      }',
      '    }],',
      '    "total": 36',
      '  }',
      '}'
    ].join('\n');

    var tagsDetail =
      '<div class="doc-note">返回当前凭证租户可见的 <b>online</b> 标签字典（公开标签 + 本租户标签，含组合标签），是写入与画像字段口径的<b>唯一事实源</b>——接入方应从本接口取标签的值类型、值域、单位与适用对象，不要写死。<br>响应 <span class="code-inline">data = { tags: [&lt;TagView&gt;…], total: 条数 }</span>，无分页。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">category_code</td><td>否</td><td>按二级维度编码精确过滤（与 <span class="code-inline">dim2_code</span> 同值，如 <span class="code-inline">driver_relation</span>）；传不存在的编码返回空列表，不报错</td></tr>' +
        '<tr><td class="mono">object_type</td><td>否</td><td>按适用对象过滤（driver / vehicle / fleet）：返回 object_type 列表<b>包含该值</b>（或未限定对象）的标签，即「能打在该类对象上的标签」</td></tr>' +
        '<tr><td class="mono">source_mode</td><td>否</td><td>按打标来源精确过滤：manual（人工）/ statistics（统计）/ hybrid（人工+统计）/ algo（算法）/ derived（组合）。客服系统可用 <span class="code-inline">object_type=driver&amp;source_mode=manual</span> 拉取「需人工打标」清单</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', tagsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-tags-curl', docCurl('GET', tagsPath, 'object_type=driver', ''))) +
      docSection('响应示例', docCode('doc-tags-resp', tagsResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>只读接口</b>：无需请求体，签名规则同写接口（GET 的 RAW_BODY 为空串）。</li>' +
        '<li><b>写入前置校验</b>：只有 <span class="code-inline">writable=true</span> 且 <span class="code-inline">object_type</span> 匹配的标签才能推送，组合标签一律不可写。</li>' +
        '<li><b>值域</b>：<span class="code-inline">options</span> 可直接用于下拉；写入时仍会按 value_range 严格校验。</li>' +
        '<li><b>扩展信息</b>：<span class="code-inline">ext</span> 是<b>租户级</b>配置（主副屏、隐藏、话术、风控优先级），不同租户可不同；未配置时回落默认值，租户可自定义增减属性，接入方应忽略不认识的 key。</li>' +
        '<li><b>展示顺序</b>：先按 <span class="code-inline">dim1_priority</span> 排一级维度，再按 <span class="code-inline">priority</span> 排标签；<span class="code-inline">ext.hidden=true</span> 不展示，<span class="code-inline">ext.show_screen</span> 决定主屏/副屏。维度中文名直接用 <span class="code-inline">dim1_name</span> / <span class="code-inline">dim2_name</span>，无需接入方自建编码映射。</li>' +
        '<li><b>缓存</b>：字典建议本地缓存，用 <span class="code-inline">version</span> 判断定义是否变更，用返回的 <span class="code-inline">ext</span> 判断展示配置是否变更。</li>' +
        '<li><b>全量返回</b>：<b>没有分页参数</b>，一次返回租户可见的全部 online 标签（含组合标签），按 <span class="code-inline">tag_code</span> 字典序；<span class="code-inline">total</span> = 返回条数。标签量级小（几十个量级），适合启动时加载并本地缓存。</li>' +
        '<li><b>过滤</b>：Query 参数可叠加；传了不存在的类目 / 对象类型只会返回空列表，<b>不报错</b>。</li>' +
        '</ul>');

    var tasksFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[].task</td><td>任务主对象，以下为 task 内的字段（task.id 为数字主键，供 task_id 参数与写入接口使用）</td></tr>' +
      '<tr><td class="mono">task.task_code</td><td>任务编号（TK- 开头，展示用）</td></tr>' +
      '<tr><td class="mono">task.title</td><td>任务标题</td></tr>' +
      '<tr><td class="mono">task.object_type</td><td>任务对象类型（driver / vehicle / fleet 等）</td></tr>' +
      '<tr><td class="mono">task.all_objects</td><td><b>全部对象模式</b>：true 时对象不预登记，发布后外部推送（带该 task_id 写入）的新对象自动纳入任务项并参与进度</td></tr>' +
      '<tr><td class="mono">status</td><td>draft / published / in_progress / reviewing / completed / verified（已验收） / cancelled</td></tr>' +
      '<tr><td class="mono">task.priority</td><td>优先级</td></tr>' +
      '<tr><td class="mono">task.assignment_mode</td><td>领取方式 ∈ pool（任务池自助领取）/ assign（指派到人）</td></tr>' +
      '<tr><td class="mono">task.total_items</td><td>对象总数</td></tr>' +
      '<tr><td class="mono">task.completed_items</td><td>已完成数（approved + skipped）</td></tr>' +
      '<tr><td class="mono">task.rejected_items</td><td>被驳回数</td></tr>' +
      '<tr><td class="mono">task.due_at</td><td>截止时间</td></tr>' +
      '<tr><td class="mono">task.published_at</td><td>发布时间</td></tr>' +
      '<tr><td class="mono">task.completed_at</td><td>完成时间</td></tr>' +
      '<tr><td class="mono">stats</td><td>任务整体的对象项状态分布：pending 待领取 / claimed 已领取未开始 / in_progress 进行中 / submitted 待审核 / approved 已通过 / rejected 已驳回 / skipped 已跳过</td></tr>' +
      '<tr><td class="mono">my_stats</td><td><b>仅传 operator_id 时返回</b>：该客服名下对象项的同口径分布；同时传单个 object_id 时进一步只统计该对象</td></tr>' +
      '<tr><td class="mono">records[].tags[]</td><td>任务要求的标签：<span class="code-inline">tag_code</span> / <span class="code-inline">tag_name</span> / <span class="code-inline">required</span>（必填才计入自动完成判定），并回填字典信息——<span class="code-inline">value_type</span>（值类型）、<span class="code-inline">unit</span>（计量单位）、<span class="code-inline">value_range</span>（值域原文）、<span class="code-inline">value_multiple</span>（枚举是否多选）、<span class="code-inline">options</span>（枚举可选项数组，可直接渲染下拉/单选控件）；标签已下线或删除时仅返回 tag_code / required</td></tr>' +
      '</tbody></table>';

    var tasksResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "page": 1, "size": 20, "total": 2,',
      '    "records": [{',
      '      "task": {',
      '        "id": 12, "task_code": "TK-20261029-88992", "title": "10 月客服补标 · 华东车队",',
      '        "object_type": "driver", "all_objects": false, "status": "in_progress", "priority": "high",',
      '        "assignment_mode": "pool", "total_items": 120, "completed_items": 86,',
      '        "rejected_items": 2, "due_at": "2026-11-05 18:00:00"',
      '      },',
      '      "tags": [{"tag_code": "driver_relation", "tag_name": "司机关系", "required": true,',
      '                "value_type": "enum", "unit": null, "value_multiple": false,',
      '                "value_range": "[\\"公司员工\\",\\"第三方派遣\\"]",',
      '                "options": ["公司员工", "第三方派遣"]}],',
      '      "stats": {"total": 120, "pending": 30, "claimed": 2, "in_progress": 0,',
      '                  "submitted": 0, "approved": 86, "rejected": 2, "skipped": 0}',
      '    }, {',
      '      "task": {',
      '        "id": 15, "task_code": "TK-20261102-33107", "title": "整车风险排查 · 苏A 车队",',
      '        "object_type": "vehicle", "all_objects": false, "status": "published", "priority": "normal",',
      '        "assignment_mode": "pool", "total_items": 45, "completed_items": 0,',
      '        "rejected_items": 0, "due_at": "2026-11-20 18:00:00"',
      '      },',
      '      "tags": [{"tag_code": "high_mileage", "tag_name": "高里程", "required": true,',
      '                "value_type": "number", "unit": "公里", "value_multiple": false,',
      '                "value_range": null, "options": []}],',
      '      "stats": {"total": 45, "pending": 45, "claimed": 0, "in_progress": 0,',
      '                  "submitted": 0, "approved": 0, "rejected": 0, "skipped": 0}',
      '    }]',
      '  }',
      '}',
    ].join('\n');

    var tasksDetail =
      '<div class="doc-note">查询本租户的打标任务及进度，用于外部系统拉取待办与对账。<b>task_id 非必填</b>：不传返回该租户全部任务（分页），传了只返回该任务。<b>支持按对象过滤</b>：object_type / object_id 逗号分隔多值、按位置配对，任一对象命中即返回该任务。<br>响应 <span class="code-inline">data = { page, size, total, records: [{ task, tags[], stats, my_stats? }] }</span>。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">task_id</td><td>否</td><td>任务数字 ID（列表里的 <span class="code-inline">task.id</span>，不是 <span class="code-inline">task_code</span>）。<b>不传 = 返回全部任务</b>；传了 = 只返回该任务</td></tr>' +
        '<tr><td class="mono">status</td><td>否</td><td>按任务状态过滤，<b>逗号分隔多值</b>（如 <span class="code-inline">published,in_progress</span> 一次查回所有「待打标」任务）；可选值 draft / published / in_progress / reviewing / completed / verified / cancelled；非法值返回 2002</td></tr>' +
        '<tr><td class="mono">operator_id</td><td>否</td><td>客服（任务项实际操作员）ID；仅返回存在该客服任务项的任务，并附带 my_stats</td></tr>' +
        '<tr><td class="mono">object_type</td><td>否</td><td>对象类型（driver / vehicle / fleet）。<b>逗号分隔多值、按位置配对</b>：<span class="code-inline">driver,vehicle</span> + <span class="code-inline">DRV85001,VH10086</span>（车辆用<b>车辆 ID</b>，非车牌号）；只查一种类型时传 1 个类型即可配多个 ID；只传 object_type 不传 object_id = 该类型对象参与的任务</td></tr>' +
        '<tr><td class="mono">object_id</td><td>否</td><td>对象 ID（车辆对象为<b>车辆 ID</b>，非车牌号），<b>逗号分隔多值</b>，与 object_type 按位置一一配对；<b>任一所列对象出现在任务对象项中即返回该任务</b>。类型数 &gt;1 且与 ID 个数不等返回 2002。不传 object_type 时只按 ID 匹配、不限类型；<b>「全部对象」模式的任务按 object_type 整体命中</b>——该类型下任意 object_id 都会返回它，无需事先写入或建任务项；与 operator_id 同用时 my_stats 额外按该对象统计（多对象时只按客服统计）</td></tr>' +
        '<tr><td class="mono">page</td><td>否</td><td>页码，默认 1</td></tr>' +
        '<tr><td class="mono">size</td><td>否</td><td>每页条数，默认 20，上限 100</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', tasksFields +
        '<p class="doc-tip">传 <span class="code-inline">task_id</span> 时<b>响应结构不变</b>，仍是分页结构、<span class="code-inline">records</span> 里只有 1 条。需要「单对象」结构可继续用路径式 <span class="code-inline">GET /api/v1/open/tasks/{task_id}</span>（兼容保留，<span class="code-inline">data</span> 直接是任务对象，不含 my_stats）。</p>') +
      docSection('调用示例（curl，含签名）', docCode('doc-tasks-curl',
        docCurl('GET', tasksPath, 'object_type=driver,vehicle&object_id=DRV85001,VH10086', ''))) +
      docSection('响应示例', docCode('doc-tasks-resp', tasksResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>task_id 两种用法</b>：不传 = 该租户全部任务（分页遍历）；传 = 只查这一个，<span class="code-inline">total</span> 为 0 或 1。<b>任务号不存在只返回空列表，不报错</b>。</li>' +
        '<li><b>空任务也能查到</b>：查询对对象项是 LEFT JOIN，尚未导入对象项的任务同样返回（stats 全 0），不会像旧版那样"消失"。</li>' +
        '<li><b>排序与过滤</b>：按任务创建时间<b>倒序</b>；status 支持逗号多值——<b>拉「待打标」任务传 <span class="code-inline">status=published,in_progress</span></b>（reviewing 为待审核，completed/verified/cancelled 无需打标）；状态值大小写敏感，非法值返回 2002。</li>' +
        '<li><b>进度口径</b>：completed_items = approved + skipped；审核中的任务会进入 <span class="code-inline">reviewing</span>。</li>' +
        '<li><b>客服口径</b>：<span class="code-inline">operator_id</span> 是任务项实际领取/提交人；仅被指派但尚未领取的项 operator_id 为空，不会命中。</li>' +
        '<li><b>对象维度</b>：传 object_type + object_id 可筛出「这批对象参与了哪些任务」——逗号分隔多值、<b>按位置配对不做笛卡尔积</b>（driver,vehicle + 123,345 = 司机 123 或车牌 345 参与的任务），任一对象命中即返回；个数不等返回 2002；只传 object_type = 按类型筛任务。要精确看对象的状态与值，用 <span class="code-inline">GET /api/v1/open/task-items</span>。</li>' +'<li><b>「全部对象」任务</b>（创建时选全部对象、未指定清单）：查询时<b>按 object_type 整体命中</b>，任意 object_id（包括从未写入过的）都会返回该任务；这类任务不依赖任务项，写入过的对象才会生成任务项并进入进度统计。指定对象的任务仍按任务项精确匹配，未列入的对象查不到。</li>' +
        '<li><b>租户隔离</b>：只返回 AppKey 绑定租户的任务；任务属于其他租户时按"不存在"处理，不暴露存在性。</li>' +
        '</ul>');

    var itemsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[].item</td><td>object_id、status（pending/claimed/in_progress/submitted/approved/rejected/skipped）、assignee_name、operator_name、submitted_at、reviewed_at</td></tr>' +
      '<tr><td class="mono">records[].values</td><td>该对象在任务内<b>已写入的标签值</b>（总是返回）：tag_code / tag_value / status；status 为任务项值状态（外部写入即 approved），与画像流水的 effective 不是同一套</td></tr>' +
      '<tr><td class="mono">stats</td><td>任务级进度统计</td></tr>' +
      '<tr><td class="mono">units</td><td><b>标签码 → 计量单位</b>映射（仅含本页出现且配置了单位的标签，如 <span class="code-inline">{"avg_daily_duration":"小时"}</span>）；展示 <span class="code-inline">values[].tag_value</span> 时按 tag_code 取单位，查不到视为无单位</td></tr>' +
      '</tbody></table>';

    var itemsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "page": 1, "size": 50, "total": 120,',
      '    "units": {"avg_daily_duration": "小时"},',
      '    "stats": {"total": 120, "pending": 30, "approved": 86, "rejected": 2, "skipped": 0},',
      '    "records": [{',
      '      "item": {"object_id": "DRV85001", "status": "approved", "operator_name": "外部打标系统",',
      '               "submitted_at": "2026-10-30 10:20:00", "reviewed_at": "2026-10-30 10:20:00"},',
      '      "values": [',
      '        {"tag_code": "driver_relation", "tag_value": "第三方派遣", "status": "approved"},',
      '        {"tag_code": "avg_daily_duration", "tag_value": "8.5", "status": "approved"}',
      '      ]',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var itemsDetail =
      '<div class="doc-note">按任务分页拉取对象项与<b>已写入值</b>，用于对账与进度；要看「哪些标签还没有值」请用画像统一入口 <span class="code-inline">GET /api/v1/open/objects/profiles?include_empty=true</span>。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">status</td><td>否</td><td>按对象项状态过滤：pending / claimed / in_progress / submitted / approved / rejected / skipped（精确匹配）</td></tr>' +
        '<tr><td class="mono">page</td><td>否</td><td>页码，默认 1</td></tr>' +
        '<tr><td class="mono">size</td><td>否</td><td>每页条数，默认 50，上限 500</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', itemsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-items-curl',
        docCurl('GET', taskItemsPath.replace('{task_id}', '12'), 'page=1&size=50', ''))) +
      docSection('响应示例', docCode('doc-items-resp', itemsResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>排序</b>：按对象项 ID <b>升序</b>（即导入顺序），翻页稳定，适合「从头扫一遍找 pending」。</li>' +
        '<li><b>values 总是返回</b>：不需要额外参数；只想要对象清单可忽略该字段。</li>' +
        '<li><b>用途</b>：<span class="code-inline">status=pending</span> 可拉出未处理对象，<span class="code-inline">submitted / approved / skipped</span> 用于对账；<b>判断「还缺哪些标签」请用统一入口的 <span class="code-inline">include_empty=true</span> 空占位</b>（任务必填 / 驳回维度可结合本接口 values 与标签字典自行判断）。</li>' +
        '<li><b>错误</b>：任务不存在返回 2002；任务属于其他租户返回 4003。</li>' +
        '<li><b>任务维度判定</b>：「必填标签 + 驳回返工 + 任务状态」的待打标集合仍由遗留接口 <span class="code-inline">GET /api/v1/open/task-todos</span> 提供（端点保留可用，但已不在本页单列；其「任务必填标签 / 驳回返工」口径与统一入口的 <span class="code-inline">include_empty</span> 空占位不同，注意区分。<b>「全部对象」模式下，任意同类型对象——即使从未写入、没有任务项——都会出现在待打标清单里，且任务的必填标签全部视为待打标</b>；对象首次写入后转为真实任务项，不会重复计数。</li>' +
        '</ul>');

    var taskItemQueryFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[].task_id</td><td>所属任务数字 ID</td></tr>' +
      '<tr><td class="mono">records[].task_code</td><td>所属任务编号（TK- 开头）</td></tr>' +
      '<tr><td class="mono">records[].task_title</td><td>所属任务标题</td></tr>' +
      '<tr><td class="mono">records[].item</td><td>object_id、status（pending/claimed/in_progress/submitted/approved/rejected/skipped）、operator_id/operator_name、assignee_name、claimed_at / submitted_at / reviewed_at</td></tr>' +
      '<tr><td class="mono">records[].values</td><td>仅 <span class="code-inline">include_values=true</span> 时返回：该对象在任务内已写入的标签值</td></tr>' +
      '<tr><td class="mono">units</td><td><b>标签码 → 计量单位</b>映射（仅含本页 values 中出现且配置了单位的标签）；无单位标签不下发</td></tr>' +
      '</tbody></table>';

    var taskItemQueryResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "page": 1, "size": 50, "total": 2, "units": {"avg_daily_duration": "小时"},',
      '    "records": [{',
      '      "task_id": 12, "task_code": "TK-20261029-88992", "task_title": "10 月客服补标",',
      '      "item": {"object_id": "DRV85001", "status": "approved", "operator_id": "CS9999",',
      '               "operator_name": "客服专员小张", "submitted_at": "2026-11-03 10:20:00"},',
      '      "values": [',
      '        {"tag_code": "driver_relation", "tag_value": "第三方派遣", "status": "approved"},',
      '        {"tag_code": "avg_daily_duration", "tag_value": "8.5", "status": "approved"}',
      '      ]',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var taskItemQueryDetail =
      '<div class="doc-note">跨任务按客服 / 对象查询任务项，直接回答“这个客服在这个对象上有哪些任务、进度如何”，无需逐任务循环拉取。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">task_id</td><td>否</td><td>限定某个任务</td></tr>' +
        '<tr><td class="mono">operator_id</td><td>否</td><td>客服（任务项实际领取/提交人）</td></tr>' +
        '<tr><td class="mono">object_id</td><td>否</td><td>对象 ID</td></tr>' +
        '<tr><td class="mono">status</td><td>否</td><td>任务项状态，取值同「任务对象项」接口（pending / claimed / in_progress / submitted / approved / rejected / skipped）</td></tr>' +
        '<tr><td class="mono">include_values</td><td>否</td><td>true 时附带已写入标签值，默认 false</td></tr>' +
        '<tr><td class="mono">page</td><td>否</td><td>页码，默认 1</td></tr>' +
        '<tr><td class="mono">size</td><td>否</td><td>每页条数，默认 50，上限 200</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', taskItemQueryFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-task-item-query-curl',
        docCurl('GET', taskItemQueryPath, 'operator_id=CS9999&object_id=DRV85001&include_values=true', ''))) +
      docSection('响应示例', docCode('doc-task-item-query-resp', taskItemQueryResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>客服口径</b>：按任务项 operator_id 匹配；仅指派未领取的项没有 operator_id，不会返回。</li>' +
        '<li><b>过滤全部可选</b>：不传任何条件会返回租户内全部任务项（分页），建议至少带 <span class="code-inline">operator_id</span> / <span class="code-inline">object_id</span> / <span class="code-inline">task_id</span> 之一。</li>' +
        '<li><b>排序</b>：按对象项更新时间<b>倒序</b>（最近处理在前），与「任务对象项」接口的 ID 升序不同。</li>' +
        '<li><b>values 状态</b>：任务项值状态（外部写入即 approved），与画像流水状态（effective）不是同一套。</li>' +
        '<li><b>进度</b>：item.status 即该客服在该对象上的处理状态；任务整体进度请配合 <span class="code-inline">GET /api/v1/open/tasks</span> 的 stats / my_stats。</li>' +
        '<li><b>租户隔离</b>：只查询 AppKey 绑定租户下的任务项。</li>' +
        '</ul>');

    var progressBody = [
      '{',
      '  "items": [',
      '    {"object_id": "DRV85001", "status": "completed"},',
      '    {"object_id": "DRV85002", "status": "skipped", "remark": "对象不符合打标条件"}',
      '  ]',
      '}'
    ].join('\n');

    var progressResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "task_id": 12,',
      '    "updated": 2,',
      '    "unchanged": 0,',
      '    "not_found": 0,',
      '    "not_found_objects": [],',
      '    "stats": {"total": 120, "pending": 28, "approved": 87, "skipped": 1}',
      '  }',
      '}'
    ].join('\n');

    var progressDetail =
      '<div class="doc-note"><b>兼容保留（可选）</b>：任务进度由平台按打标结果<b>自动统计</b>，标准对接<b>无需调用本接口</b>。仅在两种场景使用：① 整项<b>跳过</b>（对象不符合打标条件）；② 无法从写入推断的<b>手工完成</b>。任务状态与统计随之更新，全部完成后自动置为 completed。</div>' +
      docSection('请求体', '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">items</td><td>是</td><td>1~100 个对象；同一请求内 object_id 去重</td></tr>' +
        '<tr><td class="mono">items[].object_id</td><td>是</td><td>任务内已存在的对象 ID（车辆对象为车辆 ID，非车牌号）</td></tr>' +
        '<tr><td class="mono">items[].status</td><td>是</td><td><b>completed</b>（完成）或 <b>skipped</b>（跳过）</td></tr>' +
        '<tr><td class="mono">items[].remark</td><td>否</td><td>备注，写入任务流转记录</td></tr>' +
        '</tbody></table>') +
      docSection('请求示例', docCode('doc-progress-body', progressBody)) +
      docSection('调用示例（curl，含签名）', docCode('doc-progress-curl',
        docCurl('POST', taskProgressPath.replace('{task_id}', '12'), '', progressBody))) +
      docSection('响应示例', docCode('doc-progress-resp', progressResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>幂等</b>：已 submitted / approved / skipped 的对象重复回传会计入 <span class="code-inline">unchanged</span>，不会重复推进。</li>' +
        '<li><b>审核</b>：任务 review_required=true 时，completed 先置为 submitted 等待审核；否则直接 approved。</li>' +
        '<li><b>状态限制</b>：仅 published / in_progress / reviewing 的任务允许回传，draft / cancelled / completed 返回 2002。</li>' +
        '<li><b>未知对象</b>：不在任务内的 object_id 计入 <span class="code-inline">not_found_objects</span>，不影响其他条目。</li>' +
        '<li><b>推荐做法</b>：写入时携带 <span class="code-inline">task_id</span>，平台自动按标签写齐推进；<b>标准对接不需要调用本接口</b>，仅用于 skipped 或手工 completed。</li>' +
        '<li><b>结果字段</b>：<span class="code-inline">updated</span> 实际推进数 / <span class="code-inline">unchanged</span> 已是终态被跳过 / <span class="code-inline">not_found</span> 不在任务内的对象数，后两者都不算失败。</li>' +
        '</ul>');

    var tagFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">tags[].tag_code</td><td>标签编码</td></tr>' +
      '<tr><td class="mono">tags[].tag_name</td><td>标签名称（中文展示名）</td></tr>' +
      '<tr><td class="mono">tags[].tag_value</td><td>当前值（一律字符串）；<span class="code-inline">include_empty=true</span> 时无值项为 <span class="code-inline">tag_value=null</span>、<span class="code-inline">status=none</span> 占位</td></tr>' +
      '<tr><td class="mono">tags[].dim1_code</td><td>一级维度编码；组合标签与未配置维度的标签为 <span class="code-inline">null</span></td></tr>' +
      '<tr><td class="mono">tags[].dim1_name</td><td>一级维度<b>中文名称</b>（实时解析自维度字典，无需接入方自建映射）</td></tr>' +
      '<tr><td class="mono">tags[].dim2_code</td><td>二级维度编码</td></tr>' +
      '<tr><td class="mono">tags[].dim2_name</td><td>二级维度<b>中文名称</b></td></tr>' +
      '<tr><td class="mono">tags[].priority</td><td>标签优先级（维度内展示顺序），数值越小越靠前；未配置为 <span class="code-inline">null</span></td></tr>' +
      '<tr><td class="mono">tags[].dim1_priority</td><td>所属一级维度的展示优先级，数值越小越靠前；与 <span class="code-inline">priority</span> 搭配做「维度 → 标签」两级排序；未配置为 <span class="code-inline">null</span></td></tr>' +
      '<tr><td class="mono">tags[].status</td><td>effective / pending_audit（仅 include_pending=true）/ none（空占位）；已作废、已过期不返回</td></tr>' +
      '<tr><td class="mono">tags[].value_type</td><td>值类型 ∈ boolean / number / enum / string / date</td></tr>' +
      '<tr><td class="mono">tags[].unit</td><td><b>计量单位</b>：数值型统计指标返回 小时 / 天 / 次 / 公里 等，文本/布尔/枚举为 <span class="code-inline">null</span>；占位项同样带单位</td></tr>' +
      '<tr><td class="mono">tags[].derived</td><td>true 表示组合标签，值为查询期实时计算，不落 latest</td></tr>' +
      '<tr><td class="mono">tags[].source_mode</td><td>打标来源 ∈ manual / statistics / hybrid / algo / derived，便于渲染待打标清单</td></tr>' +
      '<tr><td class="mono">tags[].need_value_audit</td><td>写值是否需审核标记</td></tr>' +
      '<tr><td class="mono">tags[].biz_time</td><td>业务时间（占位项、组合标签为空）</td></tr>' +
      '<tr><td class="mono">tags[].operate_time</td><td>操作时间（占位项、组合标签为空）</td></tr>' +
      '<tr><td class="mono">tags[].expire_time</td><td>过期时间；<span class="code-inline">null</span> 表示不过期</td></tr>' +
      '<tr><td class="mono">tags[].operator_id / operator_name</td><td>打标人 ID 与姓名（算法/系统写入时为任务标识与任务名）</td></tr>' +
      '<tr><td class="mono">tags[].operator_type / operator_org</td><td>操作员类型 ∈ human / system / api 等，及所属组织</td></tr>' +
      '<tr><td class="mono">tags[].source</td><td>来源标识（写入时可选传，如「车联网统计任务」「客服工作台」），未传为 <span class="code-inline">null</span></td></tr>' +
      '<tr><td class="mono">tags[].confidence</td><td><b>置信度</b> ∈ 高 / 中 / 低；<b>写入时可选</b>，主要用于算法/外部系统推送，人工打标一般不填 → 返回 <span class="code-inline">null</span></td></tr>' +
      '<tr><td class="mono">tags[].remark / evidence_url</td><td>打标理由与证据链接（写入时可选，敏感标签 remark 必填）</td></tr>' +
      '</tbody></table>';

    var profileRecordResp = [
      '// data.records[0] 的完整字段示例：',
      '{',
      '  "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",',
      '  "tags": [{',
      '    "tag_code": "driver_relation", "tag_name": "司机关系", "tag_value": "第三方派遣",',
      '    "dim1_code": "operation_mode", "dim1_name": "运营模式",',
      '    "dim2_code": "driver_relation", "dim2_name": "司机关系",',
      '    "priority": 5, "dim1_priority": 4,',
      '    "value_type": "enum", "unit": null,',
      '    "status": "effective", "derived": false,',
      '    "source_mode": "manual", "need_value_audit": false,',
      '    "biz_time": "2026-10-16 12:15:43", "operate_time": "2026-10-16 12:16:02", "expire_time": null,',
      '    "operator_id": "U1008", "operator_name": "孙启勇", "operator_type": "human", "operator_org": "客服一组",',
      '    "source": "客服工作台", "confidence": null, "remark": "客户来电确认", "evidence_url": null',
      '  }, {',
      '    "tag_code": "risk_combo_high", "tag_name": "高风险驾驶组合", "tag_value": "true",',
      '    "dim1_code": null, "dim1_name": null, "dim2_code": null, "dim2_name": null,',
      '    "priority": null, "dim1_priority": null,',
      '    "value_type": "boolean", "unit": null,',
      '    "status": "effective", "derived": true',
      '  }, {',
      '    "tag_code": "avg_daily_duration", "tag_name": "日均时长", "tag_value": "8.5",',
      '    "dim1_code": "driving_ops", "dim1_name": "驾驶与运营习惯",',
      '    "dim2_code": "driving_habit", "dim2_name": "驾驶习惯",',
      '    "priority": null, "dim1_priority": 2,',
      '    "value_type": "number", "unit": "小时",',
      '    "status": "effective", "derived": false',
      '  }]',
      '}'
    ].join('\n');


    var profileBatchResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "tenant_id": "risk_control", "total": 3,',
      '    "records": [{',
      '      "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",',
      '      "tags": [',
      '        { "tag_code": "driver_relation", "tag_name": "司机关系", "tag_value": "第三方派遣",',
      '          "dim1_code": "operation_mode", "dim1_name": "运营模式", "dim2_code": "driver_relation", "dim2_name": "司机关系",',
      '          "priority": 5, "dim1_priority": 4,',
      '          "value_type": "enum", "unit": null, "status": "effective", "derived": false,',
      '          "source_mode": "manual", "need_value_audit": false,',
      '          "biz_time": "2026-10-16 12:15:43", "operate_time": "2026-10-16 12:16:02", "expire_time": null,',
      '          "operator_id": "U1008", "operator_name": "孙启勇", "operator_type": "human", "operator_org": "客服一组",',
      '          "source": "客服工作台", "confidence": null, "remark": "客户来电确认", "evidence_url": null }',
      '      ]',
      '    }, {',
      '      "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85002",',
      '      "tags": [',
      '        { "tag_code": "avg_speeding_count", "tag_name": "日均超速次数", "tag_value": "3",',
      '          "dim1_code": "driving_ops", "dim1_name": "驾驶与运营习惯", "dim2_code": "speeding", "dim2_name": "超速",',
      '          "priority": null, "dim1_priority": 2,',
      '          "value_type": "number", "unit": "次", "status": "effective", "derived": false,',
      '          "source_mode": "statistics", "need_value_audit": false,',
      '          "biz_time": "2026-10-21 03:00:00", "operate_time": "2026-10-21 03:00:12", "expire_time": null,',
      '          "operator_id": "SYS_VEH_METRIC", "operator_name": "车联网统计任务",',
      '          "operator_type": "system", "operator_org": null,',
      '          "source": "数据统计", "confidence": "高", "remark": null, "evidence_url": null }',
      '      ]',
      '    }, {',
      '      "tenant_id": "risk_control", "object_type": "vehicle", "object_id": "VH10086",',
      '      "tags": [',
      '        { "tag_code": "cargo_type", "tag_name": "货物类型", "tag_value": "普货",',
      '          "dim1_code": "operation_mode", "dim1_name": "运营模式", "dim2_code": "cargo_type", "dim2_name": "货物类型",',
      '          "priority": null, "dim1_priority": 4,',
      '          "value_type": "enum", "unit": null, "status": "effective", "derived": false,',
      '          "source_mode": "manual", "need_value_audit": true,',
      '          "biz_time": "2026-10-18 09:30:00", "operate_time": "2026-10-18 09:31:20", "expire_time": null,',
      '          "operator_id": "U1012", "operator_name": "李静", "operator_type": "human", "operator_org": "运营组",',
      '          "source": "客服工作台", "confidence": "中", "remark": "行驶证核定",',
      '          "evidence_url": "https://files.example.com/cargo/VH10086.jpg" }',
      '      ]',
      '    }]',
      '  }',
      '}',
      '// source / confidence / remark / evidence_url 是「写入时可选」的字段：',
      '// 人工打标一般只传 remark，confidence 为 null；算法或外部系统推送常带 高/中/低。'
    ].join('\n');

    var profileBatchEmptyResp = [
      '// include_empty=true 时，每个对象的 tags[] 里会多出无值占位项（同样带维度与单位）；',
      '// 占位项的值相关字段全为 null：tag_value / biz_time / operate_time / operator_* / source / confidence / remark / evidence_url：',
      '[',
      '  { "tag_code": "social_tendency", "tag_name": "社交倾向", "tag_value": null,',
      '    "dim1_code": "personality", "dim1_name": "性格特征", "dim2_code": "risk_attitude", "dim2_name": "风险态度",',
      '    "priority": 1, "dim1_priority": 1,',
      '    "value_type": "enum", "unit": null, "status": "none",',
      '    "source_mode": "hybrid", "need_value_audit": true, "derived": false },',
      '  { "tag_code": "avg_daily_duration", "tag_name": "日均时长", "tag_value": null,',
      '    "dim1_code": "driving_ops", "dim1_name": "驾驶与运营习惯", "dim2_code": "driving_habit", "dim2_name": "驾驶习惯",',
      '    "priority": null, "dim1_priority": 2,',
      '    "value_type": "number", "unit": "小时", "status": "none",',
      '    "source_mode": "statistics", "need_value_audit": false, "derived": false }',
      ']'
    ].join('\n');

    var profileBatchDetail =
      '<div class="doc-note"><b>画像查询的唯一入口</b>：一次读取<b>一个或多个对象</b>的当前标签画像（已生效值 + 组合标签实时计算），用于实时决策与页面展示。入参支持批量对象：<span class="code-inline">object_type</span> / <span class="code-inline">object_id</span> 逗号分隔多值、<b>按位置配对，不做笛卡尔积</b>；可选 <span class="code-inline">include_empty</span> 补无值占位、<span class="code-inline">tag_codes</span> 精确取码。响应 <span class="code-inline">data = { tenant_id, total, records: [&lt;对象画像&gt;…] }</span>，不分页。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">object_type</td><td>是</td><td>对象类型，逗号分隔多值（如 <span class="code-inline">driver,vehicle</span>）；只查一种类型时<b>只传 1 个</b>即可配多个 ID</td></tr>' +
        '<tr><td class="mono">object_id</td><td>是</td><td>对象 ID（车辆对象为<b>车辆 ID</b>，非车牌号），逗号分隔多值；<b>批量画像必须指定对象</b>（与待办清单不同，不传不会被解释为「该类型全部对象」）</td></tr>' +
        '<tr><td class="mono">include_pending</td><td>否</td><td>默认 false；true 时返回待审核值，勿用于自动决策</td></tr>' +
        '<tr><td class="mono">include_empty</td><td>否</td><td><b>默认 false = 只返回有值标签</b>；置 true 时返回该对象适用的<b>全部在线标签</b>：无值的以 <span class="code-inline">tag_value=null</span> / <span class="code-inline">status=none</span> 占位项返回（同样带单位与维度信息），可直接渲染「待打标清单」；组合标签算不出值时同样占位</td></tr>' +
        '<tr><td class="mono">tag_codes</td><td>否</td><td>逗号分隔的标签码清单，<b>精确取码</b>：只返回这些标签的当前值 / 占位 / 组合结果（三者受同一份过滤）；不传或空串 = 不过滤。常用场景：一次取多个对象的同一组特征值；传入不存在或不可见的标签码会被忽略，不报错</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">tenant_id</td><td>租户</td></tr>' +
        '<tr><td class="mono">total</td><td><b>去重后的对象数 = records 条数</b>（本接口不分页）</td></tr>' +
        '<tr><td class="mono">records[]</td><td>每个元素的结构：tenant_id / object_type / object_id / tags[]；顺序与传入的对象顺序一致</td></tr>' +
        '<tr><td class="mono">records[].tags[]</td><td>默认只返回有值标签；<span class="code-inline">include_empty=true</span> 时追加 <span class="code-inline">status=none</span> 空占位。每项都含 <span class="code-inline">unit</span> 计量单位、<span class="code-inline">derived</span> 组合标记与<b>维度信息</b>（dim1/dim2 编码+中文名、priority/dim1_priority）；作废、已过期值始终排除</td></tr>' +
        '</tbody></table>') +
      docSection('records[].tags[] 字段', tagFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-profile-batch-curl',
        docCurl('GET', profileBatchPath,
          'object_type=driver,driver,vehicle&object_id=DRV85001,DRV85002,VH10086&include_pending=false', ''))) +
      docSection('响应示例（默认 include_empty=false）', docCode('doc-profile-batch-resp', profileBatchResp)) +
      docSection('单条记录完整字段示例（data.records[0]）', docCode('doc-profile-record-resp', profileRecordResp)) +
      docSection('空占位形态（include_empty=true）', docCode('doc-profile-batch-empty-resp', profileBatchEmptyResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>按位置配对，不做笛卡尔积</b>：<span class="code-inline">driver,vehicle</span> + <span class="code-inline">DRV85001,VH10086</span> 只查 (driver,DRV85001) 与 (vehicle,VH10086)，不会交叉出 4 条。</li>' +
        '<li><b>同类型多对象</b>：<span class="code-inline">object_type=driver</span> + <span class="code-inline">object_id=DRV85001,DRV85002</span> 即可，类型不必重复写多遍。</li>' +
        '<li><b>校验</b>：类型个数 &gt; 1 且与 ID 个数不等返回 2002；<span class="code-inline">object_type</span> / <span class="code-inline">object_id</span> 缺失返回 2001；类型未注册返回 3003；<span class="code-inline">object_id</span> 为空返回 2002。<b>平台不校验 ID 格式</b>，任意非空字符串均可查询。</li>' +
        '<li><b>去重与上限</b>：重复的 (类型, ID) 只查一次；单次最多 20 个类型 / 200 个对象，建议按业务分批。</li>' +
        '<li><b>空占位</b>：<span class="code-inline">include_empty=true</span> 返回该对象适用的全部在线标签，无值项 <span class="code-inline">tag_value=null</span> / <span class="code-inline">status=none</span>，占位同样带单位与维度信息；<b>默认 false</b>，需要占位清单时显式传 true。</li>' +
        '<li><b>精确取码</b>：<span class="code-inline">tag_codes=risk_level,avg_daily_duration</span> 只返回这些标签；与 <span class="code-inline">include_empty</span> 可叠加（=「一次取多个对象的指定特征值，缺值也给占位」）。传入不存在或不可见的标签码会被忽略，不报错。</li>' +
        '<li><b>不分页</b>：一次返回全部请求对象；默认口径下对象没有有值标签时该条 <span class="code-inline">tags</span> 为空数组，<b>不会漏掉对象</b>。</li>' +
        '<li><b>返回顺序</b>：先按可见在线标签输出占位/有值项，再追加组合标签，最后追加「有值但已下线 / 不适配当前对象」的标签（避免画像丢数据）。<b>不保证维度顺序</b>——展示排序请用每个 tags[] 自带的 <span class="code-inline">dim1_priority</span>（一级维度）+ <span class="code-inline">priority</span>（维度内标签）两级排序，无需再查字典。</li>' +
        '<li><b>维度信息</b>：每个 tags[] 带 <span class="code-inline">dim1_code/dim1_name</span>、<span class="code-inline">dim2_code/dim2_name</span> 与 <span class="code-inline">priority/dim1_priority</span>，可直接按维度分组渲染（客服主/副屏分块、风控维度排序）；组合标签与未配置维度的标签这些字段为 <span class="code-inline">null</span>。</li>' +
        '<li><b>状态与单位</b>：<span class="code-inline">effective</span> / <span class="code-inline">pending_audit</span>（仅 include_pending=true）/ <span class="code-inline">none</span>（占位）；已作废、已过期的值不返回。每个 tags[] 都带 <span class="code-inline">unit</span>（数值指标为 小时 / 天 / 次 / 公里，文本/布尔/枚举为 null），占位项同样带单位。</li>' +
        '<li><b>中文参数</b>：<span class="code-inline">object_id</span> 含中文或特殊字符时（正常情况下车辆为英文数字车辆 ID；若历史接入以车牌号作为 object_id 则会含中文）在 URL 中需百分号编码，签名仍用解码原值。</li>' +
        '</ul>');

    var recordsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[]</td><td>tenant_id / object_type / object_id / tag_code / tag_value / status / biz_time / operate_time / operator_* / source / confidence</td></tr>' +
      '<tr><td class="mono">units</td><td><b>标签码 → 计量单位</b>映射（仅含本页出现且配置了单位的标签）；流水按追加式存储，单位不在每条流水里冗余，展示时按 tag_code 取值</td></tr>' +
      '<tr><td class="mono">total</td><td>总条数</td></tr>' +
      '<tr><td class="mono">page</td><td>当前页码</td></tr>' +
      '<tr><td class="mono">size</td><td>每页条数</td></tr>' +
      '</tbody></table>';

    var recordsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "operator_id": "OP_SERVICE01",',
      '    "page": 1, "size": 20, "total": 1,',
      '    "units": {"avg_daily_duration": "小时"},',
      '    "records": [{',
      '      "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",',
      '      "tag_code": "avg_daily_duration", "tag_value": "8.5", "status": "effective",',
      '      "biz_time": "2026-10-16 12:15:43", "operate_time": "2026-10-16 12:15:43",',
      '      "operator_id": "OP_SERVICE01", "operator_name": "客服专员", "source": "客服手工标注"',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var recordsDetail =
      '<div class="doc-note">按操作员查询其打标流水，用于外部系统对账与审计。v1.22.0 起 <span class="code-inline">operator_id</span> 改走 <b>query 参数</b>：签名待签串的 PATH 是固定字符串，不再涉及动态段 URL 编码，避免「编码前后不一致」的签名错误。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">operator_id</td><td>是</td><td>操作员 ID（写入时提交的 <span class="code-inline">operator.operator_id</span>）</td></tr>' +
        '<tr><td class="mono">object_type</td><td>否</td><td>按对象类型过滤</td></tr>' +
        '<tr><td class="mono">object_id</td><td>否</td><td>按对象 ID 过滤（车辆对象为车辆 ID，非车牌号）</td></tr>' +
        '<tr><td class="mono">tag_code</td><td>否</td><td>按标签码过滤</td></tr>' +
        '<tr><td class="mono">from</td><td>否</td><td>起始时间，按<b>平台落库时间 op_time</b> 过滤（不是 biz_time / operate_time），格式 yyyy-MM-dd HH:mm:ss，闭区间</td></tr>' +
        '<tr><td class="mono">to</td><td>否</td><td>结束时间，格式同上，闭区间</td></tr>' +
        '<tr><td class="mono">page</td><td>否</td><td>页码，默认 1</td></tr>' +
        '<tr><td class="mono">size</td><td>否</td><td>每页条数，默认 20，上限 200</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', recordsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-records-curl',
        docCurl('GET', recordsPath, 'object_type=driver&operator_id=OP_SERVICE01&page=1&size=20', ''))) +
      docSection('响应示例', docCode('doc-records-resp', recordsResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><span class="code-inline">operator_id</span> 为<b>写入时提交的操作员 ID</b>；开放 API 若未传 operator 或换了 ID，不会归到该客服名下。</li>' +
        '<li><b>旧路径式兼容</b>：<span class="code-inline">GET /operators/{operator_id}/tag-records</span>（v1.21.0 发布）保留可用、响应结构一致；其签名 PATH 需对 operator_id 做 URL 编码，新接入建议统一用本接口的 query 式。</li>' +
        '<li><b>排序</b>：按 <span class="code-inline">op_time</span> 倒序（最新在前），翻页稳定。</li>' +
        '<li><b>包含作废记录</b>：流水追加式存储，作废也是一条 <span class="code-inline">revoked</span> 流水；对账时应按 status 区分，不要只看条数。</li>' +
        '<li><b>只查单个操作员</b>：不支持一次查多人；租户由 AppKey 决定，无需也不能指定其他租户。</li>' +
        '</ul>');

    var deleteFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">biz_request_id</td><td>是</td><td>幂等键，≤96 字符；24 小时内重复提交回放首次响应</td></tr>' +
      '<tr><td class="mono">items</td><td>是</td><td>1~1000 条；<b>逐条处理</b>，单条不存在不影响其他条</td></tr>' +
      '<tr><td class="mono">items[].object_type</td><td>是</td><td>对象类型，与 object_id、tag_code 共同定位要作废的标签值</td></tr>' +
      '<tr><td class="mono">items[].object_id</td><td>是</td><td>对象 ID（车辆对象为车辆 ID，非车牌号）</td></tr>' +
      '<tr><td class="mono">items[].tag_code</td><td>是</td><td>要作废的标签码</td></tr>' +
      '<tr><td class="mono">items[].operator</td><td>是</td><td>同写入接口的 operator 结构（operator_id / operator_type 必填）</td></tr>' +
      '<tr><td class="mono">items[].remark</td><td>否</td><td>作废原因，≤2048 字符</td></tr>' +
      '</tbody></table>';

    var deleteBody = [
      '{',
      '  "biz_request_id": "biz-del-20261030-01",',
      '  "items": [{',
      '    "object_type": "driver",',
      '    "object_id": "DRV85001",',
      '    "tag_code": "driver_relation",',
      '    "operator": {"operator_id": "OP_SERVICE01", "operator_name": "客服专员", "operator_type": "human"},',
      '    "remark": "客户撤回授权"',
      '  }]',
      '}'
    ].join('\n');

    var deleteResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "biz_request_id": "biz-del-20261030-01",',
      '    "total": 1, "accepted": 1, "pending_audit": 0,',
      '    "results": [{"index": 0, "object_type": "driver", "object_id": "DRV85001",',
      '                 "tag_code": "driver_relation", "status": "revoked", "message": null}]',
      '  }',
      '}'
    ].join('\n');

    var deleteDetail =
      '<div class="doc-note">作废（撤销）某个对象的标签值，逐条处理：不存在返回 not_found，已作废幂等返回 revoked。</div>' +
      docSection('请求体', deleteFields) +
      docSection('请求示例', docCode('doc-delete-body', deleteBody)) +
      docSection('调用示例（curl，含签名）', docCode('doc-delete-curl',
        docCurl('POST', deletePath, '', deleteBody))) +
      docSection('响应示例', docCode('doc-delete-resp', deleteResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>逐条处理，不是整批回滚</b>：<span class="code-inline">results[].status</span> 为 <span class="code-inline">revoked</span>（成功或已作废幂等）或 <span class="code-inline">not_found</span>（无值可作废）；只有字段缺失、operator 不完整、标签禁止写入（3012）这类结构性问题才会整批拒绝。</li>' +
        '<li><b>可作废条件</b>：标签必须 <span class="code-inline">writable=true</span>（写入开关关闭的标签不能作废，返回 3012）；组合标签不可作废（本身不落 latest）。</li>' +
        '<li><b>不物理删除</b>：只追加一条 <span class="code-inline">revoked</span> 流水，历史保留可追溯；已作废再作废幂等返回 revoked。</li>' +
        '<li><b>画像延迟</b>：latest 由外部写入程序同步，作废后画像可能<b>短暂仍返回旧值</b>（≤5 秒），不要以「作废后立即查不到」作为成功判据。</li>' +
        '</ul>');

    var authDetail =
      '<div class="doc-note">所有开放接口共用一套签名：请求头带凭证四件套（X-App-Key / X-Timestamp / X-Nonce / X-Signature），请求体不传租户——<b>租户由 AppKey 凭证决定</b>。响应统一为 <span class="code-inline">{code, message, data, request_id}</span>：<span class="code-inline">code=0</span> 成功，≠0 为业务失败（此时 data 为 null）；排查问题时请提供 <span class="code-inline">request_id</span>。</div>' +
      docSection('公共请求头', headerTable) +
      docSection('待签名字符串', docCode('doc-sign', signText) +
        '<p class="doc-tip">CANONICAL_QUERY：query 按 key 字典序、同 key 按 value 字典序，使用<b>URL 解码后的原值</b>；实际请求 URL 使用百分号编码。RAW_BODY 必须与实际发送的字节完全一致，不要重新格式化 JSON；GET 请求 RAW_BODY 为空串。</p>') +
      docSection('最小调用示例（GET /tags）', docCode('doc-auth-curl', docCurl('GET', tagsPath, 'object_type=driver', ''))) +
      docSection('常见错误码', '<table class="doc-table"><thead><tr><th>code</th><th>含义</th><th>HTTP</th></tr></thead><tbody>' +
        '<tr><td class="mono">1001</td><td>缺少或非法的签名请求头（四个头缺一不可）</td><td>401</td></tr>' +
        '<tr><td class="mono">1002 / 1008</td><td>AppKey 不存在 / 已被禁用</td><td>401</td></tr>' +
        '<tr><td class="mono">1003</td><td>签名不匹配（secret、path、query 排序、RAW_BODY 任一不一致）</td><td>401</td></tr>' +
        '<tr><td class="mono">1004</td><td>时间戳超出允许偏差（±300 秒）</td><td>401</td></tr>' +
        '<tr><td class="mono">1005</td><td>Nonce 重复（60 秒内同一 AppKey 下不可复用）</td><td>401</td></tr>' +
        '<tr><td class="mono">1007</td><td>租户已停用</td><td>403</td></tr>' +
        '<tr><td class="mono">2001 / 2002 / 2003</td><td>缺少参数 / 参数校验失败 / 批量条数超限</td><td>200</td></tr>' +
        '<tr><td class="mono">3001 / 3002 / 3003</td><td>标签不存在 / 未上线或不可写 / 对象类型未注册</td><td>200</td></tr>' +
        '<tr><td class="mono">3004</td><td>同一 biz_request_id 的请求正在处理中，稍后重试</td><td>200</td></tr>' +
        '<tr><td class="mono">3005 / 3006 / 3007</td><td>操作员频控 / 同对象同标签频控 / IP 频控（建议指数退避）</td><td><b>429</b></td></tr>' +
        '<tr><td class="mono">3008 / 3011 / 3012</td><td>敏感标签缺 remark / 标签与对象类型不匹配 / 标签写入开关已关闭</td><td>200</td></tr>' +
        '<tr><td class="mono">3013 / 3014</td><td>标签值审核已停用（敏感标签按 remark 落流水）/ 任务已验收锁定（该标签值不可再改）</td><td>200</td></tr>' +
        '<tr><td class="mono">4003</td><td>无权访问（跨租户任务、越权操作）</td><td>403</td></tr>' +
        '<tr><td class="mono">5001 / 5002</td><td>系统内部错误 / 存储读写失败（可携带 request_id 重试或反馈）</td><td>500</td></tr>' +
        '</tbody></table>' +
        '<p class="doc-tip">HTTP 200 但 <span class="code-inline">code≠0</span> 表示业务拒绝——<b>不要只看 HTTP 状态码</b>；只有 5xx 与 429 值得自动重试。</p>') +
      docSection('签名失败排查', '<ul class="doc-list">' +
        '<li><b>secret</b>：使用该 AppKey 当前有效的密钥；控制台重置后旧密钥立即失效。</li>' +
        '<li><b>PATH</b>：必须包含 <span class="code-inline">/api/v1</span> 前缀且<b>不带 query</b>（如 <span class="code-inline">/api/v1/open/tags</span>）。</li>' +
        '<li><b>CANONICAL_QUERY</b>：按 key 字典序、同 key 按 value 字典序，用<b>解码后的原值</b>拼接；无参数为空串，末尾不加 <span class="code-inline">&amp;</span>。</li>' +
        '<li><b>RAW_BODY</b>：与实际发送的字节完全一致，不要在签名后重新序列化 JSON（空格、换行、字段顺序都会变）。</li>' +
        '<li><b>时间</b>：<span class="code-inline">X-Timestamp</span> 是<b>毫秒</b>时间戳，服务器需 NTP 校准（偏差 ≤300 秒）。</li>' +
        '</ul>') +
      docSection('幂等与限流', '<ul class="doc-list">' +
        '<li>写入/作废需带 <span class="code-inline">biz_request_id</span>，24 小时内重复提交回放首次响应。</li>' +
        '<li>IP 与操作员维度有秒级频控；批量写入单次 ≤1000 条，进度回传单次 ≤100 个对象。</li>' +
        '</ul>');

    // Kafka 标签推送通道：消息体与 write 接口同构，平台直接消费后走同一写入链路
    var kafkaMsgBody = [
      '{',
      '  "tenant_id": "risk_control",',
      '  "biz_request_id": "biz-20261111-0001",',
      '  "task_id": 1024,',
      '  "items": [',
      '    {',
      '      "object_type": "driver",',
      '      "object_id": "DRV90001",',
      '      "object_name": "张伟",',
      '      "tag_code": "driver_relation",',
      '      "tag_value": "公司员工",',
      '      "source": "外部系统推送",',
      '      "operator": { "operator_id": "OP_DEMO", "operator_type": "human", "operator_name": "演示账号" }',
      '    }',
      '  ]',
      '}'
    ].join('\n');

    var kafkaDetail =
      '<div class="doc-note">平台直接消费 Kafka 主题 <span class="code-inline">profile_tag_write</span>：' +
      '<b>消息体与「标签推送」接口的请求体完全一致</b>，写入后的校验、幂等、任务联动、审计行为与调用 HTTP 接口相同。' +
      '适合已有消息队列、不想再维护 HTTP 签名与重试的场景。</div>' +
      docSection('连接信息', '<table class="doc-table"><thead><tr><th>环境</th><th>Broker</th><th>Topic</th></tr></thead><tbody>' +
        '<tr><td>测试</td><td class="mono">10.1.12.59:9092</td><td class="mono">profile_tag_write</td></tr>' +
        '<tr><td>生产</td><td class="mono">172.16.0.131:9092<br>172.16.0.70:9092<br>172.16.0.178:9092</td><td class="mono">profile_tag_write</td></tr>' +
        '</tbody></table>' +
        '<div class="doc-note">消费组 <span class="code-inline">tag-platform-write</span> 由平台维护，接入方只需向 topic 生产消息。</div>') +
      docSection('消息体示例', docCode('doc-kafka-body', kafkaMsgBody)) +
      docSection('字段说明', '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">tenant_id</td><td>推荐</td><td>租户归属。可作为消息体字段，或放在 Kafka header <span class="code-inline">tenant_id</span>（header 优先）；两者都缺时回落平台配置的默认租户，未配置则进死信</td></tr>' +
        '<tr><td class="mono">app_key</td><td>否</td><td>调用方标识，用于审计与任务项归属；缺省记为 <span class="code-inline">kafka:&lt;topic&gt;</span></td></tr>' +
        '<tr><td class="mono">biz_request_id</td><td><b>是</b></td><td>幂等键（≤64 字符）。<b>同一批数据重投时必须保持不变</b>，平台据此去重；缺失直接进死信</td></tr>' +
        '<tr><td class="mono">task_id</td><td>否</td><td>关联任务；带则写入同时推进任务进度（与 HTTP 通道一致）</td></tr>' +
        '<tr><td class="mono">items[]</td><td><b>是</b></td><td>标签条目，字段与「标签推送」接口的 items <b>完全共用同一套定义</b>（含 source 来源标识、confidence 置信度、remark 备注、evidence_url、ext_json 等），逐字段说明见下一节；单批 ≤1000 条</td></tr>' +
        '</tbody></table>') +
      docSection('items[] 子字段（与「标签推送」接口完全一致）', writeItemFields) +
      docSection('可靠性约定', '<ul class="doc-list">' +
        '<li><b>重复消息不会重复写入</b>：同一 <span class="code-inline">biz_request_id</span> 再次消费时回放首次受理结果，视为成功。</li>' +
        '<li><b>失败不丢消息</b>：解析失败、缺幂等键、业务校验失败（参数非法 / 标签不存在）→ 转死信 <span class="code-inline">profile_tag_write_dlq</span>；数据库或存储抖动 → 退避重试 3 次后转死信。</li>' +
        '<li><b>死信排查</b>：死信保留原消息，header 带 <span class="code-inline">x-error</span>（原因）、<span class="code-inline">x-origin-topic / x-origin-partition / x-origin-offset</span>、<span class="code-inline">x-failed-at</span>。</li>' +
        '<li><b>顺序</b>：建议以 <span class="code-inline">object_id</span> 作为消息 key，保证同一对象的消息进入同一分区、按序处理。</li>' +
        '<li><b>位点策略</b>：平台首次接入只消费<b>启动之后的新消息</b>（不补发 topic 历史消息）；之后按已提交位点续消费，平台重启不会漏消息。确需补历史数据时联系平台运维重置消费组位点。</li>' +
        '<li><b>频控</b>：Kafka 为内部可信通道，不受 API 的 QPS 限制，仅受单批 ≤1000 条约束。</li>' +
        '<li><b>观察消费情况</b>：控制台「开发者 → 接入监控」展示分区数、未消费积压、死信量，以及两组消费计数——' +
        '<b>本次</b>（服务进程启动以来，重启清零）与<b>累计</b>（持久化于 Redis，跨重启保留，附成功率）。' +
        '累计自该看板上线起统计，不含上线前的历史消费。</li>' +
        '</ul>');

    docsApis = [
      { key: 'auth', method: 'sign', path: '鉴权与签名', name: 'HMAC-SHA256', group: '快速开始',
        desc: '公共请求头、待签名字符串与错误码', detail: authDetail },
      { key: 'tags', method: 'GET', path: tagsPath, name: '标签元数据', group: '快速开始',
        desc: '标签字典：类型、值域、适用对象、可写性与组合标记', detail: tagsDetail },
      { key: 'write', method: 'POST', path: writePath, name: '标签推送', group: '标签写入',
        desc: '批量写入标签值（1~1000 条/批，整批校验）', detail: writeDetail },
      { key: 'kafka', method: 'KAFKA', path: 'topic: profile_tag_write', name: 'Kafka 推送接入', group: '标签写入',
        desc: '消息体与标签推送同构，平台直接消费 topic 后走同一写入链路', detail: kafkaDetail },
      { key: 'delete', method: 'POST', path: deletePath, name: '标签值作废', group: '标签写入',
        desc: '作废对象的标签值（追加流水，不物理删除）', detail: deleteDetail },
      { key: 'profile-batch', method: 'GET', path: profileBatchPath, name: '对象画像（统一入口）', group: '画像查询',
        desc: '单/多对象 + 空占位 + 精确取码，画像查询唯一入口', detail: profileBatchDetail },
      { key: 'tasks', method: 'GET', path: tasksPath, name: '任务查询', group: '任务协作',
        desc: 'task_id 非必填：传则查单个，不传返回该租户全部任务（分页）', detail: tasksDetail },
      { key: 'task-items', method: 'GET', path: taskItemsPath, name: '任务对象项', group: '任务协作',
        desc: '按任务分页拉取对象与已写入值（对账）', detail: itemsDetail },
      { key: 'task-item-query', method: 'GET', path: taskItemQueryPath, name: '任务项查询（客服/对象）', group: '任务协作',
        desc: '跨任务按客服/对象查询任务项与进度', detail: taskItemQueryDetail },
      { key: 'task-progress', method: 'POST', path: taskProgressPath, name: '任务进度回传（兼容保留）', group: '任务协作',
        desc: '可选：写入已自动推进进度，常规对接无需调用；仅用于跳过/手工完成', detail: progressDetail },
      { key: 'records', method: 'GET', path: recordsPath, name: '操作员打标记录', group: '对账审计',
        desc: '按操作员查询打标流水，用于对账与审计', detail: recordsDetail }
    ];

    var deepApi = /[?&]api=([^&]+)/.exec(location.hash || '');
    if (deepApi) {
      var wanted = decodeURIComponent(deepApi[1]);
      // 已下线的两个画像接口的旧书签，重定向到统一入口
      if (wanted === 'profile' || wanted === 'query') wanted = 'profile-batch';
      if (docsApis.some(function (a) { return a.key === wanted; })) docsState.open = wanted;
    }
    var endpointCount = docsApis.filter(function (a) { return a.key !== 'auth'; }).length;

    $('#content').innerHTML =
      '<div class="card docs-intro">' +
      '  <div class="docs-intro-main"><b>开放 API 文档</b>' +
      '    <span>HMAC-SHA256 签名 · 租户由 AppKey 决定 · 契约字段只增不删</span></div>' +
      '  <div class="docs-intro-meta">' +
      '    <span class="docs-base">Base URL <span class="mono">' + esc(origin) + '</span></span>' +
      '    <span class="badge badge-gray">' + endpointCount + ' 个接口</span>' +
      '  </div>' +
      '</div>' +
      '<div class="docs-shell">' +
      '  <aside class="card docs-nav">' +
      '    <div class="card-head docs-nav-head"><div class="card-title">接口目录</div>' +
      '      <span class="badge badge-gray">' + endpointCount + '</span></div>' +
      '    <div class="docs-search"><input id="docs-search" placeholder="搜索接口 / 路径"></div>' +
      '    <div class="docs-nav-body" id="docs-nav"></div>' +
      '  </aside>' +
      '  <section class="card docs-detail" id="docs-detail"></section>' +
      '</div>';

    var search = $('#docs-search');
    search.value = docsState.keyword;
    search.addEventListener('input', function () {
      docsState.keyword = this.value.trim();
      renderDocsNav();
    });
    renderDocsNav();
    renderDocsDetail();
  }


  /* ============================================================
   * 页面 9：数据看板
   * ============================================================ */
  var dashboardState = { days: 7, timer: null, data: null };

  function stopDashboard() {
    if (dashboardState.timer) {
      clearInterval(dashboardState.timer);
      dashboardState.timer = null;
    }
  }

  core.onSignOut(stopDashboard);

  var DASH_ICONS = {
    pulse: '<path d="M3 12h4l2.5-7 4.5 14 2.5-7H21"/>',
    tag: '<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1.2"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    building: '<path d="M4 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"/><path d="M16 9h2a2 2 0 0 1 2 2v10"/><path d="M8 7h4M8 11h4M8 15h4M8 19h4"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>'
  };

  function dashIcon(name) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round">' + (DASH_ICONS[name] || '') + '</svg>';
  }

  function fmtNum(n) {
    return String(Number(n || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  function dashRangeLabel(days) {
    return days === 1 ? '今日' : '近 ' + days + ' 天';
  }

  function methodBadge(method) {
    var m = (method || '').toUpperCase();
    return '<span class="method ' + (m === 'POST' ? 'method-post' : 'method-get') + '">' + esc(m) + '</span>';
  }

  function dashKpiPlaceholder() {
    var labels = ['接口调用', '打标次数', '标签查询', '活跃调用方', '调用成功率', '平均耗时'];
    return '<div class="dash-kpis">' + labels.map(function (label) {
      return '<div class="dash-kpi"><div class="dash-kpi-label">' + label + '</div>' +
        '<div class="dash-kpi-value">--</div><div class="dash-kpi-hint">加载中</div></div>';
    }).join('') + '</div>';
  }

  function dashLoadingBlock() {
    return '<div class="empty loading"><span class="spinner"></span>' +
      '<div class="empty-title">加载中</div><div class="empty-desc">正在汇总调用数据</div></div>';
  }

  function renderDashboard() {
    stopDashboard();
    var days = dashboardState.days;
    var chips = [1, 7, 30].map(function (d) {
      return '<button class="dash-chip' + (d === days ? ' active' : '') + '" data-days="' + d + '">' +
        dashRangeLabel(d) + '</button>';
    }).join('');

    $('#content').innerHTML =
      '<div class="dash" id="dashboard">' +
      '  <section class="dash-hero">' +
      '    <div class="dash-head">' +
      '      <div class="dash-title">' +
      '        <span class="dash-eyebrow">OPEN API ANALYTICS</span>' +
      '        <h2>接口调用与标签运营看板</h2>' +
      '        <p>实时统计外部租户的接口调用（按请求计）、打标写入与标签查询（按标签条数计）</p>' +
      '      </div>' +
      '      <div class="dash-controls">' +
      '        <div class="dash-ranges" id="dash-ranges">' + chips + '</div>' +
      '        <button class="dash-btn" id="dash-refresh">刷新</button>' +
      '        <button class="dash-btn" id="dash-fullscreen">全屏</button>' +
      '      </div>' +
      '    </div>' +
      '    <div id="dash-kpis">' + dashKpiPlaceholder() + '</div>' +
      '    <div class="dash-meta"><span class="live-dot"></span>每 30 秒自动刷新 · 统计范围 ' +
      '      <span id="dash-range-label">' + dashRangeLabel(days) + '</span></div>' +
      '  </section>' +
      '  <div class="dash-grid">' +
      '    <section class="card dash-card dash-trend">' +
      '      <div class="dash-card-head"><div><h3>调用趋势</h3><span>调用量（次） / 打标（条） / 查询（条）</span></div></div>' +
      '      <div class="dash-card-body" id="dash-trend">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-endpoints">' +
      '      <div class="dash-card-head"><div><h3>接口调用分布</h3><span>按 GET / POST 端点</span></div></div>' +
      '      <div class="dash-card-body" id="dash-endpoints">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-top">' +
      '      <div class="dash-card-head"><div><h3>打标 TOP</h3><span>标签写入条数</span></div></div>' +
      '      <div class="dash-card-body" id="dash-top-write">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-top">' +
      '      <div class="dash-card-head"><div><h3>查询 TOP</h3><span>标签被查询条数</span></div></div>' +
      '      <div class="dash-card-body" id="dash-top-query">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-tenants">' +
      '      <div class="dash-card-head"><div><h3>租户调用排行</h3><span>租户 + AppKey 聚合</span></div></div>' +
      '      <div class="dash-card-body" id="dash-tenants">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-recent">' +
      '      <div class="dash-card-head"><div><h3>最近调用</h3><span>最新 15 条开放 API 请求</span></div></div>' +
      '      <div id="dash-recent">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '  </div>' +
      '</div>';

    $('#dash-ranges').querySelectorAll('[data-days]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        dashboardState.days = Number(btn.getAttribute('data-days'));
        $('#dash-ranges').querySelectorAll('.dash-chip').forEach(function (b) {
          b.classList.toggle('active', b === btn);
        });
        $('#dash-range-label').textContent = dashRangeLabel(dashboardState.days);
        loadDashboard(true);
      });
    });
    $('#dash-refresh').addEventListener('click', function () { loadDashboard(true); });
    $('#dash-fullscreen').addEventListener('click', toggleDashboardFullscreen);

    loadDashboard(true);
    dashboardState.timer = setInterval(function () { loadDashboard(false); }, 30000);
  }

  function loadDashboard(showLoading) {
    if (showLoading) {
      dashboardState.data = null;
      if ($('#dash-kpis')) $('#dash-kpis').innerHTML = dashKpiPlaceholder();
      ['dash-trend', 'dash-endpoints', 'dash-top-write', 'dash-top-query', 'dash-tenants', 'dash-recent']
        .forEach(function (id) {
          var el = $('#' + id);
          if (el) el.innerHTML = dashLoadingBlock();
        });
    }
    api('/dashboard/overview?days=' + dashboardState.days).then(function (data) {
      if (!$('#dashboard')) return;
      dashboardState.data = data;
      renderDashboardData(data);
    }).catch(function (e) {
      if (!$('#dashboard')) return;
      if (showLoading || !dashboardState.data) {
        ['dash-trend', 'dash-endpoints', 'dash-top-write', 'dash-top-query', 'dash-tenants', 'dash-recent']
          .forEach(function (id) {
            var el = $('#' + id);
            if (el) el.innerHTML = emptyState('加载失败', e.message);
          });
        toast('看板数据加载失败：' + e.message, 'err');
      }
    });
  }

  function renderDashboardData(data) {
    if ($('#dash-kpis')) $('#dash-kpis').innerHTML = dashKpis(data.kpi || {});
    if ($('#dash-trend')) $('#dash-trend').innerHTML = dashTrend(data.trend || []);
    if ($('#dash-endpoints')) $('#dash-endpoints').innerHTML = dashEndpoints(data.endpoints || []);
    if ($('#dash-top-write')) $('#dash-top-write').innerHTML = dashTagList(data.top_write_tags || [], 'write');
    if ($('#dash-top-query')) $('#dash-top-query').innerHTML = dashTagList(data.top_query_tags || [], 'query');
    if ($('#dash-tenants')) $('#dash-tenants').innerHTML = dashTenants(data.tenants || []);
    if ($('#dash-recent')) $('#dash-recent').innerHTML = dashRecent(data.recent_calls || []);
    var label = $('#dash-range-label');
    if (label) {
      var rangeText = data.from === data.to ? data.from : data.from + ' ~ ' + data.to;
      label.textContent = dashRangeLabel(data.days) + ' · ' + rangeText;
    }
  }

  function dashKpis(k) {
    var items = [
      { label: '接口调用', value: fmtNum(k.total_calls), hint: '请求次数 · 含失败请求', tone: 'blue', icon: 'pulse' },
      { label: '打标次数', value: fmtNum(k.total_writes),
        hint: k.total_deletes ? '写入标签条数 · 作废 ' + fmtNum(k.total_deletes) + ' 条' : '写入标签条数', tone: 'green', icon: 'tag' },
      { label: '标签查询', value: fmtNum(k.total_queries), hint: '有值标签返回条数', tone: 'amber', icon: 'search' },
      { label: '活跃租户', value: fmtNum(k.active_tenants),
        hint: 'AppKey ' + fmtNum(k.active_apps) + ' 个', tone: 'purple', icon: 'building' },
      { label: '调用成功率', value: ((k.success_rate || 0) * 100).toFixed(1) + '%',
        hint: 'HTTP + 业务码均成功', tone: 'green', icon: 'check' },
      { label: '平均耗时', value: fmtNum(k.avg_latency_ms) + '<small> ms</small>',
        hint: '接口处理平均耗时', tone: 'blue', icon: 'clock' }
    ];
    return '<div class="dash-kpis">' + items.map(function (it) {
      return '<div class="dash-kpi tone-' + it.tone + '">' +
        '<span class="dash-kpi-ico">' + dashIcon(it.icon) + '</span>' +
        '<div class="dash-kpi-label">' + esc(it.label) + '</div>' +
        '<div class="dash-kpi-value">' + it.value + '</div>' +
        '<div class="dash-kpi-hint">' + esc(it.hint) + '</div>' +
        '</div>';
    }).join('') + '</div>';
  }

  function dashTrend(points) {
    if (!points.length) return emptyState('暂无调用数据', '接口被调用后这里会出现趋势');
    var totals = points.reduce(function (acc, p) {
      acc.calls += p.calls || 0;
      acc.writes += p.writes || 0;
      acc.queries += p.queries || 0;
      return acc;
    }, { calls: 0, writes: 0, queries: 0 });
    if (!totals.calls && !totals.writes && !totals.queries) {
      return emptyState('暂无调用数据', '接口被调用后这里会出现趋势');
    }

    var W = 760, H = 260, padL = 46, padR = 16, padT = 16, padB = 30;
    var innerW = W - padL - padR, innerH = H - padT - padB;
    var max = 1;
    points.forEach(function (p) {
      max = Math.max(max, p.calls || 0, p.writes || 0, p.queries || 0);
    });
    var niceMax = Math.max(4, Math.ceil(max / 4) * 4);
    function px(i) {
      return points.length === 1 ? padL + innerW / 2 : padL + i * innerW / (points.length - 1);
    }
    function py(v) {
      return padT + innerH - (v / niceMax) * innerH;
    }

    var grid = '';
    for (var g = 0; g <= 4; g++) {
      var gy = padT + innerH * g / 4;
      var gv = Math.round(niceMax * (4 - g) / 4);
      grid += '<line x1="' + padL + '" y1="' + gy + '" x2="' + (W - padR) + '" y2="' + gy +
        '" stroke="#eef1f7" stroke-width="1"/>' +
        '<text x="' + (padL - 8) + '" y="' + (gy + 4) + '" text-anchor="end" font-size="10" fill="#9aa4b8">' +
        gv + '</text>';
    }

    var series = [
      { key: 'calls', color: '#4c6ef5', label: '调用量', unit: '次' },
      { key: 'writes', color: '#1f9d6b', label: '打标', unit: '条' },
      { key: 'queries', color: '#d29a2a', label: '查询', unit: '条' }
    ];
    var lines = '';
    var dots = '';
    series.forEach(function (s) {
      var d = points.map(function (p, i) {
        return (i === 0 ? 'M' : 'L') + px(i).toFixed(1) + ',' + py(p[s.key] || 0).toFixed(1);
      }).join(' ');
      lines += '<path d="' + d + '" fill="none" stroke="' + s.color +
        '" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>';
      points.forEach(function (p, i) {
        dots += '<circle cx="' + px(i).toFixed(1) + '" cy="' + py(p[s.key] || 0).toFixed(1) +
          '" r="2.6" fill="#fff" stroke="' + s.color + '" stroke-width="1.8"><title>' +
          esc(p.label) + ' ' + s.label + '：' + (p[s.key] || 0) + ' ' + s.unit + '</title></circle>';
      });
    });

    var area = '';
    if (points.length > 1) {
      area = 'M' + px(0).toFixed(1) + ',' + (padT + innerH).toFixed(1);
      points.forEach(function (p, i) {
        area += ' L' + px(i).toFixed(1) + ',' + py(p.calls || 0).toFixed(1);
      });
      area += ' L' + px(points.length - 1).toFixed(1) + ',' + (padT + innerH).toFixed(1) + ' Z';
    }

    var labelStep = points.length <= 8 ? 1 : Math.ceil(points.length / 6);
    var xLabels = '';
    points.forEach(function (p, i) {
      if (i % labelStep === 0 || i === points.length - 1) {
        xLabels += '<text x="' + px(i).toFixed(1) + '" y="' + (H - 8) +
          '" text-anchor="middle" font-size="10" fill="#9aa4b8">' + esc(p.label) + '</text>';
      }
    });

    var legend = series.map(function (s) {
      return '<span><i style="background:' + s.color + '"></i>' + s.label + '（' + s.unit + '）' +
        ' <b>' + fmtNum(totals[s.key]) + '</b></span>';
    }).join('');

    return '<div class="dash-chart-legend">' + legend + '</div>' +
      '<svg class="dash-chart" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="调用趋势">' +
      '<title>接口调用趋势</title>' + grid +
      (area ? '<path d="' + area + '" fill="rgba(76,110,245,.10)"/>' : '') +
      lines + dots + xLabels + '</svg>' +
      '<div class="dash-chart-note">调用量 = 请求次数（含鉴权失败、参数错误等未成功请求）；' +
      '打标、查询 = 标签条数——一次批量查询返回多个有值标签会记多条，' +
      '所以单日「查询」可能高于「调用量」。</div>';
  }

  function dashEndpoints(items) {
    if (!items.length) return emptyState('暂无接口调用', '外部系统调用开放接口后这里会出现分布');
    var max = 1;
    items.forEach(function (it) { max = Math.max(max, it.calls || 0); });
    return items.map(function (it) {
      var pct = Math.max(2, Math.round((it.calls || 0) / max * 100));
      return '<div class="dash-bar-row">' +
        '<div class="dash-bar-head">' + methodBadge(it.method) +
        '<span class="dash-bar-path" title="' + esc(it.endpoint) + '">' + esc(it.endpoint) + '</span>' +
        '<b>' + fmtNum(it.calls) + '</b></div>' +
        '<div class="dash-track"><i style="width:' + pct + '%"></i></div>' +
        '<div class="dash-bar-sub">成功率 ' + ((it.success_rate || 0) * 100).toFixed(1) +
        '% · 平均 ' + fmtNum(it.avg_latency_ms) + ' ms</div></div>';
    }).join('');
  }

  function dashTagList(items, action) {
    if (!items.length) {
      return emptyState(action === 'write' ? '暂无打标数据' : '暂无查询数据',
        '接口产生流量后这里会出现 TOP 标签');
    }
    var max = 1;
    items.forEach(function (it) { max = Math.max(max, it.count || 0); });
    return items.map(function (it, idx) {
      var pct = Math.max(3, Math.round((it.count || 0) / max * 100));
      return '<div class="dash-tag-row">' +
        '<span class="dash-rank">' + String(idx + 1).padStart(2, '0') + '</span>' +
        '<div class="dash-tag-main">' +
        '<div class="dash-tag-name">' + esc(it.tag_name || it.tag_code) +
        '<span class="dash-tag-code">' + esc(it.tag_code) + '</span></div>' +
        '<div class="dash-track ' + action + '"><i style="width:' + pct + '%"></i></div></div>' +
        '<b class="dash-tag-count">' + fmtNum(it.count) + '</b></div>';
    }).join('');
  }

  function dashTenants(items) {
    if (!items.length) return emptyState('暂无租户调用', '外部租户调用接口后这里会出现排行');
    var max = 1;
    items.forEach(function (it) { max = Math.max(max, it.calls || 0); });
    return items.map(function (it, idx) {
      var pct = Math.max(3, Math.round((it.calls || 0) / max * 100));
      return '<div class="dash-tenant-row">' +
        '<span class="dash-rank">' + (idx + 1) + '</span>' +
        '<div class="dash-tenant-main">' +
        '<div class="dash-tenant-name">' + esc(it.tenant_name || it.tenant_id) +
        '<span class="mono">' + esc(it.tenant_id) + '</span></div>' +
        '<div class="dash-track"><i style="width:' + pct + '%"></i></div></div>' +
        '<div class="dash-tenant-nums"><b>' + fmtNum(it.calls) + '</b>' +
        '<span>打标 ' + fmtNum(it.writes) + ' · 查询 ' + fmtNum(it.queries) + '</span></div></div>';
    }).join('');
  }

  function dashRecent(rows) {
    if (!rows.length) return emptyState('暂无调用记录', '开放 API 被调用后这里会显示明细');
    var body = rows.map(function (r) {
      var caller = '<div class="cell-strong">' + esc(r.tenant_id || '未鉴权调用') + '</div>' +
        '<div class="cell-sub mono">' + esc(r.app_key || '--') +
        (r.operator_id ? ' · ' + esc(r.operator_id) : '') + '</div>';
      var api = '<div class="dash-api-line">' + methodBadge(r.method) +
        '<span class="mono" title="' + esc(r.endpoint) + '">' + esc(r.endpoint) + '</span></div>';
      var result = r.success ? badge('success') : badge('failed');
      if (!r.success && r.biz_code !== null && r.biz_code !== undefined) {
        result += '<div class="cell-sub">code ' + esc(r.biz_code) + '</div>';
      }
      var tags = [];
      if (r.write_count) tags.push('写入 ' + fmtNum(r.write_count));
      if (r.query_count) tags.push('查询 ' + fmtNum(r.query_count));
      if (r.delete_count) tags.push('作废 ' + fmtNum(r.delete_count));
      return '<tr>' +
        '<td class="nowrap">' + esc(r.called_at) + '</td>' +
        '<td>' + caller + '</td>' +
        '<td>' + api + '</td>' +
        '<td>' + result + '</td>' +
        '<td class="nowrap">' + fmtNum(r.duration_ms) + ' ms</td>' +
        '<td class="nowrap">' + (tags.length ? tags.join(' · ') : '--') + '</td></tr>';
    }).join('');
    return '<div class="table-wrap dash-table-wrap"><table class="dash-table"><thead><tr>' +
      '<th>时间</th><th>调用方</th><th>接口</th><th>结果</th><th>耗时</th><th>标签条数</th>' +
      '</tr></thead><tbody>' + body + '</tbody></table></div>';
  }

  function toggleDashboardFullscreen() {
    var el = $('#dashboard');
    if (!el) return;
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(function () {});
      return;
    }
    if (!el.requestFullscreen) {
      toast('当前浏览器不支持全屏', 'err');
      return;
    }
    el.requestFullscreen().catch(function () { toast('进入全屏失败', 'err'); });
  }


  export { renderRecords, renderProfile, renderDocs, renderDashboard, stopDashboard };
