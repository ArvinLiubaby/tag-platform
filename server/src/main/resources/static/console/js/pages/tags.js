/** 控制台页面模块 · tags */
import * as core from '../core.js';
import { tenantChoiceOptions, selectedTenantChoices, formatTagVersionValue, sameVersionValue } from '../shared.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 1：标签字典（含定义审核）
   * ============================================================ */
  var tagsState = { status: '', keyword: '', tenantId: '', sensitivity: '', objectType: '', rows: [] };
  // 维度编码 → { name, parent }；标签编码 → 扩展属性值（列表渲染用）
  var tagsCatMap = {};
  var tagsExtMap = {};

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
      (canManageTagDefinitions() ? '<button class="btn btn-sm dim-manage-btn" id="dim-manage-btn" title="管理维度树与一级维度展示优先级">' +
        '<svg class="ico" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
          '<rect x="9" y="3" width="6" height="5" rx="1.7"/><rect x="3" y="16" width="6" height="5" rx="1.7"/><rect x="15" y="16" width="6" height="5" rx="1.7"/>' +
          '<path d="M12 8v4M6 16v-4h12v4"/></svg>维度管理</button>' : '') +
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
    if ($('#dim-manage-btn')) $('#dim-manage-btn').addEventListener('click', openDimManage);
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
      allRows = (allRows || []).filter(function (t) { return t.status !== 'deleted'; });
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
        { label: '标签总数', value: rows.length, hint: '全部租户 · 不含归档与已删除', icon: 'tag' },
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
    // 单租户时第二行不再重复一遍「归属 xxx」（既冗余又必然被列宽截断）
    var sub = ids.length > 1
      ? '<div class="cell-sub" title="' + esc(title) + '">归属 ' + esc(ids[0]) + '</div>'
      : '';
    return '<span class="cell-strong" title="' + esc(title) + '">' + esc(text) + '</span>' + sub;
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
    $('#tag-table').innerHTML = tableLoading(13, 7);
    var extTenant = tagsState.tenantId || workTenantId() || '';
    Promise.all([
      api('/tags' + qs({
        status: tagsState.status, keyword: tagsState.keyword,
        tenantId: tagsState.tenantId, sensitivity: tagsState.sensitivity,
        objectType: tagsState.objectType
      })),
      api('/tag-categories').catch(function () { return []; }),
      extTenant
        ? api('/tag-ext-summary' + qs({ tenantId: extTenant })).catch(function () { return {}; })
        : Promise.resolve({})
    ]).then(function (res) {
      var rows = res[0] || [];
      var cats = res[1] || [];
      tagsExtMap = res[2] || {};
      tagsCatMap = {};
      cats.forEach(function (c) {
        var code = c.category_code || c.categoryCode;
        tagsCatMap[code] = {
          name: c.category_name || c.categoryName || code,
          parent: c.parent_code || c.parentCode || null
        };
      });
      tagsState.rows = rows;
      if ($('#tag-count')) $('#tag-count').textContent = rows.length;
      if (!rows.length) {
        $('#tag-table').innerHTML = emptyState('暂无标签', '调整筛选条件，或注册新的标签定义');
        return;
      }
      var html = '<table><thead><tr>' +
        '<th>标签</th><th>维度 / 对象</th><th>类型</th><th>值域 / 选择</th><th>单位</th><th>适用租户 / 归属</th><th>敏感级</th>' +
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
              esc(t.tag_name) + '">恢复</button>' +
              '<button class="btn btn-link btn-danger btn-sm" data-purge="' + esc(t.tag_code) +
              '" data-name="' + esc(t.tag_name) + '">彻底删除</button>';
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
          tagExtBadges(t) +
          '</div></td>' +
          '<td>' + dimCell(t) + '</td>' +
          '<td>' + esc(t.value_type) + '<div class="cell-sub">' + esc(tagTypeLabel(t.tag_type)) + '</div></td>' +
          '<td>' + valueRangeCell(t) + '</td>' +
          '<td>' + (t.unit ? '<span class="badge badge-gray">' + esc(t.unit) + '</span>' : '<span class="muted">--</span>') + '</td>' +
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
      $('#tag-table').querySelectorAll('[data-purge]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          openPurgeTag(btn.getAttribute('data-purge'), btn.getAttribute('data-name'));
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

  /** 彻底删除已归档标签：控制台不再展示；数据库记录与打标流水保留可查，控制台不可恢复 */
  function openPurgeTag(tagCode, tagName) {
    var body =
      '<div class="notice notice-warn">彻底删除后，标签将从控制台<b>所有列表中消失且不可恢复</b>；' +
      '底层数据库记录、历史标签值、打标流水与审核记录仍完整保留可查；tag_code 永久占用，不能重新注册同名标签。</div>' +
      '<p style="margin-top:14px">确认彻底删除 <b class="mono">' + esc(tagCode) + '</b>' +
      (tagName ? ' · ' + esc(tagName) : '') + '？</p>' +
      '<div class="field" style="margin-top:14px"><span>输入 tag_code 以确认</span>' +
      '<input id="purge-confirm-code" autocomplete="off" placeholder="' + esc(tagCode) + '"></div>' +
      '<p class="warn-text">仅已归档标签可彻底删除；该操作无法在界面撤销。</p>';
    openModal('彻底删除标签 · ' + esc(tagCode), body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认彻底删除', cls: 'btn-danger', onClick: function () {
          if ($('#purge-confirm-code').value.trim() !== tagCode) {
            toast('请输入完整的 tag_code 以确认删除', 'err');
            return;
          }
          var btn = this;
          setBusy(btn, true);
          api('/tags/' + encodeURIComponent(tagCode) + '/delete', { method: 'POST' })
            .then(function () {
              closeModal();
              toast('标签已彻底删除: ' + tagCode, 'ok');
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

  /** 维度单元格：一级维度 / 二级维度 · 对象类型 */
  function dimCell(t) {
    var d2 = tagsCatMap[t.dim2_code || t.category_code];
    var d1code = t.dim1_code || (d2 && d2.parent);
    var d1 = tagsCatMap[d1code];
    var top = d1 ? d1.name : (d1code || '--');
    var sub = d2 ? d2.name : (t.category_code || '--');
    var subText = sub + ' · ' + dash(t.object_type);
    // 列宽有限，超长会省略号截断；title 保证悬停可看全
    return '<div class="cell-strong" title="' + esc(top) + '">' + esc(top) + '</div>' +
      '<div class="cell-sub" title="' + esc(subText) + '">' + esc(subText) + '</div>';
  }

  /** 标签名下方的优先级与租户扩展徽章（展示屏幕 / 隐藏） */
  function tagExtBadges(t) {
    var ext = tagsExtMap[t.tag_code] || {};
    var out = [];
    if (t.priority !== null && t.priority !== undefined && t.priority !== '') {
      out.push('<span class="badge badge-gray" title="标签优先级（人工打标顺序）">P' + esc(t.priority) + '</span>');
    }
    if (ext.show_screen) {
      out.push('<span class="badge ' + (ext.show_screen === '主' ? 'badge-blue' : 'badge-gray') + '" title="标签展示屏幕">' +
        esc(ext.show_screen) + '屏</span>');
    }
    if (String(ext.hidden) === 'true') {
      out.push('<span class="badge badge-orange" title="已在标签管理中隐藏">隐藏</span>');
    }
    return out.length ? '<div style="display:flex;gap:4px;flex-wrap:wrap;margin-top:5px">' + out.join('') + '</div>' : '';
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
      return '<span class="switch ' + (on ? 'on' : 'off') + ' is-static is-locked" title="当前账号无权限修改写入开关">' + inner + '</span>';
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

  /* ---------------- 维度管理（tag_category 增删改 · 草稿保存） ---------------- */

  /** 维度折叠状态（localStorage 记忆）：返回收起的一级维度编码数组 */
  function readDimCollapsed() {
    try {
      var raw = localStorage.getItem('tp.dim.collapsed');
      var arr = raw ? JSON.parse(raw) : [];
      return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
  }

  function saveDimCollapsed(list) {
    try { localStorage.setItem('tp.dim.collapsed', JSON.stringify(list || [])); } catch (e) { /* 隐私模式忽略 */ }
  }

  /**
   * 维度管理草稿上下文：draft 为本地维度列表（增删改只改这里，点「保存变更」才落库），
   * prio 为一级维度展示优先级。null 表示当前没有打开的维度管理会话。
   */
  var dimCtx = null;
  var dimCloseGuardBound = false;

  function openDimManage() {
    if (dimCtx) { showDimManage(); return; }
    var dimTenant = tagsState.tenantId || workTenantId() || '';
    Promise.all([
      api('/tag-categories'),
      dimTenant ? api('/tag-dim-priorities' + qs({ tenantId: dimTenant })).catch(function () { return []; })
                : Promise.resolve([])
    ]).then(function (res) {
      var cats = res[0] || [];
      var origPrio = {};
      (res[1] || []).forEach(function (it) { origPrio[it.dim_code] = it.priority; });
      var orig = {};
      var draft = cats.map(function (c) {
        var item = {
          code: c.category_code,
          name: c.category_name,
          sort: c.sort_no,
          parent: c.parent_code || null,
          isNew: false,
          deleted: false
        };
        orig[item.code] = { name: item.name, sort: Number(item.sort), parent: item.parent };
        return item;
      });
      dimCtx = {
        tenant: dimTenant,
        draft: draft,
        orig: orig,
        prio: Object.assign({}, origPrio),
        origPrio: Object.assign({}, origPrio),
        collapsed: readDimCollapsed()
      };
      showDimManage();
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function dimAlive(ctx) {
    return ctx.draft.filter(function (d) { return !d.deleted; });
  }

  function dimTops(ctx) {
    return dimAlive(ctx).filter(function (d) { return !d.parent; });
  }

  function dimSubs(ctx, code) {
    return dimAlive(ctx).filter(function (d) { return d.parent === code; });
  }

  /** 单项是否有逐字段改动（对比打开弹窗时的快照） */
  function dimChanged(ctx, d) {
    if (d.isNew) return true;
    var o = ctx.orig[d.code];
    if (!o) return true;
    return d.name !== o.name
      || Number(d.sort) !== Number(o.sort)
      || (d.parent || null) !== (o.parent || null);
  }

  function dimPrioChanged(ctx) {
    var tops = dimTops(ctx);
    for (var i = 0; i < tops.length; i++) {
      var code = tops[i].code;
      var a = ctx.prio[code] == null ? null : Number(ctx.prio[code]);
      var b = ctx.origPrio[code] == null ? null : Number(ctx.origPrio[code]);
      if (a !== b) return true;
    }
    return false;
  }

  function dimDirtyCount(ctx) {
    if (!ctx) return 0;
    var n = 0;
    ctx.draft.forEach(function (d) { if (d.deleted || dimChanged(ctx, d)) n++; });
    if (dimPrioChanged(ctx)) n++;
    return n;
  }

  function dimFlag(ctx, d) {
    if (d.isNew) return '<span class="dim-flag is-new">待新增</span>';
    if (dimChanged(ctx, d)) return '<span class="dim-flag">已修改</span>';
    return '';
  }

  function dimNextSort(ctx, parent) {
    var sibs = dimAlive(ctx).filter(function (d) { return (d.parent || null) === (parent || null); });
    var max = 0;
    sibs.forEach(function (s) {
      var n = Number(s.sort);
      if (!isNaN(n) && n > max) max = n;
    });
    return max + 10;
  }

  /** 列头 + 树 + 底部新增入口 */
  function dimBodyHtml(ctx) {
    var tops = dimTops(ctx);
    var subCount = dimAlive(ctx).length - tops.length;
    var iconEdit = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>';
    var iconDel = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>';
    var iconChevron = '<svg class="dim-chevron" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>';

    var prioCell = function (code) {
      if (!ctx.tenant) {
        return '<span class="dim-prio is-empty is-readonly" title="先在标签字典页顶部选择租户，再配置展示优先级">—</span>';
      }
      var v = ctx.prio[code];
      return '<button type="button" class="dim-prio' + (v == null ? ' is-empty' : '') +
        '" data-dim-prio="' + esc(code) + '" title="点击编辑展示优先级（数字越小越靠前）">' +
        (v == null ? '—' : 'P' + v) + '</button>';
    };

    var subRow = function (s) {
      return '<div class="dim-row dim-row-sub' + (dimChanged(ctx, s) ? ' is-dirty' : '') + '">' +
        '<span class="dim-line-cell"></span>' +
        '<span class="dim-cell-name">' +
        '<span class="dim-name">' + esc(s.name) + '</span>' +
        dimFlag(ctx, s) +
        '<span class="mono dim-code">' + esc(s.code) + '</span>' +
        '</span>' +
        '<span class="dim-sort">' + esc(String(s.sort == null ? '-' : s.sort)) + '</span>' +
        '<span class="dim-prio-cell"><span class="dim-prio is-empty is-readonly" title="展示优先级按一级维度配置">—</span></span>' +
        '<span class="row-actions">' +
        '<button class="dim-act" data-cat-edit="' + esc(s.code) + '" title="编辑维度">' + iconEdit + '</button>' +
        '<button class="dim-act is-danger" data-cat-del="' + esc(s.code) + '" title="删除维度">' + iconDel + '</button>' +
        '</span></div>';
    };

    var topRow = function (t) {
      var subs = dimSubs(ctx, t.code);
      var collapsed = ctx.collapsed.indexOf(t.code) >= 0;
      return '<div class="dim-row dim-row-top' + (collapsed ? ' is-collapsed' : '') +
          (dimChanged(ctx, t) ? ' is-dirty' : '') + '">' +
        '<button type="button" class="dim-toggle" data-dim-toggle="' + esc(t.code) + '"' +
          (subs.length ? '' : ' disabled') +
          ' title="' + (subs.length ? (collapsed ? '展开二级维度' : '收起二级维度') : '暂无二级维度') + '">' +
          iconChevron + '</button>' +
        '<span class="dim-cell-name">' +
        '<span class="dim-name" data-dim-toggle="' + esc(t.code) + '">' + esc(t.name) + '</span>' +
        (subs.length ? '<span class="dim-subcount" title="二级维度数量">' + subs.length + '</span>' : '') +
        dimFlag(ctx, t) +
        '<span class="mono dim-code">' + esc(t.code) + '</span>' +
        '</span>' +
        '<span class="dim-sort">' + esc(String(t.sort == null ? '-' : t.sort)) + '</span>' +
        '<span class="dim-prio-cell">' + prioCell(t.code) + '</span>' +
        '<span class="row-actions">' +
        '<button class="dim-act" data-cat-edit="' + esc(t.code) + '" title="编辑维度">' + iconEdit + '</button>' +
        '<button class="dim-act is-danger" data-cat-del="' + esc(t.code) + '" title="删除维度">' + iconDel + '</button>' +
        '<button class="dim-add-sub-chip" data-cat-addsub="' + esc(t.code) + '" title="在该一级维度下新增二级维度">＋ 二级</button>' +
        '</span></div>';
    };

    var tree = tops.length
      ? tops.map(function (t) {
          var subs = dimSubs(ctx, t.code);
          var collapsed = ctx.collapsed.indexOf(t.code) >= 0;
          return '<div class="dim-group" data-group="' + esc(t.code) + '">' + topRow(t) +
            '<div class="dim-subs"' + (collapsed ? ' hidden' : '') + '>' +
            subs.map(subRow).join('') + '</div></div>';
        }).join('')
      : emptyState('暂无维度', '点击「新增一级维度」创建第一个一级维度');

    return '<div class="notice notice-info">' +
        '一级维度为分类树顶层，二级维度挂其下（用于标签字典分组与画像展示）。存在下级维度或被<b>在用标签</b>引用的维度不可删除' +
        '（归档 / 已删除标签不占用维度）；<b>编码创建后不可修改</b>。所有改动先暂存，点击底部 <b>保存变更</b> 后才生效。</div>' +
      '<div class="dim-summary"><span><b>' + tops.length + '</b> 个一级维度 · <b>' + subCount + '</b> 个二级维度</span>' +
        '<span class="dim-bulk"><a data-dim-all="open">全部展开</a><i>·</i><a data-dim-all="close">全部收起</a></span></div>' +
      '<div class="dim-tree">' +
        '<div class="dim-head">' +
          '<span></span>' +
          '<span>维度名称 / 编码</span>' +
          '<span class="is-center" title="分类树的全局排序号：数字越小越靠前，所有租户一致">排序<i class="dim-col-tip">?</i></span>' +
          '<span class="is-center" title="画像与标签字典的展示顺序：数字越小越靠前，按租户独立配置（仅一级维度）">优先级<i class="dim-col-tip">?</i></span>' +
          '<span class="is-end">操作</span>' +
        '</div>' + tree +
      '</div>' +
      '<div class="dim-foot">' +
        '<span class="muted" style="font-size:12px">共 ' + dimAlive(ctx).length + ' 个维度</span>' +
        '<span class="row-actions">' +
        '<button class="btn btn-ghost btn-sm" id="dim-add-sub">＋ 新增二级维度</button>' +
        '<button class="btn btn-outline btn-sm" id="dim-add-top">＋ 新增一级维度</button>' +
        '</span></div>';
  }

  /** 打开/重渲染维度管理弹窗（草稿保留） */
  function showDimManage() {
    if (!dimCtx) return;
    openModal('维度管理 · 标签分类树', dimBodyHtml(dimCtx), [], 'lg');
    $('#modal .modal').classList.add('modal-lg');
    renderDimFoot();
    bindDimEvents();
    bindDimCloseGuard();
  }

  /** 底部：未保存计数 + 关闭 / 放弃 / 保存变更 */
  function renderDimFoot() {
    var ctx = dimCtx;
    if (!ctx) return;
    var dirty = dimDirtyCount(ctx);
    var foot = $('#modal-foot');
    foot.innerHTML =
      '<span class="dim-dirty-hint' + (dirty ? ' is-on' : '') + '">' +
        (dirty ? '<i></i>' + dirty + ' 项变更待保存' : '当前无未保存变更') + '</span>' +
      '<span class="dim-foot-btns">' +
        '<button class="btn btn-ghost btn-sm" id="dim-close">关闭</button>' +
        (dirty ? '<button class="btn btn-ghost btn-sm" id="dim-reset">放弃变更</button>' : '') +
        '<button class="btn btn-primary btn-sm" id="dim-save"' + (dirty ? '' : ' disabled') + '>保存变更</button>' +
      '</span>';
    var closeBtn = $('#dim-close');
    if (closeBtn) closeBtn.addEventListener('click', dismissDimManage);
    var resetBtn = $('#dim-reset');
    if (resetBtn) resetBtn.addEventListener('click', resetDimDraft);
    var saveBtn = $('#dim-save');
    if (saveBtn) saveBtn.addEventListener('click', function () { saveDimChanges(saveBtn); });
  }

  function bindDimEvents() {
    var ctx = dimCtx;
    var body = $('#modal-body');

    body.querySelectorAll('[data-dim-toggle]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.stopPropagation();
        if (el.disabled) return;
        var code = el.getAttribute('data-dim-toggle');
        var group = el.closest('.dim-group');
        if (!group) return;
        var subs = group.querySelector('.dim-subs');
        var collapse = !subs.hasAttribute('hidden');
        if (collapse) subs.setAttribute('hidden', ''); else subs.removeAttribute('hidden');
        group.querySelector('.dim-row-top').classList.toggle('is-collapsed', collapse);
        ctx.collapsed = ctx.collapsed.filter(function (c) { return c !== code; });
        if (collapse) ctx.collapsed.push(code);
        saveDimCollapsed(ctx.collapsed);
      });
    });

    body.querySelectorAll('[data-dim-all]').forEach(function (a) {
      a.addEventListener('click', function () {
        var collapse = a.getAttribute('data-dim-all') === 'close';
        body.querySelectorAll('.dim-group').forEach(function (group) {
          var subs = group.querySelector('.dim-subs');
          var head = group.querySelector('.dim-row-top');
          if (collapse) subs.setAttribute('hidden', ''); else subs.removeAttribute('hidden');
          head.classList.toggle('is-collapsed', collapse);
        });
        ctx.collapsed = collapse ? dimTops(ctx).map(function (t) { return t.code; }) : [];
        saveDimCollapsed(ctx.collapsed);
      });
    });

    body.querySelectorAll('[data-dim-prio]').forEach(function (b) { bindDimPrio(b); });

    body.querySelectorAll('[data-cat-edit]').forEach(function (b) {
      b.addEventListener('click', function () {
        var code = b.getAttribute('data-cat-edit');
        var item = ctx.draft.filter(function (d) { return d.code === code; })[0];
        if (item) openDimForm({ item: item });
      });
    });

    body.querySelectorAll('[data-cat-del]').forEach(function (b) {
      b.addEventListener('click', function () { markDimDeleted(b.getAttribute('data-cat-del')); });
    });

    body.querySelectorAll('[data-cat-addsub]').forEach(function (b) {
      b.addEventListener('click', function () {
        openDimForm({ isSub: true, parentCode: b.getAttribute('data-cat-addsub') });
      });
    });

    var addTop = $('#dim-add-top');
    if (addTop) addTop.addEventListener('click', function () { openDimForm({}); });

    var addSub = $('#dim-add-sub');
    if (addSub) addSub.addEventListener('click', function () {
      var tops = dimTops(ctx);
      if (!tops.length) { toast('请先新增一个一级维度', 'err'); return; }
      openDimForm({ isSub: true, parentCode: tops[0].code });
    });
  }

  /** 优先级内联编辑：只改草稿，Enter/失焦确认、Esc 取消 */
  function bindDimPrio(btn) {
    btn.addEventListener('click', function () {
      if (btn.tagName !== 'BUTTON' || !dimCtx) return;
      var code = btn.getAttribute('data-dim-prio');
      var input = document.createElement('input');
      input.type = 'number';
      input.min = '0';
      input.className = 'dim-prio-input';
      input.value = dimCtx.prio[code] == null ? '' : dimCtx.prio[code];
      btn.replaceWith(input);
      input.focus();
      input.select();
      var done = false;
      var finish = function (commit) {
        if (done) return;
        done = true;
        if (commit) {
          var raw = input.value.trim();
          var val = raw === '' ? null : parseInt(raw, 10);
          if (val != null && (isNaN(val) || val < 0)) val = null;
          dimCtx.prio[code] = val;
        }
        var next = document.createElement('button');
        next.type = 'button';
        next.className = 'dim-prio' + (dimCtx.prio[code] == null ? ' is-empty' : '');
        next.setAttribute('data-dim-prio', code);
        next.title = '点击编辑展示优先级（数字越小越靠前）';
        next.textContent = dimCtx.prio[code] == null ? '—' : 'P' + dimCtx.prio[code];
        input.replaceWith(next);
        bindDimPrio(next);
        renderDimFoot();
      };
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); finish(true); }
        else if (e.key === 'Escape') { e.preventDefault(); finish(false); }
      });
      input.addEventListener('blur', function () { finish(true); });
    });
  }

  /** 删除：仅标记草稿，保存后才落库 */
  function markDimDeleted(code) {
    var ctx = dimCtx;
    if (!ctx) return;
    var item = ctx.draft.filter(function (d) { return d.code === code; })[0];
    if (!item) return;
    var subs = dimSubs(ctx, code);
    if (subs.length) {
      toast('该维度下还有 ' + subs.length + ' 个二级维度，请先删除或调整其父级', 'err');
      return;
    }
    if (item.isNew) {
      ctx.draft = ctx.draft.filter(function (d) { return d.code !== code; });
      toast('已移除待新增的维度', 'ok');
    } else {
      item.deleted = true;
      toast('已标记删除，点击「保存变更」后生效', 'ok');
    }
    showDimManage();
  }

  /** 新增/编辑维度表单：只写草稿，返回维度树 */
  function openDimForm(opt) {
    var ctx = dimCtx;
    var item = opt.item || null;
    var isEdit = !!item;
    var isSub = isEdit ? !!item.parent : !!opt.isSub;
    var tops = dimTops(ctx);
    var parentCode = isEdit ? (item.parent || '') : (opt.parentCode || '');

    var topOptions = function (selected, withEmpty) {
      return '<option value="">' + (withEmpty ? '（无 · 升级为一级维度）' : '请选择一级维度') + '</option>' +
        tops.filter(function (t) { return !isEdit || t.code !== item.code; })
          .map(function (t) {
            return '<option value="' + esc(t.code) + '"' + (t.code === selected ? ' selected' : '') + '>' +
              esc(t.name) + '</option>';
          }).join('');
    };

    var parentField = '';
    if (!isEdit && isSub) {
      parentField = '<div class="field"><span>父级维度 *</span><select id="dim-form-parent">' +
        (opt.parentCode ? topOptions(opt.parentCode, false) : topOptions('', false)) + '</select></div>';
    } else if (isEdit && isSub) {
      parentField = '<div class="field"><span>父级维度</span><select id="dim-form-parent">' +
        topOptions(parentCode, true) + '</select></div>';
    }

    var body = '<div class="modal-form"><div class="form-grid">' +
      '<div class="field"><span>维度编码 ' + (isEdit ? '' : '*') + '</span>' +
      (isEdit
        ? '<input type="text" value="' + esc(item.code) + '" disabled>'
        : '<input type="text" id="dim-form-code" autocomplete="off" placeholder="如 driver_traits（小写字母/数字/下划线）">') +
      '</div>' +
      '<div class="field"><span>维度名称 *</span>' +
      '<input type="text" id="dim-form-name" autocomplete="off" maxlength="128" value="' +
      (isEdit ? esc(item.name) : '') + '"></div>' +
      parentField +
      '<div class="field"><span>排序（全局，数字越小越靠前）</span>' +
      '<input type="number" id="dim-form-sort" value="' +
      (isEdit && item.sort != null ? esc(String(item.sort)) : '') + '" placeholder="留空 = 同级末尾"></div>' +
      '</div></div>' +
      '<div class="notice notice-info" style="margin-top:10px">' +
      (isEdit ? '编码不可修改；调整父级时，存在下级维度的节点会被拒绝。' : '将加入待保存列表，点击底部「保存变更」后才写入。') +
      '</div>';

    openModal((isEdit ? '编辑维度 · ' + esc(item.code) : '新增' + (isSub ? '二级' : '一级') + '维度'), body, [
      { text: '取消', cls: 'btn-ghost', onClick: showDimManage },
      { text: isEdit ? '确定修改' : '加入待保存', cls: 'btn-primary', onClick: function () {
          var nameInput = $('#dim-form-name');
          var name = nameInput ? nameInput.value.trim() : '';
          if (!name) { toast('请填写维度名称', 'err'); return; }
          var sortEl = $('#dim-form-sort');
          var sortRaw = sortEl ? sortEl.value.trim() : '';
          var sortNo = sortRaw === '' ? null : parseInt(sortRaw, 10);
          if (sortNo != null && (isNaN(sortNo) || sortNo < 0)) { toast('排序需为非负整数', 'err'); return; }
          var parentSel = $('#dim-form-parent');
          var newParent = parentSel ? (parentSel.value || null) : (isEdit ? (item.parent || null) : null);

          if (isEdit) {
            item.name = name;
            if (sortNo != null) item.sort = sortNo;
            item.parent = newParent;
            toast('已修改，点击「保存变更」后生效', 'ok');
          } else {
            var codeInput = $('#dim-form-code');
            var code = codeInput ? codeInput.value.trim() : '';
            if (!/^[a-z][a-z0-9_]{1,63}$/.test(code)) {
              toast('维度编码需为小写字母开头、2~64 位小写字母/数字/下划线', 'err');
              return;
            }
            var dup = ctx.draft.filter(function (d) { return !d.deleted && d.code === code; })[0];
            if (dup) { toast('维度编码已存在: ' + code, 'err'); return; }
            ctx.draft.push({
              code: code,
              name: name,
              sort: sortNo == null ? dimNextSort(ctx, newParent) : sortNo,
              parent: newParent,
              isNew: true,
              deleted: false
            });
            toast('已加入待保存列表', 'ok');
          }
          showDimManage();
        } }
    ]);
  }

  /** 保存变更：维度结构走批量接口（单事务），优先级走租户级接口，先结构后优先级 */
  function saveDimChanges(btn) {
    var ctx = dimCtx;
    if (!ctx) return;
    var creates = [];
    var updates = [];
    var deletes = [];
    ctx.draft.forEach(function (d) {
      if (d.deleted) {
        if (!d.isNew) deletes.push(d.code);
        return;
      }
      if (d.isNew) {
        creates.push({
          category_code: d.code,
          category_name: d.name,
          parent_code: d.parent || null,
          sort_no: d.sort == null ? null : Number(d.sort)
        });
        return;
      }
      var o = ctx.orig[d.code];
      if (!o) return;
      var changed = d.name !== o.name
        || Number(d.sort) !== Number(o.sort)
        || (d.parent || null) !== (o.parent || null);
      if (!changed) return;
      updates.push({
        category_code: d.code,
        category_name: d.name,
        sort_no: d.sort == null ? null : Number(d.sort),
        parent_code: d.parent || null,
        parent_changed: (d.parent || null) !== (o.parent || null)
      });
    });

    var prioChanged = dimPrioChanged(ctx);
    var hasDim = creates.length > 0 || updates.length > 0 || deletes.length > 0;
    if (!hasDim && !prioChanged) { toast('没有需要保存的变更', 'info'); return; }

    if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }

    var step = Promise.resolve();
    if (hasDim) {
      step = step.then(function () {
        return api('/tag-categories/batch', {
          method: 'POST',
          body: { creates: creates, updates: updates, deletes: deletes }
        });
      });
    }
    if (prioChanged) {
      step = step.then(function () {
        return api('/tag-dim-priorities' + qs({ tenantId: ctx.tenant }), {
          method: 'PUT',
          body: { items: dimTops(ctx).map(function (t) {
            return {
              dimCode: t.code,
              dimName: t.name,
              priority: ctx.prio[t.code] == null ? null : Number(ctx.prio[t.code])
            };
          }) }
        });
      });
    }
    step.then(function () {
      dimCtx = null;
      toast('维度变更已保存', 'ok');
      loadTags();
      openDimManage();
    }).catch(function (e) {
      if (btn) { btn.disabled = false; btn.textContent = '保存变更'; }
      toast(e.message || '保存失败', 'err');
    });
  }

  /** 关闭：有未保存变更时二次确认 */
  function dismissDimManage() {
    if (!dimCtx) { closeModal(); return; }
    var dirty = dimDirtyCount(dimCtx);
    if (!dirty) { dimCtx = null; closeModal(); return; }
    openModal('放弃未保存的变更？',
      '<div class="notice notice-warn">当前有 <b>' + dirty + '</b> 项变更尚未保存，关闭后将全部丢失。</div>',
      [
        { text: '继续编辑', cls: 'btn-ghost', onClick: showDimManage },
        { text: '放弃变更', cls: 'btn-danger', onClick: function () { dimCtx = null; closeModal(); } }
      ]);
  }

  function resetDimDraft() {
    if (!dimCtx) return;
    openModal('放弃未保存的变更？',
      '<div class="notice notice-warn">将丢弃当前 <b>' + dimDirtyCount(dimCtx) + '</b> 项变更，恢复为已保存的状态。</div>',
      [
        { text: '继续编辑', cls: 'btn-ghost', onClick: showDimManage },
        { text: '放弃变更', cls: 'btn-danger', onClick: function () { dimCtx = null; openDimManage(); } }
      ]);
  }

  /** 右上角 X 同样拦截：仅在维度管理视图且存在未保存变更时接管 */
  function bindDimCloseGuard() {
    if (dimCloseGuardBound) return;
    dimCloseGuardBound = true;
    $('#modal-close').addEventListener('click', function (e) {
      var title = $('#modal-title') ? $('#modal-title').textContent : '';
      if (!dimCtx || title.indexOf('维度管理') !== 0) return;
      if (!dimDirtyCount(dimCtx)) return;
      e.stopImmediatePropagation();
      e.preventDefault();
      dismissDimManage();
    }, true);
  }

  function openEditTag(tagCode) {
    var t = findTagRow(tagCode);
    if (!t) { toast('标签数据已刷新，请重试', 'err'); return; }
    if (!canEditTag(t)) { toast('只有标签归属租户管理员或平台管理员可编辑', 'err'); return; }
    if (t.status === 'archived') { toast('已归档标签不可编辑，请先恢复', 'err'); return; }
    if (t.status === 'reviewing') { toast('审核中的标签不可编辑', 'err'); return; }

    var extTenant = tagsState.tenantId || workTenantId() || String(t.tenant_id || '').split(',')[0].trim();
    Promise.all([
      api('/tag-categories'),
      api('/tenants'),
      api('/tags/' + encodeURIComponent(tagCode) + '/ext' + qs({ tenantId: extTenant }))
        .catch(function () { return { attrs: [] }; })
    ]).then(function (res) {
      var cats = res[0];
      var tenants = res[1];
      var extView = res[2] || { attrs: [] };
      var catMap = {};
      (cats || []).forEach(function (c) {
        var code = c.category_code || c.categoryCode;
        catMap[code] = {
          name: c.category_name || c.categoryName || code,
          parent: c.parent_code || c.parentCode || null
        };
      });
      // 一级维度
      var dim1Options = '<option value="">未设置</option>' + (cats || []).filter(function (c) {
        return !(c.parent_code || c.parentCode);
      }).map(function (c) {
        var code = c.category_code || c.categoryCode;
        return editOption(code, c.category_name || c.categoryName || code, t.dim1_code);
      }).join('');
      // 二级维度：按一级分组展示
      var groups = {};
      (cats || []).filter(function (c) {
        return !!(c.parent_code || c.parentCode);
      }).forEach(function (c) {
        var p = c.parent_code || c.parentCode;
        (groups[p] = groups[p] || []).push(c);
      });
      var categoryOptions = '<option value="">未设置</option>';
      Object.keys(groups).forEach(function (p) {
        categoryOptions += '<optgroup label="' + esc(catMap[p] ? catMap[p].name : p) + '">';
        groups[p].forEach(function (c) {
          var code = c.category_code || c.categoryCode;
          categoryOptions += editOption(code, c.category_name || c.categoryName || code,
            t.dim2_code || t.category_code);
        });
        categoryOptions += '</optgroup>';
      });
      var knownCategory = catMap[t.category_code];
      if (t.category_code && !knownCategory) {
        categoryOptions += editOption(t.category_code, t.category_code, t.category_code);
      }

      var tenantEditor = isAdmin()
        ? '<div class="field span-2"><span>适用租户（可多选）</span>' +
          tenantChoiceOptions(tenants, t.tenant_id, 'edit') +
          '<div class="form-tip">PUBLIC = 全部租户；第一个适用租户为标签归属方</div></div>'
        : '<div class="field span-2"><span>适用租户 / 归属</span><div class="readonly-box">' +
          esc(t.tenant_id) + '</div><div class="form-tip">标签归属第一个租户；租户管理员不能修改适用租户列表</div></div>';
      function parseExtOptions(json) {
        if (!json) return [];
        try {
          var v = JSON.parse(json);
          if (Array.isArray(v)) return v;
          if (v && Array.isArray(v.enum)) return v.enum;
        } catch (e) { /* 容忍逗号分隔 */ }
        return String(json).replace(/^\[|\]$/g, '').split(',').map(function (s) {
          return s.trim().replace(/^"|"$/g, '');
        }).filter(Boolean);
      }

      function buildExtForm(attrs) {
        if (!attrs.length) {
          return '<div class="notice notice-info">当前租户未配置扩展属性。可在「标签管理 → 扩展属性」中新增后再编辑。</div>';
        }
        var html = '<div class="form-grid">';
        attrs.forEach(function (a) {
          if (a.visible === false) return;
          var id = 'edit-ext-' + a.attr_key;
          var v = a.value == null ? '' : String(a.value);
          var ctrl;
          if (a.value_type === 'enum') {
            ctrl = '<select id="' + id + '"><option value="">（默认：' + esc(a.default_value || '--') + '）</option>' +
              parseExtOptions(a.options_json).map(function (o) { return editOption(o, o, v); }).join('') + '</select>';
          } else if (a.value_type === 'bool') {
            ctrl = '<select id="' + id + '"><option value="">（默认：' + esc(a.default_value || 'false') + '）</option>' +
              editOption('true', '是', v) + editOption('false', '否', v) + '</select>';
          } else if (a.value_type === 'text') {
            ctrl = '<textarea id="' + id + '" rows="7" placeholder="面向客服的提问话术与取值口径">' + esc(v) + '</textarea>';
          } else if (a.value_type === 'int') {
            ctrl = '<input id="' + id + '" type="number" value="' + esc(v) + '">';
          } else {
            ctrl = '<input id="' + id + '" value="' + esc(v) + '">';
          }
          html += '<div class="field ' + (a.value_type === 'text' ? 'span-2' : '') + '"><span>' + esc(a.attr_name) +
            (a.inherited ? ' <em style="font-style:normal;font-weight:400;color:#8a97a6">（默认值）</em>' : '') +
            '</span>' + ctrl + '</div>';
        });
        return html + '</div>';
      }

      var extAttrs = (extView && extView.attrs) || [];
      var extForm = buildExtForm(extAttrs);
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
        '  <div class="field"><span>一级维度</span><select id="edit-dim1">' + dim1Options + '</select></div>' +
        '  <div class="field"><span>二级维度</span><select id="edit-category">' + categoryOptions + '</select></div>' +
        '  <div class="field"><span>标签优先级</span><input id="edit-priority" type="number" min="0" value="' +
        (t.priority == null ? '' : esc(t.priority)) + '">' +
        '     <div class="form-tip">人工打标顺序，数值越小越靠前；留空表示非人工打标标签</div></div>' +
        '  <div class="field"><span>适用对象（逗号分隔多值；留空=不限）</span><input id="edit-object" value="' +
        esc(t.object_type || '') + '" placeholder="driver / driver,vehicle"></div>' +
        '  <div class="field"><span>值类型 <i class="req">*</i></span><select id="edit-vtype">' +
        editOption('boolean', 'boolean', t.value_type) + editOption('number', 'number', t.value_type) +
        editOption('enum', 'enum', t.value_type) + editOption('string', 'string', t.value_type) +
        editOption('date', 'date', t.value_type) + '</select></div>' +
        '  <div class="field"><span>单位</span><input id="edit-unit" value="' + esc(t.unit || '') +
        '" placeholder="如 小时 / 天 / 次 / 公里">' +
        '     <div class="form-tip">数值型统计指标的计量单位；文本、布尔、枚举类可留空</div></div>' +
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
        '</div></section>' +
        '<section class="form-section">' +
        '<div class="form-section-head"><span class="form-section-index">02</span><div><h4>租户扩展信息</h4>' +
        '<p>按租户生效（当前：' + esc(extTenant) + '）。留空表示使用属性默认值，属性可在标签管理中增减。</p></div></div>' +
        extForm +
        '</section></div>' +
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
            var prioRaw = $('#edit-priority').value.trim();
            var priorityVal = prioRaw === '' ? -1 : parseInt(prioRaw, 10);
            if (!isNaN(priorityVal) && priorityVal < 0 && prioRaw !== '') {
              toast('标签优先级必须为不小于 0 的整数', 'err'); return;
            }
            if (prioRaw !== '' && isNaN(priorityVal)) {
              toast('标签优先级必须为整数', 'err'); return;
            }
            var payload = {
              tag_name: name,
              category_code: $('#edit-category').value,
              dim1_code: $('#edit-dim1').value,
              dim2_code: $('#edit-category').value,
              priority: priorityVal,
              tenant_id: tenantIds,
              value_type: $('#edit-vtype').value,
              unit: $('#edit-unit').value.trim(),
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
            var extValues = {};
            extAttrs.forEach(function (a) {
              var el = document.getElementById('edit-ext-' + a.attr_key);
              if (el) extValues[a.attr_key] = el.value;
            });
            setBusy(btn, true);
            api('/tags/' + encodeURIComponent(t.tag_code), { method: 'PUT', body: payload })
              .then(function () {
                if (!Object.keys(extValues).length) return null;
                return api('/tags/' + encodeURIComponent(t.tag_code) + '/ext' + qs({ tenantId: extTenant }),
                  { method: 'PUT', body: { values: extValues } });
              })
              .then(function () {
                closeModal();
                toast('标签定义与扩展信息已更新', 'ok');
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
    ['tag_name', '标签名称'], ['category_code', '类目'], ['value_type', '值类型'], ['unit', '单位'],
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
        '    <div class="field"><span>单位</span><input id="reg-unit" placeholder="如 小时 / 天 / 次 / 公里">' +
        '      <div class="form-tip">数值型统计指标填写，其他标签可留空</div></div>' +
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
                unit: $('#reg-unit').value.trim(),
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
