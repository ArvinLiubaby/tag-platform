/** 控制台页面模块 · tags */
import * as core from '../core.js';
import { tenantChoiceOptions, selectedTenantChoices, formatTagVersionValue, sameVersionValue } from '../shared.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 1：标签字典（含定义审核）
   * ============================================================ */
  var tagsState = { status: '', keyword: '', tenantId: '', sensitivity: '', objectType: '', rows: [] };

  function tagOwner(tag) {
    var ids = String(tag && tag.tenant_id ? tag.tenant_id : '').split(',').map(function (v) { return v.trim(); }).filter(Boolean);
    return ids.length ? ids[0] : null;
  }

  function canEditTag(tag) {
    if (!canManageTagDefinitions()) return false;
    if (isAdmin()) return true;
    return isTenantAdmin() && !!tag && tagOwner(tag) === workTenantId();
  }

  function renderTags() {
    $('#content').innerHTML =
      '<div id="tag-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head has-divider">' +
      '    <div class="card-title">标签定义 <span class="count-chip" id="tag-count">--</span></div>' +
      '    <div class="card-actions">' +
      (canManageTagDefinitions() ? '<button class="btn btn-primary btn-sm tag-register-btn" id="tag-register">' +
        '<svg class="ico" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>注册标签</button>' : '') +
      '    </div>' +
      '  </div>' +
      '  <div class="filter-bar">' +
      '    <div class="field"><span>租户</span><select id="tag-tenant"><option value="">全部租户</option></select></div>' +
      '    <div class="field"><span>敏感级</span><select id="tag-sensitivity">' +
      '      <option value="">全部</option><option value="L1">L1 公开</option>' +
      '      <option value="L2">L2 内部</option><option value="L3">L3 敏感</option></select></div>' +
      '    <div class="field"><span>状态</span><select id="tag-status">' +
      '      <option value="">全部</option><option value="online">已上线</option>' +
      '      <option value="offline">已下线</option><option value="reviewing">审核中</option>' +
      '      <option value="archived">已归档</option></select></div>' +
      '    <div class="field"><span>对象</span><select id="tag-object"><option value="">全部对象</option></select></div>' +
      '    <div class="field grow"><span>关键字</span><input id="tag-keyword" placeholder="搜索 tag_code 或标签名称"></div>' +
      '    <div class="filter-btns">' +
      '      <button class="btn btn-ghost" id="tag-reset">重置</button>' +
      '      <button class="btn btn-primary" id="tag-search">查询</button>' +
      '    </div>' +
      '  </div>' +
      '  <div id="tag-table"></div>' +
      '</div>' +
      '<div class="card">' +
      '  <div class="card-head">' +
      '    <div class="card-title">定义审核流水 <span class="count-chip" id="audit-count">--</span></div>' +
      '    <div class="card-actions">' +
      '      <select id="audit-status" class="select-sm">' +
      '        <option value="reviewing" selected>待审核</option><option value="">全部</option>' +
      '        <option value="approved">已通过</option><option value="rejected">已驳回</option></select>' +
      '      <button class="btn btn-ghost btn-sm" id="audit-refresh">刷新</button>' +
      '    </div>' +
      '  </div>' +
      '  <div id="tag-audit-table"></div>' +
      '</div>';

    $('#tag-tenant').value = tagsState.tenantId;
    $('#tag-sensitivity').value = tagsState.sensitivity;
    $('#tag-status').value = tagsState.status;
    $('#tag-object').value = tagsState.objectType;
    $('#tag-keyword').value = tagsState.keyword;
    $('#tag-search').addEventListener('click', function () {
      tagsState.tenantId = $('#tag-tenant').value;
      tagsState.sensitivity = $('#tag-sensitivity').value;
      tagsState.status = $('#tag-status').value;
      tagsState.objectType = $('#tag-object').value;
      tagsState.keyword = $('#tag-keyword').value.trim();
      loadTags();
    });
    $('#tag-reset').addEventListener('click', function () {
      tagsState.status = '';
      tagsState.keyword = '';
      tagsState.tenantId = '';
      tagsState.sensitivity = '';
      tagsState.objectType = '';
      $('#tag-tenant').value = '';
      $('#tag-sensitivity').value = '';
      $('#tag-status').value = '';
      $('#tag-object').value = '';
      $('#tag-keyword').value = '';
      loadTags();
    });
    $('#tag-keyword').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') $('#tag-search').click();
    });
    if ($('#tag-register')) $('#tag-register').addEventListener('click', openRegisterModal);
    $('#audit-refresh').addEventListener('click', function () { loadTagAudits($('#audit-status').value); });
    $('#audit-status').addEventListener('change', function () { loadTagAudits(this.value); });

    loadTagStats();
    loadTags();
    loadTagAudits('reviewing');
  }

  function loadTagStats() {
    var host = $('#tag-stats');
    if (!host) return;
    host.innerHTML = statCards([
      { label: '标签总数', value: '--', icon: 'tag' }, { label: '已上线', value: '--', icon: 'check' },
      { label: '审核中', value: '--', icon: 'clock' }, { label: '已下线', value: '--', icon: 'pause' }
    ]);
    api('/tags?includeArchived=true').then(function (allRows) {
      if (!host) return;
      allRows = allRows || [];
      fillTenantOptions(allRows);
      fillObjectOptions(allRows);
      var rows = allRows.filter(function (t) { return t.status !== 'archived'; });
      var online = 0, reviewing = 0, offline = 0;
      rows.forEach(function (t) {
        if (t.status === 'online') online++;
        else if (t.status === 'reviewing') reviewing++;
        else if (t.status === 'offline') offline++;
      });
      host.innerHTML = statCards([
        { label: '标签总数', value: rows.length, hint: '全部租户 · 不含归档', icon: 'tag' },
        { label: '已上线', value: online, hint: '可用于打标', tone: 'green', icon: 'check' },
        { label: '审核中', value: reviewing, hint: '等待定义审核', tone: 'amber', icon: 'clock' },
        { label: '已下线', value: offline, hint: '暂不可用', tone: 'gray', icon: 'pause' }
      ]);
    }).catch(function () { host.innerHTML = ''; });
  }

  /** 用全量标签中的租户 ID 填充筛选下拉（PUBLIC 置顶） */
  function fillTenantOptions(rows) {
    var sel = $('#tag-tenant');
    if (!sel) return;
    var current = tagsState.tenantId || sel.value;
    var tenants = [];
    (rows || []).forEach(function (t) {
      String(t.tenant_id || '').split(',').forEach(function (raw) {
        var id = raw.trim();
        if (id && tenants.indexOf(id) === -1) tenants.push(id);
      });
    });
    tenants.sort(function (a, b) {
      if (a === 'PUBLIC') return -1;
      if (b === 'PUBLIC') return 1;
      return a.localeCompare(b);
    });
    sel.innerHTML = '<option value="">全部租户</option>' + tenants.map(function (id) {
      return '<option value="' + esc(id) + '">' +
        (id === 'PUBLIC' ? 'PUBLIC（全部租户）' : esc(id)) + '</option>';
    }).join('');
    sel.value = tenants.indexOf(current) >= 0 ? current : '';
    if (sel.value === '') tagsState.tenantId = '';
  }

  /** 用全量标签中的 object_type 填充对象筛选下拉（支持逗号分隔多值） */
  function fillObjectOptions(rows) {
    var sel = $('#tag-object');
    if (!sel) return;
    var current = tagsState.objectType || sel.value;
    var types = [];
    (rows || []).forEach(function (t) {
      (t.object_type || '').split(',').forEach(function (raw) {
        var type = raw.trim();
        if (type && types.indexOf(type) === -1) types.push(type);
      });
    });
    types.sort(function (a, b) { return a.localeCompare(b); });
    sel.innerHTML = '<option value="">全部对象</option>' + types.map(function (type) {
      return '<option value="' + esc(type) + '">' + esc(type) + '</option>';
    }).join('');
    sel.value = types.indexOf(current) >= 0 ? current : '';
    if (sel.value === '') tagsState.objectType = '';
  }

  var SOURCE_LABELS = { manual: '人工打标', statistics: '数据统计', hybrid: '手工+统计', algo: '算法模型', derived: '实时派生' };

  function sourceBadge(mode) {
    if (!mode) return '<span class="muted">--</span>';
    return '<span class="badge badge-gray" title="' + esc(mode) + '">' + esc(SOURCE_LABELS[mode] || mode) + '</span>';
  }

  function sensitivityBadge(level) {
    if (!level) return '<span class="muted">--</span>';
    var cls = level === 'L3' ? 'badge-red' : level === 'L2' ? 'badge-blue' : 'badge-gray';
    return '<span class="badge ' + cls + '">' + esc(level) + '</span>';
  }

  function enumValuesOf(tag) {
    if (!tag || tag.value_type !== 'enum' || !tag.value_range) return [];
    try {
      var root = JSON.parse(tag.value_range);
      var values = Array.isArray(root) ? root : root.enum;
      if (!Array.isArray(values)) return [];
      return values.map(function (v) { return String(v); });
    } catch (e) {
      return [];
    }
  }

  function valueRangeCell(tag) {
    if (tag.value_type !== 'enum') return '<span class="muted">--</span>';
    var values = enumValuesOf(tag);
    if (!values.length) return '<span class="badge badge-red">未配置</span>';
    var mode = '<span class="badge ' + (tag.value_multiple ? 'badge-blue' : 'badge-gray') + '">' +
      (tag.value_multiple ? '多选' : '单选') + '</span>';
    var preview = values.slice(0, 2).map(esc).join(' / ') +
      (values.length > 2 ? ' +' + (values.length - 2) : '');
    return '<div class="cell-strong">' + mode + '</div>' +
      '<div class="cell-sub" title="' + esc(values.join(' / ')) + '">' + esc(preview) + '</div>';
  }

  function tenantSummary(tag) {
    var ids = String(tag.tenant_id || '').split(',').map(function (v) { return v.trim(); })
      .filter(Boolean);
    if (!ids.length) return '<span class="muted">--</span>';
    if (ids.indexOf('PUBLIC') >= 0) {
      return '<span class="badge badge-blue">全部租户</span><div class="cell-sub">平台标签</div>';
    }
    var title = '归属 ' + ids[0] + '；适用 ' + ids.join(' / ');
    var text = ids[0] + (ids.length > 1 ? ' +' + (ids.length - 1) : '');
    return '<span class="cell-strong" title="' + esc(title) + '">' + esc(text) + '</span>' +
      '<div class="cell-sub">归属 ' + esc(ids[0]) + '</div>';
  }

  function enumValuesText(valueRange) {
    try {
      var root = JSON.parse(valueRange || '');
      var values = Array.isArray(root) ? root : root.enum;
      return Array.isArray(values) ? values.join('\n') : '';
    } catch (e) {
      return '';
    }
  }

  function syncEnumFields(prefix) {
    var typeEl = $('#' + prefix + '-vtype');
    if (!typeEl) return;
    var isEnum = typeEl.value === 'enum';
    var modeField = $('#' + prefix + '-mode-field');
    if (modeField) modeField.classList.toggle('hidden', !isEnum);
    var label = $('#' + prefix + '-range-label');
    if (label) {
      label.textContent = isEnum
        ? '枚举值（每行一个，至少 1 项）'
        : '值域 JSON（number: {"min":0,"max":100}；其他可留空）';
    }
    var range = $('#' + prefix + '-range');
    if (range) {
      range.placeholder = isEnum ? '公共道路\n港口\n矿区\n园区\n工地\n其他' : '可留空';
      range.rows = isEnum ? 6 : 2;
    }
    var tip = $('#' + prefix + '-range-tip');
    if (tip) {
      tip.textContent = isEnum
        ? '打标时只能从这些枚举值中选择；多选写入用英文逗号分隔，例如：公共道路,港口。枚举值不能包含英文逗号。'
        : 'number 类型可填写 {"min":0,"max":100}；其他类型可留空。';
    }
  }

  function buildValueRange(prefix) {
    var type = $('#' + prefix + '-vtype').value;
    var raw = $('#' + prefix + '-range').value;
    if (type !== 'enum') {
      raw = raw.trim();
      return raw || null;
    }
    var lines = raw.split(/\r?\n/).map(function (v) { return v.trim(); }).filter(Boolean);
    if (!lines.length) {
      toast('enum 标签至少配置一个枚举值', 'err');
      return null;
    }
    var seen = {};
    for (var i = 0; i < lines.length; i++) {
      if (seen[lines[i]]) {
        toast('枚举值重复：' + lines[i], 'err');
        return null;
      }
      if (lines[i].indexOf(',') >= 0) {
        toast('枚举值不能包含英文逗号：' + lines[i], 'err');
        return null;
      }
      seen[lines[i]] = true;
    }
    return JSON.stringify({ enum: lines });
  }

  function valueMultiplePayload(prefix) {
    if ($('#' + prefix + '-vtype').value !== 'enum') return false;
    return $('#' + prefix + '-mode').value === 'multi';
  }

  function loadTags() {
    $('#tag-table').innerHTML = tableLoading(12, 7);
    api('/tags' + qs({
      status: tagsState.status, keyword: tagsState.keyword,
      tenantId: tagsState.tenantId, sensitivity: tagsState.sensitivity,
      objectType: tagsState.objectType
    })).then(function (rows) {
      rows = rows || [];
      tagsState.rows = rows;
      if ($('#tag-count')) $('#tag-count').textContent = rows.length;
      if (!rows.length) {
        $('#tag-table').innerHTML = emptyState('暂无标签', '调整筛选条件，或注册新的标签定义');
        return;
      }
      var html = '<table><thead><tr>' +
        '<th>标签</th><th>类目 / 对象</th><th>类型</th><th>值域 / 选择</th><th>适用租户 / 归属</th><th>敏感级</th>' +
        '<th>来源</th><th>可写入</th><th>值审核</th><th>TTL</th><th>状态</th><th>操作</th>' +
        '</tr></thead><tbody>';
      rows.forEach(function (t) {
        var op = '--';
        if (canEditTag(t)) {
          if (t.tag_type === 'derived') {
            op = t.status === 'archived'
              ? '<span class="badge badge-gray">已删除</span>'
              : '<a class="btn btn-link btn-primary btn-sm" href="#/tag-compose?tag=' + encodeURIComponent(t.tag_code) +
                '">组合规则</a>';
          } else if (t.status === 'online') {
            op = '<button class="btn btn-ghost btn-sm" data-offline="' + esc(t.tag_code) + '">下线</button>' +
              '<button class="btn btn-link btn-primary btn-sm" data-edit="' + esc(t.tag_code) + '">编辑</button>';
          } else if (t.status === 'reviewing') {
            op = '<span class="badge badge-blue">审核中</span>';
          } else if (t.status === 'archived') {
            op = '<button class="btn btn-ghost btn-sm" data-restore="' + esc(t.tag_code) + '" data-name="' +
              esc(t.tag_name) + '">恢复</button>';
          } else {
            op = '<button class="btn btn-ghost btn-sm" data-online="' + esc(t.tag_code) + '">上线</button>' +
              '<button class="btn btn-link btn-primary btn-sm" data-edit="' + esc(t.tag_code) + '">编辑</button>' +
              '<button class="btn btn-link btn-danger btn-sm" data-archive="' + esc(t.tag_code) +
              '" data-name="' + esc(t.tag_name) + '">归档</button>';
          }
        } else if (isTenantAdmin()) {
          op = '<span class="badge badge-gray">只读</span>';
        }
        html += '<tr>' +
          '<td><div class="tag-ident">' +
          '<div class="tag-ident-top">' +
          '<span class="cell-strong" title="' + esc(t.tag_name) + '">' + esc(t.tag_name) + '</span>' +
          '<button type="button" class="version-link' + versionTone(t.version) + '" data-versions="' + esc(t.tag_code) +
          '" title="查看版本历史 · 当前 v' + esc(t.version) + '">v' + esc(t.version) + '</button>' +
          '</div>' +
          '<div class="cell-sub mono tag-code-line"><span title="' + esc(t.tag_code) + '">' + esc(t.tag_code) + '</span></div>' +
          '</div></td>' +
          '<td>' + dash(t.category_code) + '<div class="cell-sub">' + dash(t.object_type) + '</div></td>' +
          '<td>' + esc(t.value_type) + '<div class="cell-sub">' + esc(tagTypeLabel(t.tag_type)) + '</div></td>' +
          '<td>' + valueRangeCell(t) + '</td>' +
          '<td>' + tenantSummary(t) + '</td>' +
          '<td>' + sensitivityBadge(t.sensitivity) + '</td>' +
          '<td>' + sourceBadge(t.source_mode) + '</td>' +
          '<td class="nowrap">' + renderWritableCell(t) + '</td>' +
          '<td>' + (t.need_value_audit ? '<span class="badge badge-orange">需审核</span>' : '<span class="muted">--</span>') + '</td>' +
          '<td class="nowrap">' + (t.value_ttl_days ? t.value_ttl_days + ' 天' : '不过期') + '</td>' +
          '<td>' + badge(t.status) + '</td>' +
          '<td class="nowrap"><div class="row-actions">' + op + '</div></td>' +
          '</tr>';
      });
      $('#tag-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#tag-table').querySelectorAll('[data-online]').forEach(function (btn) {
        btn.addEventListener('click', function () { changeTagStatus(btn.getAttribute('data-online'), 'online'); });
      });
      $('#tag-table').querySelectorAll('[data-offline]').forEach(function (btn) {
        btn.addEventListener('click', function () { changeTagStatus(btn.getAttribute('data-offline'), 'offline'); });
      });
      $('#tag-table').querySelectorAll('[data-writable-on]').forEach(function (btn) {
        btn.addEventListener('click', function () { changeTagWritable(btn.getAttribute('data-writable-on'), true); });
      });
      $('#tag-table').querySelectorAll('[data-writable-off]').forEach(function (btn) {
        btn.addEventListener('click', function () { changeTagWritable(btn.getAttribute('data-writable-off'), false); });
      });
      $('#tag-table').querySelectorAll('[data-edit]').forEach(function (btn) {
        btn.addEventListener('click', function () { openEditTag(btn.getAttribute('data-edit')); });
      });
      $('#tag-table').querySelectorAll('[data-versions]').forEach(function (btn) {
        btn.addEventListener('click', function () { openTagVersions(btn.getAttribute('data-versions')); });
      });
      $('#tag-table').querySelectorAll('[data-archive]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          openArchiveTag(btn.getAttribute('data-archive'), btn.getAttribute('data-name'));
        });
      });
      $('#tag-table').querySelectorAll('[data-restore]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          openRestoreTag(btn.getAttribute('data-restore'), btn.getAttribute('data-name'));
        });
      });
    }).catch(function (e) {
      $('#tag-table').innerHTML = emptyState('加载失败', e.message);
    });
  }

  function changeTagStatus(tagCode, status) {
    api('/tags/' + encodeURIComponent(tagCode) + '/status', { method: 'POST', body: { status: status } })
      .then(function () { toast(tagCode + ' 已' + (status === 'online' ? '上线' : '下线'), 'ok'); loadTags(); loadTagStats(); })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  function openArchiveTag(tagCode, tagName) {
    var body =
      '<div class="notice notice-warn">归档是软删除：标签将从标签字典默认列表隐藏，开放 API 的标签清单和写入不再包含该标签；' +
      '历史标签值、定义审核流水和审计记录都会保留，已产生的值不会被清除。</div>' +
      '<p style="margin-top:14px">确认归档 <b class="mono">' + esc(tagCode) + '</b>' +
      (tagName ? ' · ' + esc(tagName) : '') + '？</p>' +
      '<div class="field" style="margin-top:14px"><span>输入 tag_code 以确认</span>' +
      '<input id="archive-confirm-code" autocomplete="off" placeholder="' + esc(tagCode) + '"></div>' +
      '<p class="warn-text">归档后 tag_code 仍被占用，不能重新注册同名标签；恢复后状态为「已下线」，需重新上线。</p>';
    openModal('归档标签定义 · ' + esc(tagCode), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认归档', cls: 'btn-danger', onClick: function () {
          if ($('#archive-confirm-code').value.trim() !== tagCode) {
            toast('请输入完整的 tag_code 以确认归档', 'err');
            return;
          }
          var btn = this;
          setBusy(btn, true);
          api('/tags/' + encodeURIComponent(tagCode) + '/archive', { method: 'POST' })
            .then(function () {
              closeModal();
              toast('标签已归档: ' + tagCode, 'ok');
              loadTags();
              loadTagStats();
            }).catch(function (e) {
              setBusy(btn, false);
              toast(e.message, 'err');
            });
        } }
    ]);
  }

  function openRestoreTag(tagCode, tagName) {
    var body = '<p>确认将标签 <b class="mono">' + esc(tagCode) + '</b>' +
      (tagName ? ' · ' + esc(tagName) : '') + ' 恢复为「已下线」？</p>' +
      '<p class="warn-text">恢复后不会自动上线；需要重新上线后才会对开放 API 可见。</p>';
    openModal('恢复归档标签 · ' + esc(tagCode), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认恢复', cls: 'btn-primary', onClick: function () {
          var btn = this;
          setBusy(btn, true);
          api('/tags/' + encodeURIComponent(tagCode) + '/restore', { method: 'POST' })
            .then(function () {
              closeModal();
              toast('标签已恢复为已下线: ' + tagCode, 'ok');
              loadTags();
              loadTagStats();
            }).catch(function (e) {
              setBusy(btn, false);
              toast(e.message, 'err');
            });
        } }
    ]);
  }

  // 版本徽标色阶：v1 灰 / v2-3 蓝 / v4-5 紫 / v6+ 青
  function versionTone(v) {
    var n = parseInt(v, 10) || 1;
    if (n >= 6) return ' v-teal';
    if (n >= 4) return ' v-violet';
    if (n >= 2) return ' v-blue';
    return ' v-gray';
  }

  function tagTypeLabel(type) {
    return { fact: '事实', rule: '规则', algo: '算法', derived: '派生' }[type] || type || '--';
  }

  // 写入开关单元格：状态开关（仅管理员可切换）；已归档标签固定为不可写
  function renderWritableCell(t) {
    if (t.tag_type === 'derived') {
      return '<span class="badge badge-blue">实时计算</span>';
    }
    if (t.status === 'archived') {
      return '<span class="switch off is-static" title="已归档标签不可写入">' +
        '<span class="switch-track"><span class="switch-thumb"></span></span>' +
        '<span class="switch-label">禁止</span></span>';
    }
    var on = !!t.writable;
    var inner = '<span class="switch-track"><span class="switch-thumb"></span></span>' +
      '<span class="switch-label">' + (on ? '允许' : '禁止') + '</span>';
    if (!canEditTag(t)) {
      return '<span class="switch ' + (on ? 'on' : 'off') + ' is-static">' + inner + '</span>';
    }
    return '<button type="button" class="switch ' + (on ? 'on' : 'off') + '" ' +
      (on ? 'data-writable-off="' + esc(t.tag_code) + '"' : 'data-writable-on="' + esc(t.tag_code) + '"') +
      ' title="点击' + (on ? '禁止' : '允许') + '写入">' + inner + '</button>';
  }

  function changeTagWritable(tagCode, writable) {
    api('/tags/' + encodeURIComponent(tagCode) + '/writable', { method: 'POST', body: { writable: writable } })
      .then(function () { toast(tagCode + ' 已' + (writable ? '允许写入' : '禁止写入'), 'ok'); loadTags(); })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  function editOption(value, label, current) {
    var cur = current === null || current === undefined ? '' : current;
    return '<option value="' + esc(value) + '"' + (value === cur ? ' selected' : '') + '>' + esc(label) + '</option>';
  }

  function findTagRow(tagCode) {
    var rows = tagsState.rows || [];
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].tag_code === tagCode) return rows[i];
    }
    return null;
  }

  function openEditTag(tagCode) {
    var t = findTagRow(tagCode);
    if (!t) { toast('标签数据已刷新，请重试', 'err'); return; }
    if (!canEditTag(t)) { toast('只有标签归属租户管理员或平台管理员可编辑', 'err'); return; }
    if (t.status === 'archived') { toast('已归档标签不可编辑，请先恢复', 'err'); return; }
    if (t.status === 'reviewing') { toast('审核中的标签不可编辑', 'err'); return; }

    Promise.all([api('/tag-categories'), api('/tenants')]).then(function (res) {
      var cats = res[0];
      var tenants = res[1];
      var categoryOptions = '<option value="">未分类</option>' + (cats || []).map(function (c) {
        var code = c.category_code || c.categoryCode;
        var name = c.category_name || c.categoryName || code;
        return editOption(code, name, t.category_code);
      }).join('');
      var knownCategory = (cats || []).some(function (c) {
        return (c.category_code || c.categoryCode) === t.category_code;
      });
      if (t.category_code && !knownCategory) {
        categoryOptions += editOption(t.category_code, t.category_code, t.category_code);
      }

      var tenantEditor = isAdmin()
        ? '<div class="field span-2"><span>适用租户（可多选）</span>' +
          tenantChoiceOptions(tenants, t.tenant_id, 'edit') +
          '<div class="form-tip">PUBLIC = 全部租户；第一个适用租户为标签归属方</div></div>'
        : '<div class="field span-2"><span>适用租户 / 归属</span><div class="readonly-box">' +
          esc(t.tenant_id) + '</div><div class="form-tip">标签归属第一个租户；租户管理员不能修改适用租户列表</div></div>';
      var body = '' +
        (t.status === 'online'
          ? '<div class="notice notice-warn">该标签已上线，保存后将立即影响开放 API 的标签清单与写入校验。</div>'
          : '') +
        '<div class="modal-form"><section class="form-section">' +
        '<div class="form-section-head"><span class="form-section-index">01</span><div><h4>标签定义</h4>' +
        '<p>保存后立即生效并生成新版本；值域和适用租户变更不会迁移历史值。</p></div></div>' +
        '<div class="form-grid">' +
        '  <div class="field"><span>tag_code（不可修改）</span><input id="edit-code" value="' + esc(t.tag_code) + '" readonly></div>' +
        tenantEditor +
        '  <div class="field"><span>标签名称 <i class="req">*</i></span><input id="edit-name" value="' + esc(t.tag_name) + '"></div>' +
        '  <div class="field"><span>类目</span><select id="edit-category">' + categoryOptions + '</select></div>' +
        '  <div class="field"><span>适用对象（逗号分隔多值；留空=不限）</span><input id="edit-object" value="' +
        esc(t.object_type || '') + '" placeholder="driver / driver,vehicle"></div>' +
        '  <div class="field"><span>值类型 <i class="req">*</i></span><select id="edit-vtype">' +
        editOption('boolean', 'boolean', t.value_type) + editOption('number', 'number', t.value_type) +
        editOption('enum', 'enum', t.value_type) + editOption('string', 'string', t.value_type) +
        editOption('date', 'date', t.value_type) + '</select></div>' +
        '  <div class="field" id="edit-mode-field"><span>选择方式</span><select id="edit-mode">' +
        '     <option value="single">单选</option><option value="multi">多选</option></select>' +
        '     <div class="form-tip">仅 enum 标签生效</div></div>' +
        '  <div class="field"><span>标签类型 <i class="req">*</i></span><select id="edit-ttype">' +
        editOption('fact', 'fact 事实', t.tag_type) + editOption('rule', 'rule 规则', t.tag_type) +
        editOption('algo', 'algo 算法', t.tag_type) + '</select></div>' +
        '  <div class="field"><span>来源类型（打标方式）</span><select id="edit-source">' +
        editOption('', '未声明', t.source_mode) + editOption('manual', 'manual 客服手工', t.source_mode) +
        editOption('statistics', 'statistics 数据统计', t.source_mode) +
        editOption('hybrid', 'hybrid 手工+统计', t.source_mode) +
        editOption('algo', 'algo 算法模型', t.source_mode) + '</select></div>' +
        '  <div class="field"><span>敏感级 <i class="req">*</i></span><select id="edit-sens">' +
        editOption('L1', 'L1 公开', t.sensitivity) + editOption('L2', 'L2 内部', t.sensitivity) +
        editOption('L3', 'L3 敏感', t.sensitivity) + '</select></div>' +
        '  <div class="field"><span>值 TTL（天，0=不过期）</span><input id="edit-ttl" type="number" min="0" value="' +
        esc(t.value_ttl_days) + '"></div>' +
        '  <div class="field span-2"><span id="edit-range-label">值域</span>' +
        '     <textarea id="edit-range" rows="4">' +
        esc(t.value_type === 'enum' ? enumValuesText(t.value_range) : (t.value_range || '')) +
        '</textarea><div class="form-tip" id="edit-range-tip"></div></div>' +
        '  <div class="field span-2"><span>需要值审核</span>' +
        '     <input id="edit-need-audit" type="checkbox"' + (t.need_value_audit ? ' checked' : '') + '>' +
        '     <div class="form-tip">勾选后写入该标签的值将进入待审核队列，必须填写打标理由</div></div>' +
        '  <div class="field span-2"><span>允许写入（开放 API 推送）</span>' +
        '     <input id="edit-writable" type="checkbox"' + (t.writable ? ' checked' : '') + '>' +
        '     <div class="form-tip">关闭后外部系统通过开放 API 推送/作废该标签数据将被拒绝（code=3012）</div></div>' +
        '</div></section></div>' +
        '<div class="notice notice-info" style="margin-top:14px">修改会立即生效、记录审计并生成新版本（可在标签列表的 vN 版本历史中查看 diff 或回滚）。' +
        '修改值类型、值域、适用租户或对象范围不会迁移历史标签值，仅影响后续写入校验。</div>';

      openModal('修改标签定义 · ' + esc(t.tag_code), body, [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '保存修改', cls: 'btn-primary', onClick: function () {
            var btn = this;
            var name = $('#edit-name').value.trim();
            if (!name) { toast('标签名称不能为空', 'err'); return; }
            var ttl = parseInt($('#edit-ttl').value || '0', 10);
            if (isNaN(ttl) || ttl < 0) { toast('值 TTL 必须为不小于 0 的整数', 'err'); return; }
            var tenantIds = isAdmin() ? selectedTenantChoices('edit') : t.tenant_id;
            if (!tenantIds) { toast('至少选择一个适用租户', 'err'); return; }
            var rangeValue = buildValueRange('edit');
            if ($('#edit-vtype').value === 'enum' && rangeValue === null) return;
            var payload = {
              tag_name: name,
              category_code: $('#edit-category').value || null,
              tenant_id: tenantIds,
              value_type: $('#edit-vtype').value,
              tag_type: $('#edit-ttype').value,
              sensitivity: $('#edit-sens').value,
              value_ttl_days: ttl,
              object_type: $('#edit-object').value.trim() || null,
              source_mode: $('#edit-source').value || null,
              value_multiple: valueMultiplePayload('edit'),
              need_value_audit: $('#edit-need-audit').checked,
              writable: $('#edit-writable').checked,
              value_range: rangeValue,
              version: t.version
            };
            setBusy(btn, true);
            api('/tags/' + encodeURIComponent(t.tag_code), { method: 'PUT', body: payload })
              .then(function () {
                closeModal();
                toast('标签定义已更新', 'ok');
                loadTags();
                loadTagStats();
              }).catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          } }
      ]);
      $('#edit-vtype').addEventListener('change', function () { syncEnumFields('edit'); });
      $('#edit-mode').value = t.value_multiple ? 'multi' : 'single';
      syncEnumFields('edit');
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  var TAG_VERSION_FIELDS = [
    ['tag_name', '标签名称'], ['category_code', '类目'], ['value_type', '值类型'],
    ['value_range', '值域'], ['value_multiple', '选择方式'], ['tag_type', '标签类型'],
    ['need_value_audit', '值审核'], ['value_ttl_days', 'TTL（天）'], ['tenant_id', '适用租户'],
    ['sensitivity', '敏感级'], ['object_type', '适用对象'], ['source_mode', '来源类型'],
    ['writable', '允许写入']
  ];

  var TAG_CHANGE_LABELS = {
    baseline: '基线', create: '注册', update: '修改', writable: '写入开关', rollback: '回滚', market: '市场授权'
  };

  function openTagVersions(tagCode) {
    var tag = findTagRow(tagCode);
    if (!tag) { toast('标签数据已刷新，请重试', 'err'); return; }
    api('/tags/' + encodeURIComponent(tagCode) + '/versions?limit=100').then(function (versions) {
      versions = versions || [];
      if (!versions.length) {
        openModal('版本历史 · ' + esc(tagCode),
          emptyState('暂无版本记录', '该标签尚未产生定义快照'), [{ text: '关闭', onClick: closeModal }], 'lg');
        return;
      }
      var currentVersion = tag.version || versions[0].version;
      var rows = versions.map(function (v) {
        var label = TAG_CHANGE_LABELS[v.change_type] || v.change_type;
        return '<button type="button" class="version-item" data-version="' + v.version + '">' +
          '<span class="version-no">v' + v.version + '</span>' +
          '<span class="version-main"><span class="version-item-head"><b>' + esc(label) + '</b>' +
          (v.version === currentVersion ? '<span class="badge badge-green">当前</span>' : '') + '</span>' +
          '<small>' + esc(v.change_by || '--') + ' · ' + fmtDt(v.created_at) + '</small>' +
          '<span class="version-item-summary">' + esc(v.change_summary || '--') + '</span></span></button>';
      }).join('');
      var body = '<div class="version-layout">' +
        '<div class="version-list">' + rows + '</div>' +
        '<div class="version-detail" id="version-detail">' + emptyState('选择版本', '查看该版本的定义与变更') + '</div>' +
        '</div>' +
        '<div class="version-note">上下线、归档属于生命周期操作，不生成定义版本；回滚会生成新版本，历史版本不会删除。</div>';
      openModal('版本历史 · ' + esc(tagCode), body, [{ text: '关闭', cls: 'btn-ghost', onClick: closeModal }], 'xl');
      var scope = $('#modal-body');
      scope.querySelectorAll('[data-version]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          loadTagVersionDetail(tagCode, Number(btn.getAttribute('data-version')), tag, currentVersion);
        });
      });
      loadTagVersionDetail(tagCode, versions[0].version, tag, currentVersion);
    }).catch(function (e) { toast('版本历史加载失败：' + e.message, 'err'); });
  }

  function loadTagVersionDetail(tagCode, version, tag, currentVersion) {
    api('/tags/' + encodeURIComponent(tagCode) + '/versions/' + version).then(function (detail) {
      var host = $('#version-detail');
      if (!host) return;
      host.innerHTML = renderTagVersionDetail(tagCode, detail, tag, currentVersion);
      var scope = $('#modal-body');
      scope.querySelectorAll('.version-item').forEach(function (item) {
        item.classList.toggle('active', Number(item.getAttribute('data-version')) === version);
      });
      var rollback = $('#version-rollback');
      if (rollback) rollback.addEventListener('click', function () {
        openRollbackTag(tag, version, currentVersion);
      });
    }).catch(function (e) {
      var host = $('#version-detail');
      if (host) host.innerHTML = emptyState('版本加载失败', e.message);
    });
  }

  function renderTagVersionDetail(tagCode, detail, tag, currentVersion) {
    var summary = detail.version || {};
    var def = detail.definition || {};
    var prev = detail.previous;
    var changed = [];
    TAG_VERSION_FIELDS.forEach(function (field) {
      var key = field[0];
      var oldValue = prev ? prev[key] : undefined;
      var newValue = def[key];
      if (!prev || !sameVersionValue(oldValue, newValue)) {
        changed.push({ key: key, label: field[1], oldValue: prev ? oldValue : null, newValue: newValue });
      }
    });
    var diffRows = changed.length ? changed.map(function (row) {
      return '<div class="version-diff-row">' +
        '<span class="version-diff-field">' + esc(row.label) + '</span>' +
        '<span class="version-diff-old">' + esc(formatTagVersionValue(row.key, row.oldValue)) + '</span>' +
        '<span class="version-diff-new">' + esc(formatTagVersionValue(row.key, row.newValue)) + '</span></div>';
    }).join('') : '<div class="version-no-change">与上一版本相比没有定义字段变化（仅版本号/操作记录变化）</div>';
    var canRollback = canEditTag(tag) && summary.version !== currentVersion
      && ['online', 'offline'].indexOf(tag.status) >= 0;
    return '<div class="version-detail-head">' +
      '<div><span class="version-detail-no">v' + esc(summary.version) + '</span>' +
      '<span class="badge badge-blue">' + esc(TAG_CHANGE_LABELS[summary.change_type] || summary.change_type) + '</span>' +
      (summary.version === currentVersion ? '<span class="badge badge-green">当前版本</span>' : '') + '</div>' +
      '<div class="version-detail-meta">' + esc(summary.change_by || '--') + ' · ' + fmtDt(summary.created_at) + '</div>' +
      '</div>' +
      '<div class="version-summary">' + esc(summary.change_summary || '--') + '</div>' +
      (prev ? '' : '<div class="version-origin">初始版本定义</div>') +
      '<div class="version-diff-head"><span>字段</span><span>' + (prev ? '上一版本' : '初始') + '</span><span>本版本</span></div>' +
      '<div class="version-diff">' + diffRows + '</div>' +
      '<div class="version-detail-actions">' +
      (canRollback
        ? '<button type="button" class="btn btn-primary btn-sm" id="version-rollback">回滚到此版本</button>'
        : '<span class="muted">' + (!canEditTag(tag)
          ? (isTenantAdmin() ? '仅标签归属租户可回滚' : '仅管理员可回滚')
          : summary.version === currentVersion ? '这是当前版本' : '当前状态不可回滚') + '</span>') +
      '</div>';
  }

  function openRollbackTag(tag, targetVersion, currentVersion) {
    var body = '<div class="notice notice-warn">回滚会把该标签的定义恢复为 <b>v' + targetVersion + '</b> 的内容，' +
      '并在当前版本基础上生成 <b>v' + (currentVersion + 1) + '</b>。历史版本全部保留，标签上下线状态不变。</div>' +
      '<p class="version-rollback-target">目标：<span class="mono">' + esc(tag.tag_code) + '</span>' +
      ' · 当前 v' + currentVersion + ' → 回滚 v' + targetVersion + '</p>' +
      '<div class="form-tip">回滚会立即影响开放 API 的标签清单、值域校验与打标表单；请确认已完成评审。</div>';
    openModal('回滚标签定义 · ' + esc(tag.tag_code), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认回滚', cls: 'btn-primary', onClick: function () {
          var btn = this;
          setBusy(btn, true);
          api('/tags/' + encodeURIComponent(tag.tag_code) + '/versions/' + targetVersion + '/rollback', { method: 'POST' })
            .then(function (updated) {
              closeModal();
              toast('已回滚到 v' + targetVersion + '，当前版本 v' + updated.version, 'ok');
              loadTags();
              loadTagStats();
            }).catch(function (e) {
              setBusy(btn, false);
              toast(e.message, 'err');
            });
        } }
    ], 'lg');
  }

  function openRegisterModal() {
    if (!canManageTagDefinitions()) { toast('当前角色无标签管理权限', 'err'); return; }
    Promise.all([api('/tag-categories'), api('/tenants')]).then(function (res) {
      var cats = res[0];
      var tenants = res[1];
      var options = (cats || []).map(function (c) {
        return '<option value="' + esc(c.category_code || c.categoryCode) + '">' +
          esc(c.category_name || c.categoryName || c.category_code) + '</option>';
      }).join('');
      var tenantEditor = isAdmin()
        ? '<div class="field span-2"><span>适用租户（可多选）</span>' +
          tenantChoiceOptions(tenants, workTenantId() || 'risk_control', 'reg') +
          '<div class="form-tip">PUBLIC = 全部租户；第一个适用租户为标签归属方</div></div>'
        : '<div class="field span-2"><span>归属租户</span><div class="readonly-box">' +
          esc(workTenantId()) + '</div><div class="form-tip">租户管理员只能为本租户注册标签</div></div>';
      var body = '<div class="modal-form">' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">01</span><div><h4>基础定义</h4>' +
        '    <p>标签编码创建后不可修改，名称与类目用于业务检索。</p></div></div>' +
        '  <div class="form-grid">' +
        '    <div class="field"><span>tag_code <i class="req">*</i></span><input id="reg-code" placeholder="^[a-z][a-z0-9_]{2,63}$"></div>' +
        '    <div class="field"><span>标签名称 <i class="req">*</i></span><input id="reg-name" placeholder="中文名称"></div>' +
        '    <div class="field"><span>类目</span><select id="reg-category">' + options + '</select></div>' +
        '    <div class="field"><span>适用对象</span><input id="reg-object" placeholder="driver / driver,vehicle（留空=不限）"></div>' +
        '  </div>' +
        '</section>' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">02</span><div><h4>值域与类型</h4>' +
        '    <p>值类型、选择方式和枚举值域决定后续写入校验与工作台控件。</p></div></div>' +
        '  <div class="form-grid">' +
        '    <div class="field"><span>值类型 <i class="req">*</i></span><select id="reg-vtype">' +
        '      <option value="boolean">boolean</option><option value="number">number</option>' +
        '      <option value="enum">enum</option><option value="string">string</option><option value="date">date</option></select></div>' +
        '    <div class="field" id="reg-mode-field"><span>选择方式</span><select id="reg-mode">' +
        '      <option value="single">单选</option><option value="multi">多选</option></select>' +
        '      <div class="form-tip">仅 enum 标签生效</div></div>' +
        '    <div class="field"><span>标签类型 <i class="req">*</i></span><select id="reg-ttype">' +
        '      <option value="fact">fact 事实</option><option value="rule">rule 规则</option><option value="algo">algo 算法</option></select></div>' +
        '    <div class="field"><span>来源类型</span><select id="reg-source">' +
        '      <option value="">未声明</option><option value="manual">manual 客服手工</option>' +
        '      <option value="statistics">statistics 数据统计</option><option value="hybrid">hybrid 手工+统计</option>' +
        '      <option value="algo">algo 算法模型</option></select></div>' +
        '    <div class="field span-2"><span id="reg-range-label">值域</span>' +
        '      <textarea id="reg-range" rows="3" placeholder="enum 请每行一个枚举值；number 可填 JSON 范围；其他类型可留空"></textarea>' +
        '      <div class="form-tip" id="reg-range-tip"></div></div>' +
        '  </div>' +
        '</section>' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">03</span><div><h4>归属与治理</h4>' +
        '    <p>第一个适用租户为标签归属方；敏感标签必须填写打标理由。</p></div></div>' +
        '  <div class="form-grid">' +
        tenantEditor +
        '    <div class="field"><span>敏感级 <i class="req">*</i></span><select id="reg-sens">' +
        '      <option value="L1">L1 公开</option><option value="L2" selected>L2 内部</option><option value="L3">L3 敏感</option></select></div>' +
        '    <div class="field"><span>值 TTL（天，0=不过期）</span><input id="reg-ttl" type="number" min="0" value="0"></div>' +
        '    <div class="option-grid span-2">' +
        '      <label class="option-card"><input id="reg-need-audit" type="checkbox">' +
        '        <span><b>需要值审核</b><small>写入该标签的值进入待审核队列，必须填写打标理由</small></span></label>' +
        '      <label class="option-card"><input id="reg-writable" type="checkbox" checked>' +
        '        <span><b>允许开放 API 写入</b><small>关闭后外部推送/作废该标签数据返回 code=3012</small></span></label>' +
        '    </div>' +
        '  </div>' +
        '</section>' +
        '</div>';
      openModal('注册标签定义', body,
        [
          { text: '取消', onClick: closeModal },
          {
            text: '提交注册', cls: 'btn-primary', onClick: function () {
              var btn = this;
              var tenantIds = isAdmin() ? selectedTenantChoices('reg') : workTenantId();
              if (!tenantIds) { toast('至少选择一个适用租户', 'err'); return; }
              var rangeValue = buildValueRange('reg');
              if ($('#reg-vtype').value === 'enum' && rangeValue === null) return;
              var body = {
                tag_code: $('#reg-code').value.trim(),
                tag_name: $('#reg-name').value.trim(),
                category_code: $('#reg-category').value,
                value_type: $('#reg-vtype').value,
                tag_type: $('#reg-ttype').value,
                sensitivity: $('#reg-sens').value,
                value_ttl_days: parseInt($('#reg-ttl').value || '0', 10),
                tenant_id: tenantIds,
                need_value_audit: $('#reg-need-audit').checked,
                writable: $('#reg-writable').checked,
                value_range: rangeValue,
                value_multiple: valueMultiplePayload('reg'),
                object_type: $('#reg-object').value.trim() || null,
                source_mode: $('#reg-source').value || null
              };
              setBusy(btn, true);
              api('/tags', { method: 'POST', body: body }).then(function () {
                closeModal();
                toast('注册成功，等待定义审核', 'ok');
                loadTags();
                loadTagStats();
                loadTagAudits('reviewing');
              }).catch(function (e) { toast(e.message, 'err'); })
                .finally(function () { setBusy(btn, false); });
            }
          }
        ]);
      $('#reg-vtype').addEventListener('change', function () { syncEnumFields('reg'); });
      syncEnumFields('reg');
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function loadTagAudits(status) {
    $('#tag-audit-table').innerHTML = tableLoading(9, 4);
    api('/tag-audits' + qs({ status: status })).then(function (rows) {
      rows = rows || [];
      if ($('#audit-count')) $('#audit-count').textContent = rows.length;
      if (!rows.length) {
        $('#tag-audit-table').innerHTML = emptyState('暂无审核流水', '新的标签定义提交后会出现在这里');
        return;
      }
      var html = '<table><thead><tr><th>ID</th><th>tag_code</th><th>类型</th><th>提交人</th>' +
        '<th>审核人</th><th>审核意见</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (a) {
        var op = '<span class="muted">--</span>';
        if (isAdmin() && (a.status === 'reviewing' || a.status === 'pending')) {
          op = '<button class="btn btn-primary btn-sm" data-approve="' + a.id + '">通过</button> ' +
            '<button class="btn btn-danger btn-sm" data-reject="' + a.id + '">驳回</button>';
        }
        var actionLabel = a.action_type === 'register' ? '注册' : (a.action_type === 'update' ? '变更' : '');
        html += '<tr>' +
          '<td class="mono">#' + a.id + '</td>' +
          '<td class="mono">' + esc(a.tag_code) + '</td>' +
          '<td>' + (actionLabel ? esc(actionLabel) + '<div class="cell-sub mono">' + dash(a.action_type) + '</div>' : dash(a.action_type)) + '</td>' +
          '<td>' + dash(a.submit_by) + '</td>' +
          '<td>' + dash(a.audit_by) + '</td>' +
          '<td>' + (a.audit_remark
            ? '<span class="cell-detail" title="' + esc(a.audit_remark) + '">' + esc(a.audit_remark) + '</span>'
            : '--') + '</td>' +
          '<td>' + badge(a.status) + '</td>' +
          '<td class="nowrap">' + dash(a.created_at) + '</td>' +
          '<td class="nowrap"><div class="row-actions">' + op + '</div></td></tr>';
      });
      $('#tag-audit-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#tag-audit-table').querySelectorAll('[data-approve]').forEach(function (btn) {
        btn.addEventListener('click', function () { decideTagAudit(btn.getAttribute('data-approve'), true); });
      });
      $('#tag-audit-table').querySelectorAll('[data-reject]').forEach(function (btn) {
        btn.addEventListener('click', function () { decideTagAudit(btn.getAttribute('data-reject'), false); });
      });
    }).catch(function (e) {
      $('#tag-audit-table').innerHTML = emptyState('加载失败', e.message);
    });
  }

  function decideTagAudit(id, approved) {
    openModal((approved ? '通过' : '驳回') + '定义审核单 #' + id,
      '<div class="field"><span>审核意见' + (approved ? '（通过必填）' : '') + '</span>' +
      '<input id="tag-audit-remark" placeholder="审核意见"></div>',
      [
        { text: '取消', onClick: closeModal },
        {
          text: approved ? '确认通过' : '确认驳回', cls: approved ? 'btn-primary' : 'btn-danger', onClick: function () {
            var btn = this;
            var remark = $('#tag-audit-remark').value.trim();
            if (approved && !remark) { toast('通过审核必须填写审核意见', 'err'); return; }
            setBusy(btn, true);
            api('/tag-audits/' + id + (approved ? '/approve' : '/reject'),
              { method: 'POST', body: { audit_remark: remark } })
              .then(function () {
                closeModal();
                toast('审核单已处理', 'ok');
                loadTagAudits($('#audit-status').value);
                loadTags();
                loadTagStats();
              }).catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          }
        }
      ]);
  }


  export { renderTags };
