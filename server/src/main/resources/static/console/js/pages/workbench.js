/** 控制台页面模块 · workbench */
import * as core from '../core.js';
import { openTaskDetail } from './tasks.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fillTenantSelect, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 10：打标工作台
   * ============================================================ */
  var wbState = {
    objectType: 'driver', objectId: '', item: null, task: null, taskTags: [],
    profile: null, tags: [], items: [], formTags: [], values: {}, pending: false, submitBizId: null
  };

  function hashQuery() {
    var hash = location.hash || '';
    var idx = hash.indexOf('?');
    var out = {};
    if (idx < 0) return out;
    hash.slice(idx + 1).split('&').forEach(function (pair) {
      if (!pair) return;
      var kv = pair.split('=');
      out[decodeURIComponent(kv[0])] = decodeURIComponent(kv.slice(1).join('=') || '');
    });
    return out;
  }

  function tenantFieldHtml(id) {
    if (!isAdmin()) {
      return '<span class="wb-tenant-chip">租户 · ' + esc(workTenantId() || '--') + '</span>';
    }
    return '<div class="field"><span>租户</span><select id="' + id + '"></select></div>';
  }

  function renderWorkbench() {
    var q = hashQuery();
    wbState.pending = false;
    $('#content').innerHTML =
      '<div class="card wb-toolbar">' +
      '  <div class="filter-bar wb-filter">' +
      tenantFieldHtml('wb-tenant') +
      '    <div class="field"><span>对象类型</span><select id="wb-otype"><option value="driver">driver 司机</option><option value="vehicle">vehicle 车辆</option></select></div>' +
      '    <div class="field grow"><span>对象 ID</span><input id="wb-oid" placeholder="DRV85001 / 苏A88801"></div>' +
      '    <button class="btn btn-primary" id="wb-search">查画像</button>' +
      '  </div>' +
      '</div>' +
      '<div class="wb-grid">' +
      '  <aside class="card wb-side">' +
      '    <div class="card-head"><div class="card-title">我的待办 <span class="count-chip" id="wb-todo-count">--</span></div>' +
      '      <div class="card-actions"><button class="btn btn-ghost btn-sm" id="wb-queue-refresh">刷新</button></div></div>' +
      '    <div id="wb-queue" class="wb-queue"></div>' +
      '    <div class="card-head wb-subhead"><div class="card-title">最近对象</div></div>' +
      '    <div id="wb-recent" class="wb-recent"></div>' +
      '  </aside>' +
      '  <section class="card wb-center">' +
      '    <div class="card-head"><div class="card-title" id="wb-object-title">对象画像</div>' +
      '      <div class="card-actions" id="wb-object-meta"></div></div>' +
      '    <div id="wb-profile">' + emptyState('选择一个对象', '搜索对象 ID，或从左侧待办 / 最近对象进入') + '</div>' +
      '  </section>' +
      '  <aside class="card wb-form">' +
      '    <div class="card-head"><div class="card-title" id="wb-form-title">打标表单</div>' +
      '      <div class="card-actions" id="wb-form-actions"></div></div>' +
      '    <div id="wb-form-body">' + emptyState('尚未选择对象', '画像加载后可开始打标') + '</div>' +
      '  </aside>' +
      '</div>';

    if (isAdmin()) {
      fillTenantSelect('wb-tenant');
      $('#wb-tenant').addEventListener('change', function () {
        setWorkTenant(this.value);
        wbState.item = null; wbState.task = null; wbState.objectId = '';
        loadWbQueue(); loadWbRecent();
        $('#wb-profile').innerHTML = emptyState('已切换租户', '请重新搜索对象');
        $('#wb-form-body').innerHTML = emptyState('尚未选择对象', '画像加载后可开始打标');
        $('#wb-object-title').textContent = '对象画像';
        $('#wb-object-meta').innerHTML = '';
      });
    }
    $('#wb-search').addEventListener('click', function () {
      var oid = $('#wb-oid').value.trim();
      if (!oid) { toast('请输入对象 ID', 'err'); return; }
      wbState.objectType = $('#wb-otype').value;
      wbState.objectId = oid;
      wbState.item = null; wbState.task = null; wbState.taskTags = [];
      wbState.formTags = []; wbState.values = {};
      loadWbTags().then(function () { loadWbProfile(); renderWbForm(); });
    });
    $('#wb-oid').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('#wb-search').click(); });
    $('#wb-queue-refresh').addEventListener('click', function () { loadWbQueue(); loadWbRecent(); });

    api('/workbench/object-types').then(function (types) {
      var sel = $('#wb-otype');
      if (!sel || !types || !types.length) return;
      sel.innerHTML = types.map(function (t) {
        return '<option value="' + esc(t.object_type) + '">' + esc(t.object_type) + ' ' + esc(t.type_name || '') + '</option>';
      }).join('');
      if (!types.some(function (t) { return t.object_type === wbState.objectType; })) {
        wbState.objectType = types[0].object_type;
      }
      sel.value = wbState.objectType;
      $('#wb-oid').placeholder = wbState.objectType === 'vehicle' ? '苏A88801' : 'DRV85001';
      sel.addEventListener('change', function () {
        wbState.objectType = this.value;
        $('#wb-oid').placeholder = this.value === 'vehicle' ? '苏A88801' : 'DRV85001';
        loadWbTags();
        loadWbRecent();
      });
    }).catch(function () {});

    loadWbQueue();
    loadWbRecent();
    loadWbTags();
    if (q.item) {
      loadWbItem(Number(q.item));
    } else {
      $('#wb-otype').value = wbState.objectType;
    }
  }

  function loadWbTags() {
    return api('/workbench/tags?objectType=' + encodeURIComponent(wbState.objectType))
      .then(function (rows) {
        wbState.tags = rows || [];
        if (wbState.formTags.length) renderWbForm();
        return wbState.tags;
      }).catch(function (e) { toast('标签加载失败：' + e.message, 'err'); return []; });
  }

  function wbMeta(code) {
    for (var i = 0; i < wbState.tags.length; i++) {
      if (wbState.tags[i].tag_code === code) return wbState.tags[i];
    }
    return null;
  }

  function loadWbQueue() {
    return api('/workbench/items?limit=40').then(function (rows) {
      wbState.items = rows || [];
      renderWbQueue();
    }).catch(function (e) {
      if ($('#wb-queue')) $('#wb-queue').innerHTML = emptyState('加载失败', e.message);
    });
  }

  function renderWbQueue() {
    var host = $('#wb-queue');
    if (!host) return;
    if ($('#wb-todo-count')) $('#wb-todo-count').textContent = wbState.items.length;
    if (!wbState.items.length) {
      host.innerHTML = emptyState('暂无待办', '任务发布并派发后会出现在这里');
      return;
    }
    host.innerHTML = wbState.items.map(function (x) {
      var item = x.item || {};
      var task = x.task || {};
      var active = wbState.item && wbState.item.id === item.id ? ' active' : '';
      return '<button type="button" class="wb-queue-item' + active + '" data-wb-item="' + item.id + '">' +
        '<span class="wb-queue-dot ' + esc(item.status) + '"></span>' +
        '<span class="wb-queue-main"><b class="mono">' + esc(item.object_id) + '</b>' +
        '<small>' + esc(task.title || task.task_code || '') + '</small></span>' +
        badge(item.status) + '</button>';
    }).join('');
    host.querySelectorAll('[data-wb-item]').forEach(function (btn) {
      btn.addEventListener('click', function () { loadWbItem(Number(btn.getAttribute('data-wb-item'))); });
    });
  }

  function loadWbRecent() {
    api('/workbench/objects?objectType=' + encodeURIComponent(wbState.objectType) + '&limit=20')
      .then(function (rows) {
        var host = $('#wb-recent');
        if (!host) return;
        rows = rows || [];
        if (!rows.length) { host.innerHTML = '<div class="wb-empty-line">暂无对象记录</div>'; return; }
        host.innerHTML = rows.map(function (r) {
          return '<button type="button" class="wb-recent-item" data-wb-oid="' + esc(r.object_id) + '">' +
            '<span class="mono">' + esc(r.object_id) + '</span><small>' + fmtDt(r.last_seen) + '</small></button>';
        }).join('');
        host.querySelectorAll('[data-wb-oid]').forEach(function (btn) {
          btn.addEventListener('click', function () {
            wbState.objectId = btn.getAttribute('data-wb-oid');
            wbState.item = null; wbState.task = null; wbState.taskTags = [];
            wbState.formTags = []; wbState.values = {};
            $('#wb-oid').value = wbState.objectId;
            loadWbTags().then(function () { loadWbProfile(); renderWbForm(); });
          });
        });
      }).catch(function () {});
  }

  function loadWbItem(itemId, keepValues) {
    api('/workbench/items/' + itemId).then(function (ctx) {
      if (!ctx || !ctx.item) return;
      var old = wbState.values;
      wbState.item = ctx.item;
      wbState.task = ctx.task;
      wbState.taskTags = ctx.tags || [];
      wbState.objectType = ctx.item.object_type;
      wbState.objectId = ctx.item.object_id;
      if (!keepValues) {
        wbState.values = {};
        wbState.formTags = [];
        (ctx.tags || []).forEach(function (t) { wbState.formTags.push(t.tag_code); });
        (ctx.values || []).forEach(function (v) {
          wbState.values[v.tag_code] = { value: v.tag_value, remark: v.remark || '', evidence: v.evidence_url || '' };
        });
      } else {
        Object.keys(old).forEach(function (k) { wbState.values[k] = old[k]; });
      }
      var otype = $('#wb-otype'); var oid = $('#wb-oid');
      if (otype) otype.value = wbState.objectType;
      if (oid) oid.value = wbState.objectId;
      loadWbTags().then(function () { loadWbProfile(); renderWbForm(); });
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function loadWbProfile() {
    if (!wbState.objectId) return;
    api('/workbench/objects/profile?objectType=' + encodeURIComponent(wbState.objectType) +
      '&objectId=' + encodeURIComponent(wbState.objectId)).then(function (p) {
      wbState.profile = p;
      renderWbProfile();
      renderWbForm();
    }).catch(function (e) {
      wbState.profile = null;
      $('#wb-profile').innerHTML = emptyState('画像加载失败', e.message);
      $('#wb-form-body').innerHTML = emptyState('无法打标', '请先修正对象 ID 或对象类型');
    });
  }

  function wbLatestValue(code) {
    var p = wbState.profile;
    if (!p || !p.latest) return null;
    var tags = p.latest.tags || [];
    for (var i = 0; i < tags.length; i++) {
      if (tags[i].tag_code === code && tags[i].status !== 'none') return tags[i];
    }
    return null;
  }

  function wbPendingValue(code) {
    var p = wbState.profile;
    if (!p) return null;
    var rows = p.pending || [];
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].tag_code === code) return rows[i];
    }
    return null;
  }

  function renderWbProfile() {
    var host = $('#wb-profile');
    var p = wbState.profile;
    if (!host) return;
    if (!p) {
      host.innerHTML = emptyState('尚未查询', '输入对象 ID 查看画像');
      return;
    }
    var latest = p.latest || { tags: [] };
    var pending = p.pending || [];
    var syncing = p.syncing || [];
    var tags = latest.tags || [];
    $('#wb-object-title').textContent = wbState.objectId;
    $('#wb-object-meta').innerHTML = '<span class="wb-tenant-chip">' + esc(wbState.objectType) + ' · 租户 ' + esc(workTenantId()) + '</span>';

    var latestCodes = {};
    var cards = tags.map(function (t) {
      latestCodes[t.tag_code] = true;
      var value = t.tag_value;
      var valueHtml = (value === null || value === undefined || value === '')
        ? '<span class="wb-tag-none">未采集</span>'
        : (t.value_multiple
          ? String(value).split(',').map(function (v) { return '<span class="tag-value-chip">' + esc(v.trim()) + '</span>'; }).join('')
          : esc(value));
      return '<button type="button" class="wb-tag-card' + (t.status === 'pending_audit' ? ' pending' : '') + '" data-wb-tag="' + esc(t.tag_code) + '">' +
        '<span class="wb-tag-head"><b>' + esc(t.tag_name || t.tag_code) + '</b>' + badge(t.status) + '</span>' +
        '<span class="wb-tag-code mono">' + esc(t.tag_code) + (t.value_multiple ? ' · 多选' : '') + '</span>' +
        '<span class="wb-tag-value">' + valueHtml + '</span>' +
        '<span class="wb-tag-sub">' + dash(t.operator_name) + ' · ' + fmtDt(t.operate_time) + '</span>' +
        '</button>';
    }).join('');
    var overlayCards = '';
    pending.forEach(function (v) {
      if (latestCodes[v.tag_code]) return;
      latestCodes[v.tag_code] = true;
      overlayCards += '<button type="button" class="wb-tag-card pending" data-wb-tag="' + esc(v.tag_code) + '">' +
        '<span class="wb-tag-head"><b>' + esc(v.tag_name || v.tag_code) + '</b>' + badge('submitted') + '</span>' +
        '<span class="wb-tag-code mono">' + esc(v.tag_code) + '</span>' +
        '<span class="wb-tag-value">' + esc(v.tag_value) + '</span>' +
        '<span class="wb-tag-sub">' + esc(v.operator_name || '') + ' · ' + fmtDt(v.submitted_at) + '</span></button>';
    });
    syncing.forEach(function (v) {
      if (latestCodes[v.tag_code]) return;
      latestCodes[v.tag_code] = true;
      var meta = wbMeta(v.tag_code);
      var multi = meta && meta.value_multiple;
      var valueHtml = multi
        ? String(v.tag_value || '').split(',').map(function (x) { return '<span class="tag-value-chip">' + esc(x.trim()) + '</span>'; }).join('')
        : esc(v.tag_value);
      overlayCards += '<button type="button" class="wb-tag-card syncing" data-wb-tag="' + esc(v.tag_code) + '">' +
        '<span class="wb-tag-head"><b>' + esc(v.tag_name || v.tag_code) + '</b>' + badge('syncing') + '</span>' +
        '<span class="wb-tag-code mono">' + esc(v.tag_code) + (multi ? ' · 多选' : '') + '</span>' +
        '<span class="wb-tag-value">' + valueHtml + '</span>' +
        '<span class="wb-tag-sub">已生效，等待外部程序同步 latest</span></button>';
    });
    cards += overlayCards;

    var pendingHtml = pending.length ? pending.map(function (v) {
      return '<div class="wb-pending-row">' +
        '<div><div class="cell-strong">' + esc(v.tag_name || v.tag_code) + '</div>' +
        '<div class="cell-sub mono">' + esc(v.tag_code) + ' · ' + esc(v.task_code) + '</div></div>' +
        '<div class="wb-pending-value">' + esc(v.tag_value) + '</div>' +
        '<div class="wb-pending-meta">' + dash(v.operator_name) + '<br>' + fmtDt(v.submitted_at) + '</div>' +
        (canReview() ? '<button class="btn btn-ghost btn-sm" data-wb-review="' + v.task_id + '">审核</button>' : badge('submitted')) +
        '</div>';
    }).join('') : '<div class="wb-empty-line">暂无待审核提议值</div>';

    var syncingHtml = syncing.length ? syncing.map(function (v) {
      return '<div class="wb-pending-row syncing">' +
        '<div><div class="cell-strong">' + esc(v.tag_name || v.tag_code) + '</div>' +
        '<div class="cell-sub mono">' + esc(v.tag_code) + '</div></div>' +
        '<div class="wb-pending-value">' + esc(v.tag_value) + '</div>' +
        '<div class="wb-pending-meta">已生效 · 待同步<br>' + fmtDt(v.op_time) + '</div>' +
        '<span class="badge badge-blue">待同步</span></div>';
    }).join('') : '<div class="wb-empty-line">暂无待同步值</div>';

    host.innerHTML =
      '<div class="wb-profile-summary">' +
      '  <div class="wb-profile-avatar">' + esc((wbState.objectType || '?').charAt(0).toUpperCase()) + '</div>' +
      '  <div><div class="wb-profile-id">' + esc(wbState.objectId) + '</div>' +
      '  <div class="wb-profile-meta">' + esc(wbState.objectType) + ' · 已同步 ' + tags.length + ' 个标签 · 待审核 ' + pending.length + ' · 待同步 ' + syncing.length + '</div></div>' +
      '</div>' +
      '<div class="wb-section-title">当前画像 <span>点击标签加入打标表单</span></div>' +
      '<div class="wb-tag-grid">' + (cards || emptyState('暂无标签值', '可直接在右侧以临时打标方式采集')) + '</div>' +
      '<div class="wb-section-title">待审核提议值 <span>审核通过后写入 history</span></div>' +
      '<div class="wb-pending-list">' + pendingHtml + '</div>' +
      '<div class="wb-section-title">已生效 · 待同步 <span>外部写入程序维护 latest</span></div>' +
      '<div class="wb-pending-list">' + syncingHtml + '</div>';

    host.querySelectorAll('[data-wb-tag]').forEach(function (btn) {
      btn.addEventListener('click', function () { wbAddTag(btn.getAttribute('data-wb-tag')); });
    });
    host.querySelectorAll('[data-wb-review]').forEach(function (btn) {
      btn.addEventListener('click', function () { openTaskDetail(Number(btn.getAttribute('data-wb-review'))); });
    });
  }

  function wbCurrentForTag(code) {
    var latest = wbLatestValue(code);
    if (latest && latest.tag_value !== null && latest.tag_value !== undefined) {
      return { value: latest.tag_value, remark: latest.remark || '', evidence: latest.evidence_url || '' };
    }
    var pending = wbPendingValue(code);
    if (pending) return { value: pending.tag_value, remark: pending.remark || '', evidence: '' };
    var p = wbState.profile;
    if (p) {
      var syncing = p.syncing || [];
      for (var i = 0; i < syncing.length; i++) {
        if (syncing[i].tag_code === code) return { value: syncing[i].tag_value, remark: '', evidence: '' };
      }
    }
    return null;
  }

  function wbAddTag(code) {
    if (wbState.formTags.indexOf(code) >= 0) {
      var el = document.querySelector('[data-wb-tag-form="' + code + '"]');
      if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    if (!canTag()) { toast('当前角色无打标权限', 'err'); return; }
    wbState.formTags.push(code);
    if (!wbState.values[code]) wbState.values[code] = wbCurrentForTag(code) || { value: '', remark: '', evidence: '' };
    renderWbForm();
    setTimeout(function () {
      var el = document.querySelector('[data-wb-tag-form="' + code + '"]');
      if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 30);
  }

  function wbFormEditable() {
    if (!wbState.objectId || !canTag()) return false;
    if (!wbState.item) return true;
    if (['submitted', 'approved', 'skipped'].indexOf(wbState.item.status) >= 0) return false;
    if (!wbState.task) return false;
    return ['published', 'in_progress', 'reviewing'].indexOf(wbState.task.status) >= 0;
  }

  function wbEnumOptions(meta) {
    var range = parseJsonValue(meta.value_range, {});
    return Array.isArray(range.enum) ? range.enum : [];
  }

  function wbControlHtml(meta, current) {
    var code = meta.tag_code;
    var val = current && current.value !== null && current.value !== undefined ? String(current.value) : '';
    var disabled = wbFormEditable() ? '' : ' disabled';
    if (meta.value_type === 'boolean') {
      return '<select class="wb-input" data-wb-input="' + esc(code) + '"' + disabled + '>' +
        '<option value="">请选择</option>' +
        '<option value="true"' + (val === 'true' ? ' selected' : '') + '>是</option>' +
        '<option value="false"' + (val === 'false' ? ' selected' : '') + '>否</option></select>';
    }
    if (meta.value_type === 'number') {
      var range = parseJsonValue(meta.value_range, {});
      var attrs = ' type="number" step="any"';
      if (range.min !== undefined && range.min !== null) attrs += ' min="' + esc(range.min) + '"';
      if (range.max !== undefined && range.max !== null) attrs += ' max="' + esc(range.max) + '"';
      return '<input class="wb-input"' + attrs + ' data-wb-input="' + esc(code) + '" value="' + esc(val) + '"' + disabled + '>';
    }
    if (meta.value_type === 'enum') {
      var options = wbEnumOptions(meta);
      if (meta.value_multiple) {
        var selected = val.split(',').map(function (v) { return v.trim(); });
        return '<div class="wb-checks">' + options.map(function (opt) {
          return '<label class="wb-check"><input type="checkbox" data-wb-check="' + esc(code) + '" value="' +
            esc(opt) + '"' + (selected.indexOf(opt) >= 0 ? ' checked' : '') + disabled + '><span>' + esc(opt) + '</span></label>';
        }).join('') + '</div>';
      }
      return '<select class="wb-input" data-wb-input="' + esc(code) + '"' + disabled + '><option value="">请选择</option>' +
        options.map(function (opt) {
          return '<option value="' + esc(opt) + '"' + (val === opt ? ' selected' : '') + '>' + esc(opt) + '</option>';
        }).join('') + '</select>';
    }
    if (meta.value_type === 'date') {
      return '<input class="wb-input" type="date" data-wb-input="' + esc(code) + '" value="' + esc(val.slice(0, 10)) + '"' + disabled + '>';
    }
    return '<textarea class="wb-input" rows="2" data-wb-input="' + esc(code) + '"' + disabled + '>' + esc(val) + '</textarea>';
  }

  function renderWbForm() {
    var host = $('#wb-form-body');
    var title = $('#wb-form-title');
    var actions = $('#wb-form-actions');
    if (!host) return;
    if (!wbState.objectId) {
      title.textContent = '打标表单';
      actions.innerHTML = '';
      host.innerHTML = emptyState('尚未选择对象', '画像加载后可开始打标');
      return;
    }
    title.textContent = wbState.item ? '任务打标 · ' + (wbState.item.object_id || '') : '临时打标';
    if (wbState.item) {
      actions.innerHTML = badge(wbState.item.status);
    } else {
      actions.innerHTML = '<span class="wb-tenant-chip">无任务 · 临时打标</span>';
    }

    var editable = wbFormEditable();
    var metaHtml = '';
    var addPicker = '';
    if (!wbState.item && editable) {
      var available = wbState.tags.filter(function (t) { return wbState.formTags.indexOf(t.tag_code) < 0; });
      addPicker = '<div class="wb-add-tag">' +
        '<select class="wb-input" id="wb-add-tag-select"><option value="">选择要打的标签…</option>' +
        available.map(function (t) {
          return '<option value="' + esc(t.tag_code) + '">' + esc(t.tag_name) + ' · ' + esc(t.tag_code) +
            (t.need_value_audit ? '（需审核）' : '') + '</option>';
        }).join('') + '</select>' +
        '<button type="button" class="btn btn-ghost btn-sm" id="wb-add-tag-btn">添加</button></div>';
    }

    if (wbState.item) {
      var task = wbState.task || {};
      var hasSensitive = wbState.formTags.some(function (code) {
        var meta = wbMeta(code);
        return meta && meta.need_value_audit;
      });
      var reviewHint = task.review_required ? '任务需审核，提交后等待审核'
        : hasSensitive ? '含敏感标签，提交后需审核' : '普通标签提交即生效';
      if (wbState.taskTags.some(function (t) { return t.required; })) reviewHint += ' · 必填 ' + wbState.taskTags.filter(function (t) { return t.required; }).length + ' 个';
      metaHtml = '<div class="wb-task-banner">' +
        '<div><b>' + esc(task.title || task.task_code || '') + '</b>' +
        '<span class="mono">' + esc(task.task_code || '') + '</span></div>' +
        '<div class="wb-task-banner-sub">' + esc(reviewHint) + '</div>' +
        '</div>';
    }

    var forms = wbState.formTags.map(function (code) {
      var meta = wbMeta(code) || { tag_code: code, tag_name: code, value_type: 'string' };
      var current = wbState.values[code] || { value: '', remark: '', evidence: '' };
      var latest = wbLatestValue(code);
      var latestHint = latest
        ? '<span class="wb-latest-hint">当前：' + esc(latest.tag_value) + ' · ' + badge(latest.status) + '</span>'
        : '<span class="wb-latest-hint">当前未采集</span>';
      var required = wbState.taskTags.some(function (t) { return t.tag_code === code && t.required; });
      return '<div class="wb-tag-form" data-wb-tag-form="' + esc(code) + '">' +
        '<div class="wb-tag-form-head"><div><b>' + esc(meta.tag_name || code) + '</b>' +
        (meta.need_value_audit ? '<span class="badge badge-orange">敏感</span>' : '') +
        (required ? '<span class="badge badge-blue">必填</span>' : '') +
        '<div class="mono wb-tag-form-code">' + esc(code) + ' · ' + esc(meta.value_type || '') +
        (meta.value_multiple ? ' · 多选' : '') + '</div></div>' +
        (wbState.item ? '' : '<button type="button" class="btn btn-link btn-sm" data-wb-remove="' + esc(code) + '">移除</button>') +
        '</div>' + latestHint +
        '<div class="wb-control">' + wbControlHtml(meta, current) + '</div>' +
        '<div class="wb-form-line"><input class="wb-input" data-wb-remark="' + esc(code) + '" placeholder="' +
        (meta.need_value_audit ? '打标理由（敏感标签必填）' : '备注 / 打标理由') + '" value="' + esc(current.remark || '') + '"' + (editable ? '' : ' disabled') + '>' +
        '<input class="wb-input" data-wb-evidence="' + esc(code) + '" placeholder="证据链接 http(s)://（可选）" value="' + esc(current.evidence || '') + '"' + (editable ? '' : ' disabled') + '></div>' +
        '</div>';
    }).join('');

    var footer = '';
    if (editable) {
      footer = '<div class="wb-submit-bar">' +
        '<button type="button" class="btn btn-primary" id="wb-submit"' + (wbState.formTags.length ? '' : ' disabled') + '>提交' + (wbState.item ? '并下一个' : '') + '</button>' +
        (wbState.item ? '<button type="button" class="btn btn-ghost btn-sm" id="wb-skip">跳过</button>' : '') +
        '</div>';
    } else {
      footer = '<div class="wb-readonly">' + (canTag()
        ? '当前状态不可编辑' + (wbState.item ? '（' + wbState.item.status + '）' : '')
        : '当前角色仅可查看，不能打标') + '</div>';
    }

    host.innerHTML = metaHtml + addPicker +
      (forms || '<div class="wb-empty-line">从画像点击标签，或使用上方下拉添加</div>') + footer;

    if ($('#wb-add-tag-btn')) {
      $('#wb-add-tag-btn').addEventListener('click', function () {
        var code = $('#wb-add-tag-select').value;
        if (!code) { toast('请选择标签', 'err'); return; }
        wbAddTag(code);
      });
    }
    host.querySelectorAll('[data-wb-remove]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var code = btn.getAttribute('data-wb-remove');
        wbState.formTags = wbState.formTags.filter(function (c) { return c !== code; });
        delete wbState.values[code];
        renderWbForm();
      });
    });
    if ($('#wb-submit')) $('#wb-submit').addEventListener('click', function () { submitWb(this, true); });
    if ($('#wb-skip')) $('#wb-skip').addEventListener('click', function () { openWbSkip(); });

    host.oninput = syncWbValues;
    host.onchange = syncWbValues;
  }

  function syncWbValues() {
    wbState.formTags.forEach(function (code) {
      var meta = wbMeta(code);
      var value = '';
      if (meta && meta.value_type === 'enum' && meta.value_multiple) {
        var checked = document.querySelectorAll('[data-wb-check="' + code + '"]:checked');
        var vals = [];
        checked.forEach(function (el) { vals.push(el.value); });
        value = vals.join(',');
      } else {
        var input = document.querySelector('[data-wb-input="' + code + '"]');
        value = input ? input.value.trim() : '';
      }
      var remark = document.querySelector('[data-wb-remark="' + code + '"]');
      var evidence = document.querySelector('[data-wb-evidence="' + code + '"]');
      if (!wbState.values[code]) wbState.values[code] = {};
      wbState.values[code].value = value;
      wbState.values[code].remark = remark ? remark.value : '';
      wbState.values[code].evidence = evidence ? evidence.value : '';
    });
  }

  function wbPayload() {
    syncWbValues();
    var tags = [];
    var missing = [];
    wbState.formTags.forEach(function (code) {
      var meta = wbMeta(code) || {};
      var v = wbState.values[code] || {};
      var required = wbState.taskTags.some(function (t) { return t.tag_code === code && t.required; });
      if (!v.value) {
        if (required) missing.push(meta.tag_name || code);
        return;
      }
      tags.push({
        tag_code: code, tag_value: v.value,
        remark: v.remark || null, evidence_url: v.evidence || null
      });
    });
    if (missing.length) { toast('必填标签未填写：' + missing.join('、'), 'err'); return null; }
    if (!tags.length) { toast('请至少填写一个标签值', 'err'); return null; }
    if (!wbState.submitBizId) {
      wbState.submitBizId = 'wb-' + Date.now() + '-' + Math.floor(Math.random() * 100000);
    }
    var body = {
      biz_request_id: wbState.submitBizId,
      tags: tags
    };
    if (wbState.item) {
      body.task_item_id = wbState.item.id;
      body.object_type = wbState.item.object_type;
      body.object_id = wbState.item.object_id;
    } else {
      body.object_type = wbState.objectType;
      body.object_id = wbState.objectId;
    }
    return body;
  }

  function submitWb(btn, withNext) {
    var body = wbPayload();
    if (!body) return;
    setBusy(btn, true);
    api('/workbench/tag-values', { method: 'POST', body: body }).then(function (res) {
      wbState.submitBizId = null;
      toast(res.status === 'pending_review' ? '已提交审核，通过后写入标签值' : '提交成功，已写入流水（外部同步 ≤5s）', 'ok');
      var itemId = wbState.item ? wbState.item.id : null;
      if (itemId) {
        loadWbItem(itemId, true);
        loadWbQueue().then(function () {
          if (withNext) {
            var next = wbState.items.filter(function (x) {
              return x.item.id !== itemId && ['pending', 'claimed', 'in_progress', 'rejected'].indexOf(x.item.status) >= 0;
            })[0];
            if (next) loadWbItem(next.item.id);
          }
        });
      } else {
        wbState.formTags = [];
        wbState.values = {};
        loadWbProfile();
      }
      loadWbRecent();
    }).catch(function (e) {
      setBusy(btn, false);
      toast(e.message, 'err');
    }).finally(function () { setBusy(btn, false); });
  }

  function openWbSkip() {
    if (!wbState.item) return;
    openModal('跳过任务项 · ' + wbState.item.object_id,
      '<div class="field"><span>跳过原因 <i class="req">*</i></span><textarea id="wb-skip-reason" rows="3" placeholder="如：对象信息缺失，无法确认"></textarea></div>',
      [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '确认跳过', cls: 'btn-danger', onClick: function () {
            var reason = $('#wb-skip-reason').value.trim();
            if (!reason) { toast('请填写跳过原因', 'err'); return; }
            var btn = this;
            setBusy(btn, true);
            api('/workbench/items/' + wbState.item.id + '/skip', { method: 'POST', body: { remark: reason } })
              .then(function () {
                closeModal();
                toast('已跳过', 'ok');
                loadWbQueue();
                loadWbItem(wbState.item.id, true);
              }).catch(function (e) { toast(e.message, 'err'); })
              .finally(function () { setBusy(btn, false); });
          } }
      ]);
  }


  export { renderWorkbench };
