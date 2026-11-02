/** 控制台页面模块 · 标签组合（组合列表 / 规则编排 / 标签查看抽屉） */
import * as core from '../core.js';
import { tenantChoiceOptions, selectedTenantChoices } from '../shared.js';

const {
  $, esc, api, qs, toast, emptyState, badge, fmtDt, dash, parseJsonValue,
  canByPermission, workTenantId, openModal, closeModal
} = core;

const OBJECT_TYPES = [
  { value: 'driver', label: 'driver 司机' },
  { value: 'vehicle', label: 'vehicle 车辆' },
  { value: 'fleet', label: 'fleet 车队' }
];

const COMP_STATUS = {
  draft: { label: '草稿', cls: 'badge-gray' },
  published: { label: '已发布', cls: 'badge-green' },
  disabled: { label: '已停用', cls: 'badge-orange' }
};

const OP_LABEL = {
  eq: '等于', ne: '不等于', in: '属于', not_in: '不属于',
  gt: '大于', gte: '大于等于', lt: '小于', lte: '小于等于', between: '介于',
  contains: '包含', not_contains: '不包含', before: '早于', after: '晚于',
  is_true: '为是', is_false: '为否', is_empty: '为空', is_not_empty: '不为空'
};

const OPS_BY_TYPE = {
  boolean: ['is_true', 'is_false', 'is_empty', 'is_not_empty'],
  enum: ['eq', 'ne', 'in', 'not_in', 'is_empty', 'is_not_empty'],
  number: ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'between', 'is_empty', 'is_not_empty'],
  string: ['eq', 'ne', 'contains', 'not_contains', 'is_empty', 'is_not_empty'],
  date: ['before', 'after', 'is_empty', 'is_not_empty']
};

const DERIVED_ROW_STATUS = {
  effective: ['badge-green', '命中'],
  not_matched: ['badge-gray', '未命中'],
  none: ['badge-orange', '未知']
};

const OUTPUT_TYPES = [
  { value: 'boolean', label: 'boolean 布尔' },
  { value: 'enum', label: 'enum 枚举' },
  { value: 'number', label: 'number 数值' },
  { value: 'string', label: 'string 文本' }
];

const TAG_TYPE_LABEL = { fact: '事实', rule: '规则', algo: '算法', derived: '派生' };

var cmp = {
  tags: [],
  comps: [],
  categories: [],
  tenants: [],
  editing: null,
  view: 'list',
  filter: { keyword: '', status: '', objectType: '' },
  libraryKeyword: '',
  outputType: 'boolean',
  rule: null,
  meta: {},
  preview: null,
  dirty: false,
  drawer: null
};

function defaultRule() {
  return {
    logic: 'AND',
    groups: [{ logic: 'AND', conditions: [{ tag_code: '', op: 'eq', value: '', value_to: '' }] }],
    output: { mode: 'fixed', value: 'true', source_tag_code: '', default_value: '', entries: [] },
    missing_policy: 'hit_false'
  };
}

function defaultMeta() {
  return {
    tag_code: '',
    tag_name: '',
    category_code: '',
    object_type: 'driver',
    tenant_id: workTenantId() || 'risk_control',
    description: ''
  };
}

function markDirty() { cmp.dirty = true; }

/* ============================================================
 * 入口
 * ============================================================ */
export function renderCompose() {
  cmp.view = 'list';
  cmp.editing = null;
  cmp.filter = { keyword: '', status: '', objectType: '' };
  cmp.libraryKeyword = '';
  cmp.preview = null;
  cmp.dirty = false;
  cmp.outputType = 'boolean';
  cmp.rule = defaultRule();
  cmp.meta = defaultMeta();
  $('#content').innerHTML = '<div id="cmp-main"></div>';
  loadAll();
}

function loadAll() {
  Promise.all([
    api('/tags?status=online'),
    api('/tag-compositions'),
    api('/tag-categories'),
    api('/tenants').catch(function () { return []; })
  ]).then(function (res) {
    cmp.tags = res[0] || [];
    cmp.comps = res[1] || [];
    cmp.categories = res[2] || [];
    cmp.tenants = res[3] || [];
    renderMain();
    var deep = /[?&]tag=([^&]+)/.exec(location.hash || '');
    if (deep) {
      var code = decodeURIComponent(deep[1]);
      if (cmp.comps.some(function (c) { return c.tag_code === code; })) loadComposition(code);
    }
  }).catch(function (e) {
    $('#cmp-main').innerHTML = '<div class="card">' + emptyState('加载失败', e.message) + '</div>';
  });
}

function reloadComps() {
  return api('/tag-compositions').then(function (rows) {
    cmp.comps = rows || [];
    if (cmp.view === 'list') renderListRows();
  });
}

function renderMain() {
  initDrawer();
  if (cmp.view === 'list') renderListView(); else renderEditorView();
}

/* ============================================================
 * 组合列表
 * ============================================================ */
function renderListView() {
  var statusOptions = [
    { v: '', t: '全部状态' }, { v: 'published', t: '已发布' },
    { v: 'draft', t: '草稿' }, { v: 'disabled', t: '已停用' }
  ].map(function (o) {
    return '<option value="' + o.v + '"' + (cmp.filter.status === o.v ? ' selected' : '') + '>' + o.t + '</option>';
  }).join('');
  var objectOptions = '<option value="">全部对象</option>' + OBJECT_TYPES.map(function (o) {
    return '<option value="' + o.value + '"' + (cmp.filter.objectType === o.value ? ' selected' : '') + '>' + o.label + '</option>';
  }).join('');

  $('#cmp-main').innerHTML =
    '<div class="card cmp-list-card">' +
    '  <div class="card-head cmp-list-head">' +
    '    <div class="cmp-list-title">' +
    '      <div class="card-title">标签组合 <span class="count-chip" id="cmp-count">' + cmp.comps.length + '</span></div>' +
    '    </div>' +
    '    <div class="cmp-list-tools">' +
    '      <input id="cmp-list-search" placeholder="搜索名称 / code" value="' + esc(cmp.filter.keyword) + '">' +
    '      <select id="cmp-list-status">' + statusOptions + '</select>' +
    '      <select id="cmp-list-object">' + objectOptions + '</select>' +
    '      <button class="btn btn-primary btn-sm" id="cmp-new">＋ 新建组合</button>' +
    '    </div>' +
    '  </div>' +
    '  <div id="cmp-list-body"></div>' +
    '</div>';

  $('#cmp-new').addEventListener('click', newComposition);
  $('#cmp-list-search').addEventListener('input', function () {
    cmp.filter.keyword = this.value.trim();
    renderListRows();
  });
  $('#cmp-list-status').addEventListener('change', function () {
    cmp.filter.status = this.value;
    renderListRows();
  });
  $('#cmp-list-object').addEventListener('change', function () {
    cmp.filter.objectType = this.value;
    renderListRows();
  });
  renderListRows();
}

function ruleSummary(rule) {
  if (!rule || !rule.groups || !rule.groups.length) return '<span class="muted">未配置规则</span>';
  var conditions = 0;
  rule.groups.forEach(function (g) { conditions += (g.conditions || []).length; });
  var out = rule.output && rule.output.mode === 'mapping'
    ? '映射输出'
    : ('固定值 ' + (rule.output && rule.output.value != null ? rule.output.value : '--'));
  return '<span class="cmp-rule-chip">' + rule.groups.length + ' 组 · ' + conditions + ' 条件 · ' + rule.logic + '</span>' +
    '<div class="cell-sub">' + esc(out) + '</div>';
}

function renderListRows() {
  var host = $('#cmp-list-body');
  if (!host) return;
  var kw = cmp.filter.keyword.toLowerCase();
  var rows = cmp.comps.filter(function (c) {
    if (cmp.filter.status && c.status !== cmp.filter.status) return false;
    if (cmp.filter.objectType && c.object_type !== cmp.filter.objectType) return false;
    if (kw && (c.tag_code + ' ' + c.tag_name).toLowerCase().indexOf(kw) < 0) return false;
    return true;
  });
  if (!rows.length) {
    host.innerHTML = emptyState(cmp.comps.length ? '没有匹配的组合' : '还没有组合标签',
      cmp.comps.length ? '调整搜索或筛选条件后重试' : '点击「新建组合」用已有标签编排第一个派生标签');
    return;
  }
  var html = '<table class="cmp-list-table"><thead><tr>' +
    '<th>组合</th><th>对象</th><th>规则</th><th>状态</th><th>版本</th><th>更新</th><th>操作</th>' +
    '</tr></thead><tbody>';
  rows.forEach(function (c) {
    var st = COMP_STATUS[c.status] || { label: c.status, cls: 'badge-gray' };
    html += '<tr class="cmp-list-row" data-open="' + esc(c.tag_code) + '">' +
      '<td><div class="cell-strong">' + esc(c.tag_name) + '</div>' +
      '<div class="cell-sub mono">' + esc(c.tag_code) + '</div></td>' +
      '<td>' + esc(c.object_type) + '</td>' +
      '<td>' + ruleSummary(c.rule) + '</td>' +
      '<td><span class="badge ' + st.cls + '">' + st.label + '</span></td>' +
      '<td class="mono">v' + c.rule_version + '</td>' +
      '<td class="nowrap muted">' + fmtDt(c.updated_at) + '</td>' +
      '<td class="nowrap"><button class="btn btn-link btn-primary btn-sm" data-open="' +
      esc(c.tag_code) + '">编辑</button></td>' +
      '</tr>';
  });
  host.innerHTML = '<div class="table-wrap">' + html + '</tbody></table></div>';
  host.querySelectorAll('[data-open]').forEach(function (el) {
    el.addEventListener('click', function (e) {
      e.stopPropagation();
      openEditor(el.getAttribute('data-open'));
    });
  });
}

/* ============================================================
 * 编辑器
 * ============================================================ */
function newComposition() {
  cmp.editing = null;
  cmp.outputType = 'boolean';
  cmp.meta = defaultMeta();
  cmp.rule = defaultRule();
  cmp.preview = null;
  cmp.dirty = false;
  cmp.libraryKeyword = '';
  cmp.view = 'edit';
  renderEditorView();
}

function openEditor(code) {
  loadComposition(code);
}

function loadComposition(code) {
  api('/tag-compositions/' + encodeURIComponent(code)).then(function (view) {
    cmp.editing = view;
    applyView(view);
    cmp.view = 'edit';
    renderEditorView();
  }).catch(function (e) { toast(e.message, 'err'); });
}

function renderEditorView() {
  var view = cmp.editing;
  var status = view ? (COMP_STATUS[view.status] || { label: view.status, cls: 'badge-gray' }) : null;
  $('#cmp-main').innerHTML =
    '<div class="cmp-ed-head">' +
    '  <button class="btn btn-ghost btn-sm cmp-back" id="cmp-back">← 组合列表</button>' +
    '  <div class="cmp-ed-title">' +
    '    <b>' + esc(cmp.meta.tag_name || '新建组合') + '</b>' +
    (cmp.meta.tag_code ? '<span class="mono">' + esc(cmp.meta.tag_code) + '</span>' : '<span class="muted">未保存</span>') +
    (status ? '<span class="badge ' + status.cls + '">' + status.label + '</span>' : '') +
    '  </div>' +
    '  <div class="cmp-ed-actions" id="cmp-actions"></div>' +
    '</div>' +
    '<div class="cmp-grid">' +
    '  <aside class="card cmp-side">' +
    '    <div class="card-head cmp-side-head">' +
    '      <div class="card-title">标签库</div>' +
    '      <span class="muted" id="cmp-side-hint"></span>' +
    '    </div>' +
    '    <div class="cmp-side-body">' +
    '      <div class="cmp-search">' +
    '        <input id="cmp-lib-search" placeholder="搜索名称 / code">' +
    '        <button class="btn btn-ghost btn-sm" id="cmp-lib-search-btn">查询</button>' +
    '      </div>' +
    '      <div id="cmp-resource" class="cmp-resource"></div>' +
    '    </div>' +
    '  </aside>' +
    '  <section class="card cmp-editor">' +
    '    <div class="card-head">' +
    '      <div class="card-title">组合规则</div>' +
    '      <div class="card-actions"><span class="muted">点击左侧「加入」快速添加条件</span></div>' +
    '    </div>' +
    '    <div id="cmp-editor-body" class="cmp-editor-body"></div>' +
    '  </section>' +
    '  <aside class="card cmp-result">' +
    '    <div class="card-head"><div class="card-title">标签定义与预检</div></div>' +
    '    <div id="cmp-meta-body" class="cmp-meta-body"></div>' +
    '  </aside>' +
    '</div>';

  $('#cmp-back').addEventListener('click', function () { backToList(); });
  $('#cmp-lib-search').value = cmp.libraryKeyword;
  $('#cmp-lib-search').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { cmp.libraryKeyword = this.value.trim(); renderLibrary(); }
  });
  $('#cmp-lib-search-btn').addEventListener('click', function () {
    cmp.libraryKeyword = $('#cmp-lib-search').value.trim();
    renderLibrary();
  });
  renderLibrary();
  renderEditor();
  renderMeta();
}

function backToList(force) {
  if (cmp.dirty && force !== true) {
    openModal('离开编辑器', '<div class="notice notice-warn">当前有未保存的修改，离开后会丢失。</div>',
      [{ text: '继续编辑', cls: 'btn-ghost', onClick: closeModal },
       { text: '返回列表', cls: 'btn-danger', onClick: function () { closeModal(); backToList(true); } }]);
    return;
  }
  cmp.view = 'list';
  cmp.editing = null;
  cmp.preview = null;
  cmp.dirty = false;
  renderListView();
  reloadComps();
}

/* ============================================================
 * 标签库（左栏）
 * ============================================================ */
function supportsObjectType(tag, objectType) {
  if (!tag) return false;
  if (!objectType) return true;
  var binding = tag.object_type;
  if (!binding) return true;
  return String(binding).split(',').map(function (v) { return v.trim(); }).indexOf(objectType) >= 0;
}

function usableTags() {
  return cmp.tags.filter(function (t) { return supportsObjectType(t, cmp.meta.object_type); });
}

function metaOf(code) {
  for (var i = 0; i < cmp.tags.length; i++) {
    if (cmp.tags[i].tag_code === code) return cmp.tags[i];
  }
  return null;
}

function enumValues(meta) {
  if (!meta || meta.value_type !== 'enum') return [];
  var parsed = parseJsonValue(meta.value_range, null);
  return parsed && Array.isArray(parsed.enum) ? parsed.enum : [];
}

function renderLibrary() {
  var host = $('#cmp-resource');
  if (!host) return;
  var hint = $('#cmp-side-hint');
  if (hint) hint.textContent = cmp.meta.object_type + ' · ' + usableTags().length + ' 个可用';
  var kw = cmp.libraryKeyword.toLowerCase();
  var tags = usableTags().filter(function (t) {
    return !kw || (t.tag_code + ' ' + t.tag_name).toLowerCase().indexOf(kw) >= 0;
  });
  if (!tags.length) {
    host.innerHTML = emptyState('没有可用标签', '调整关键字，或先在标签字典注册');
    return;
  }
  host.innerHTML = tags.map(function (t) {
    var derived = t.tag_type === 'derived';
    return '<div class="cmp-lib-item">' +
      '<div class="cmp-lib-main">' +
      '  <b>' + esc(t.tag_name) + (derived ? ' <span class="badge badge-blue">派生</span>' : '') + '</b>' +
      '  <small class="mono">' + esc(t.tag_code) + ' · ' + esc(t.value_type) + ' · ' + esc(t.sensitivity || '') + '</small>' +
      '</div>' +
      '<div class="cmp-lib-actions">' +
      '  <button type="button" class="btn btn-ghost btn-sm" data-use="' + esc(t.tag_code) + '">加入</button>' +
      '  <button type="button" class="cmp-lib-info" data-view="' + esc(t.tag_code) + '" title="查看标签">查看</button>' +
      '</div>' +
      '</div>';
  }).join('');
  host.querySelectorAll('[data-use]').forEach(function (btn) {
    btn.addEventListener('click', function () { addConditionWith(btn.getAttribute('data-use')); });
  });
  host.querySelectorAll('[data-view]').forEach(function (btn) {
    btn.addEventListener('click', function () { openTagDrawer(btn.getAttribute('data-view')); });
  });
}

/* ============================================================
 * 规则编排（中栏）
 * ============================================================ */
function tagOptions(selected) {
  var tags = usableTags();
  var selectedMeta = metaOf(selected);
  var options = '<option value="">选择标签…</option>';
  if (selected && selectedMeta && tags.indexOf(selectedMeta) < 0) {
    options += '<option value="' + esc(selected) + '" selected>（不适用当前对象）' +
      esc(selectedMeta.tag_name) + ' · ' + esc(selected) + '</option>';
  }
  return options + tags.map(function (t) {
    return '<option value="' + esc(t.tag_code) + '"' + (t.tag_code === selected ? ' selected' : '') + '>' +
      esc(t.tag_name) + ' · ' + esc(t.tag_code) + (t.tag_type === 'derived' ? '（派生）' : '') + '</option>';
  }).join('');
}

function opOptions(valueType, selected) {
  var ops = OPS_BY_TYPE[valueType] || OPS_BY_TYPE.string;
  return ops.map(function (op) {
    return '<option value="' + op + '"' + (op === selected ? ' selected' : '') + '>' +
      (OP_LABEL[op] || op) + '</option>';
  }).join('');
}

function needsValue(op) {
  return ['is_true', 'is_false', 'is_empty', 'is_not_empty'].indexOf(op) < 0;
}

function valueControl(condition, meta) {
  var op = condition.op;
  if (!needsValue(op)) return '<span class="cmp-cond-novalue">—</span>';
  var valueType = meta ? meta.value_type : 'string';
  var value = condition.value == null ? '' : condition.value;
  var valueTo = condition.value_to == null ? '' : condition.value_to;
  if (op === 'between') {
    return '<input data-cval value="' + esc(value) + '" placeholder="下限">' +
      '<input data-cval2 value="' + esc(valueTo) + '" placeholder="上限">';
  }
  if (valueType === 'enum' && (op === 'eq' || op === 'ne')) {
    var values = enumValues(meta);
    return '<select data-cval>' + values.map(function (v) {
      return '<option value="' + esc(v) + '"' + (v === value ? ' selected' : '') + '>' + esc(v) + '</option>';
    }).join('') + '</select>';
  }
  var inputType = valueType === 'number' ? 'number' : 'text';
  var placeholder = valueType === 'enum' ? '多个值用英文逗号分隔' : (valueType === 'date' ? '2026-10-29' : '比较值');
  return '<input type="' + inputType + '" data-cval value="' + esc(value) + '" placeholder="' + placeholder + '">';
}

function renderEditor() {
  var host = $('#cmp-editor-body');
  if (!host) return;
  var groups = cmp.rule.groups || [];
  var html =
    '<div class="cmp-logic-bar">' +
    '  <span>组间关系</span>' +
    '  <select id="cmp-rule-logic">' +
    '    <option value="AND"' + (cmp.rule.logic === 'AND' ? ' selected' : '') + '>AND（全部满足）</option>' +
    '    <option value="OR"' + (cmp.rule.logic === 'OR' ? ' selected' : '') + '>OR（任一满足）</option>' +
    '  </select>' +
    '  <span class="muted">组内再按各组关系合并</span>' +
    '</div>';

  groups.forEach(function (group, gi) {
    html += '<div class="cmp-group" data-group="' + gi + '">' +
      '<div class="cmp-group-head">' +
      '  <span class="cmp-group-title">条件组 ' + (gi + 1) + '</span>' +
      '  <select data-glogic class="cmp-glogic">' +
      '    <option value="AND"' + (group.logic === 'AND' ? ' selected' : '') + '>AND</option>' +
      '    <option value="OR"' + (group.logic === 'OR' ? ' selected' : '') + '>OR</option>' +
      '  </select>' +
      '  <button type="button" class="btn btn-link btn-sm" data-remove-group="' + gi + '">删除组</button>' +
      '</div>' +
      '<div class="cmp-conds">' + group.conditions.map(function (c, ci) {
        return conditionHtml(gi, ci, c);
      }).join('') + '</div>' +
      '<button type="button" class="btn btn-link btn-sm" data-add-cond="' + gi + '">＋ 添加条件</button>' +
      '</div>';
  });
  html += '<button type="button" class="btn btn-ghost btn-sm cmp-add-group" id="cmp-add-group">＋ 添加条件组</button>';
  host.innerHTML = html;

  $('#cmp-rule-logic').addEventListener('change', function () { cmp.rule.logic = this.value; markDirty(); });
  host.querySelectorAll('[data-glogic]').forEach(function (sel, gi) {
    sel.addEventListener('change', function () { cmp.rule.groups[gi].logic = this.value; markDirty(); });
  });
  host.querySelectorAll('[data-add-cond]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      captureEditor();
      cmp.rule.groups[parseInt(btn.getAttribute('data-add-cond'), 10)].conditions
        .push({ tag_code: '', op: 'eq', value: '', value_to: '' });
      markDirty();
      renderEditor();
    });
  });
  host.querySelectorAll('[data-remove-cond]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      captureEditor();
      var gi = parseInt(btn.getAttribute('data-group-i'), 10);
      var ci = parseInt(btn.getAttribute('data-remove-cond'), 10);
      cmp.rule.groups[gi].conditions.splice(ci, 1);
      if (!cmp.rule.groups[gi].conditions.length) {
        cmp.rule.groups[gi].conditions.push({ tag_code: '', op: 'eq', value: '', value_to: '' });
      }
      markDirty();
      renderEditor();
    });
  });
  host.querySelectorAll('[data-remove-group]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      captureEditor();
      cmp.rule.groups.splice(parseInt(btn.getAttribute('data-remove-group'), 10), 1);
      if (!cmp.rule.groups.length) {
        cmp.rule.groups.push({ logic: 'AND', conditions: [{ tag_code: '', op: 'eq', value: '', value_to: '' }] });
      }
      markDirty();
      renderEditor();
      renderOutputMap();
    });
  });
  $('#cmp-add-group').addEventListener('click', function () {
    captureEditor();
    cmp.rule.groups.push({ logic: 'AND', conditions: [{ tag_code: '', op: 'eq', value: '', value_to: '' }] });
    markDirty();
    renderEditor();
  });
  host.querySelectorAll('[data-ctag]').forEach(function (sel) {
    sel.addEventListener('change', function () {
      captureEditor();
      var gi = parseInt(sel.getAttribute('data-g'), 10);
      var ci = parseInt(sel.getAttribute('data-c'), 10);
      var condition = cmp.rule.groups[gi].conditions[ci];
      condition.tag_code = this.value;
      var meta = metaOf(condition.tag_code);
      condition.op = (OPS_BY_TYPE[meta ? meta.value_type : 'string'] || ['eq'])[0];
      condition.value = meta && meta.value_type === 'enum' ? (enumValues(meta)[0] || '') : '';
      condition.value_to = '';
      markDirty();
      renderEditor();
    });
  });
  host.querySelectorAll('[data-cop]').forEach(function (sel) {
    sel.addEventListener('change', function () {
      captureEditor();
      var gi = parseInt(sel.getAttribute('data-g'), 10);
      var ci = parseInt(sel.getAttribute('data-c'), 10);
      cmp.rule.groups[gi].conditions[ci].op = this.value;
      markDirty();
      renderEditor();
    });
  });
  host.querySelectorAll('[data-cval], [data-cval2]').forEach(function (input) {
    input.addEventListener('input', markDirty);
  });
}

function conditionHtml(gi, ci, condition) {
  var meta = metaOf(condition.tag_code);
  var valueType = meta ? meta.value_type : 'string';
  return '<div class="cmp-cond" data-cond="' + ci + '">' +
    '<select data-ctag data-g="' + gi + '" data-c="' + ci + '" class="cmp-cond-tag">' + tagOptions(condition.tag_code) + '</select>' +
    '<select data-cop data-g="' + gi + '" data-c="' + ci + '" class="cmp-cond-op">' + opOptions(valueType, condition.op) + '</select>' +
    valueControl(condition, meta) +
    '<button type="button" class="cmp-cond-remove" data-remove-cond="' + ci + '" data-group-i="' + gi + '" title="删除条件">×</button>' +
    '</div>';
}

function captureEditor() {
  var host = $('#cmp-editor-body');
  if (!host) return;
  var logicSel = $('#cmp-rule-logic');
  if (logicSel) cmp.rule.logic = logicSel.value;
  var groups = [];
  host.querySelectorAll('[data-group]').forEach(function (groupEl) {
    var group = { logic: groupEl.querySelector('[data-glogic]').value, conditions: [] };
    groupEl.querySelectorAll('[data-cond]').forEach(function (condEl) {
      var tag = condEl.querySelector('[data-ctag]').value;
      group.conditions.push({
        tag_code: tag,
        op: condEl.querySelector('[data-cop]').value,
        value: condEl.querySelector('[data-cval]') ? condEl.querySelector('[data-cval]').value : null,
        value_to: condEl.querySelector('[data-cval2]') ? condEl.querySelector('[data-cval2]').value : null
      });
    });
    groups.push(group);
  });
  if (groups.length) cmp.rule.groups = groups;
}

function addConditionWith(code) {
  var checkMeta = metaOf(code);
  if (!supportsObjectType(checkMeta, cmp.meta.object_type)) {
    toast('该标签不适用于 ' + cmp.meta.object_type + '，不能加入当前组合', 'err');
    return;
  }
  captureEditor();
  var groups = cmp.rule.groups;
  var group = groups[groups.length - 1];
  var meta = metaOf(code);
  group.conditions = group.conditions.filter(function (c) { return c.tag_code; });
  group.conditions.push({
    tag_code: code,
    op: (OPS_BY_TYPE[meta ? meta.value_type : 'string'] || ['eq'])[0],
    value: meta && meta.value_type === 'enum' ? (enumValues(meta)[0] || '') : '',
    value_to: ''
  });
  markDirty();
  renderEditor();
  renderOutputMap();
  toast('已加入条件：' + (meta ? meta.tag_name : code), 'ok');
}

/* ============================================================
 * 右侧：定义 / 输出 / 预检
 * ============================================================ */
function renderMeta() {
  var meta = cmp.meta;
  var cats = cmp.categories.map(function (c) {
    return '<option value="' + esc(c.category_code) + '"' + (c.category_code === meta.category_code ? ' selected' : '') + '>' +
      esc(c.category_name) + '</option>';
  }).join('');
  var editing = !!cmp.editing;

  $('#cmp-meta-body').innerHTML =
    '<div class="cmp-block">' +
    '  <div class="cmp-block-title">基本信息</div>' +
    '  <div class="cmp-field"><span>标签名称 <i class="req">*</i></span><input id="cmp-name" maxlength="128" value="' + esc(meta.tag_name) + '" placeholder="如：高风险驾驶组合"></div>' +
    '  <div class="cmp-field"><span>标签编码 <i class="req">*</i></span><input id="cmp-code" class="mono" maxlength="64" value="' + esc(meta.tag_code) + '"' + (editing ? ' disabled' : '') + ' placeholder="如：risk_combo_high"></div>' +
    '  <div class="cmp-field"><span>类目</span><select id="cmp-category"><option value="">未分类</option>' + cats + '</select></div>' +
    '  <div class="cmp-field"><span>对象类型 <i class="req">*</i></span><select id="cmp-otype">' + OBJECT_TYPES.map(function (t) {
      return '<option value="' + t.value + '"' + (t.value === meta.object_type ? ' selected' : '') + '>' + t.label + '</option>';
    }).join('') + '</select></div>' +
    '  <div class="cmp-field"><span>适用租户' + (editing ? '（创建后不可改）' : '') + '</span>' +
    tenantChoiceOptions(cmp.tenants, meta.tenant_id, 'cmp') + '</div>' +
    '  <div class="cmp-field"><span>说明</span><input id="cmp-desc" maxlength="512" value="' + esc(meta.description || '') + '" placeholder="组合口径说明（可选）"></div>' +
    '</div>' +
    '<div class="cmp-block">' +
    '  <div class="cmp-block-title">输出定义</div>' +
    '  <div class="cmp-field"><span>输出类型 <i class="req">*</i></span><select id="cmp-output-type">' + OUTPUT_TYPES.map(function (t) {
      return '<option value="' + t.value + '"' + (t.value === cmp.outputType ? ' selected' : '') + '>' + t.label + '</option>';
    }).join('') + '</select></div>' +
    '  <div class="cmp-field"><span>输出方式</span><select id="cmp-output-mode">' +
    '    <option value="fixed"' + (cmp.rule.output.mode === 'fixed' ? ' selected' : '') + '>命中 → 固定值</option>' +
    '    <option value="mapping"' + (cmp.rule.output.mode === 'mapping' ? ' selected' : '') + '>按标签值映射</option>' +
    '  </select></div>' +
    '  <div id="cmp-output-fields"></div>' +
    '  <div class="cmp-field"><span>缺失值策略</span><select id="cmp-missing">' +
    '    <option value="hit_false"' + (cmp.rule.missing_policy === 'hit_false' ? ' selected' : '') + '>按不命中处理</option>' +
    '    <option value="unknown"' + (cmp.rule.missing_policy === 'unknown' ? ' selected' : '') + '>输出未知（不写值）</option>' +
    '  </select></div>' +
    '</div>' +
    '<div class="cmp-block">' +
    '  <div class="cmp-block-title">预检</div>' +
    '  <div class="cmp-preview-bar"><input id="cmp-preview-oid" placeholder="对象 ID，如 DRV85001"><button class="btn btn-ghost btn-sm" id="cmp-preview-btn">预检</button></div>' +
    '  <div id="cmp-preview-body" class="cmp-preview-body"></div>' +
    '</div>';

  $('#cmp-name').addEventListener('input', function () { cmp.meta.tag_name = this.value.trim(); markDirty(); updateHeadTitle(); });
  $('#cmp-code').addEventListener('input', function () { cmp.meta.tag_code = this.value.trim(); markDirty(); updateHeadTitle(); });
  $('#cmp-category').addEventListener('change', function () { cmp.meta.category_code = this.value; markDirty(); });
  $('#cmp-otype').addEventListener('change', onObjectTypeChange);
  $('#cmp-desc').addEventListener('input', function () { cmp.meta.description = this.value.trim(); markDirty(); });
  $('#cmp-output-type').addEventListener('change', function () {
    captureOutput();
    cmp.outputType = this.value;
    if (this.value === 'boolean') cmp.rule.output.value = 'true';
    if (this.value !== 'enum') cmp.rule.output.mode = 'fixed';
    markDirty();
    renderMeta();
  });
  $('#cmp-output-mode').addEventListener('change', function () {
    captureOutput();
    cmp.rule.output.mode = this.value;
    markDirty();
    renderMeta();
  });
  $('#cmp-missing').addEventListener('change', function () { cmp.rule.missing_policy = this.value; markDirty(); });
  $('#cmp-preview-btn').addEventListener('click', runPreview);
  $('#cmp-preview-oid').addEventListener('keydown', function (e) { if (e.key === 'Enter') runPreview(); });
  document.querySelectorAll('[data-tenant-pick="cmp"]').forEach(function (el) {
    el.addEventListener('change', markDirty);
  });
  renderOutputFields();
  renderCmpActions();
  renderPreview();
}

function updateHeadTitle() {
  var title = $('.cmp-ed-title');
  if (!title) return;
  var name = title.querySelector('b');
  var code = title.querySelector('.mono');
  if (name) name.textContent = cmp.meta.tag_name || '新建组合';
  if (code) code.textContent = cmp.meta.tag_code || '未保存';
}

function onObjectTypeChange() {
  captureEditor();
  var next = this.value;
  var invalid = [];
  cmp.rule.groups.forEach(function (group) {
    group.conditions.forEach(function (c) {
      if (!c.tag_code) return;
      var meta = metaOf(c.tag_code);
      if (meta && !supportsObjectType(meta, next)) invalid.push(meta.tag_name || c.tag_code);
    });
  });
  if (invalid.length) {
    this.value = cmp.meta.object_type;
    toast('以下条件不适用于 ' + next + '：' + invalid.join('、') + '，请先移除后再切换', 'err');
    return;
  }
  cmp.meta.object_type = next;
  markDirty();
  renderEditor();
  renderOutputFields();
  renderLibrary();
}

function captureOutput() {
  var fixed = $('#cmp-fixed-value');
  if (fixed) cmp.rule.output.value = fixed.value;
  var source = $('#cmp-map-source');
  if (source) cmp.rule.output.source_tag_code = source.value;
  var dft = $('#cmp-map-default');
  if (dft) cmp.rule.output.default_value = dft.value;
  var entries = [];
  var host = $('#cmp-output-fields');
  if (host) {
    host.querySelectorAll('[data-map-row]').forEach(function (row) {
      var from = row.querySelector('[data-map-from]');
      var to = row.querySelector('[data-map-to]');
      if (from && to && from.value.trim()) {
        entries.push({ from: from.value.trim(), to: to.value.trim() });
      }
    });
    if (host.querySelector('[data-map-row]')) cmp.rule.output.entries = entries;
  }
}

function renderOutputFields() {
  var host = $('#cmp-output-fields');
  if (!host) return;
  var out = cmp.rule.output;
  var html = '';
  if (out.mode === 'mapping') {
    var condCodes = [];
    cmp.rule.groups.forEach(function (g) {
      g.conditions.forEach(function (c) { if (c.tag_code) condCodes.push(c.tag_code); });
    });
    html += '<div class="cmp-field"><span>映射来源标签 <i class="req">*</i></span><select id="cmp-map-source">' +
      '<option value="">选择条件中的标签</option>' +
      condCodes.map(function (code) {
        var m = metaOf(code);
        return '<option value="' + esc(code) + '"' + (code === out.source_tag_code ? ' selected' : '') + '>' +
          esc(m ? m.tag_name : code) + ' · ' + esc(code) + '</option>';
      }).join('') + '</select></div>';
    html += '<div class="cmp-field"><span>未命中默认值</span><input id="cmp-map-default" value="' + esc(out.default_value || '') + '" placeholder="可选"></div>';
    html += '<div class="cmp-map-rows">' + (out.entries || []).map(function (e, i) {
      return '<div class="cmp-map-row" data-map-row="' + i + '">' +
        '<input data-map-from value="' + esc(e.from) + '" placeholder="来源值">' +
        '<span>→</span>' +
        '<input data-map-to value="' + esc(e.to) + '" placeholder="输出值">' +
        '<button type="button" class="cmp-cond-remove" data-remove-map="' + i + '">×</button></div>';
    }).join('') + '</div>' +
      '<button type="button" class="btn btn-link btn-sm" id="cmp-add-map">＋ 添加映射</button>';
  } else if (cmp.outputType === 'boolean') {
    html += '<div class="cmp-field"><span>命中输出值</span><select id="cmp-fixed-value">' +
      '<option value="true"' + (out.value !== 'false' ? ' selected' : '') + '>true（是）</option>' +
      '<option value="false"' + (out.value === 'false' ? ' selected' : '') + '>false（否）</option></select></div>';
  } else {
    var placeholder = cmp.outputType === 'number' ? '如 1' : (cmp.outputType === 'enum' ? '如 high' : '如 高风险');
    html += '<div class="cmp-field"><span>命中输出值 <i class="req">*</i></span><input id="cmp-fixed-value" value="' + esc(out.value || '') + '" placeholder="' + placeholder + '"></div>';
  }
  host.innerHTML = html;
  var fixed = $('#cmp-fixed-value');
  if (fixed) fixed.addEventListener('input', function () { cmp.rule.output.value = this.value; markDirty(); });
  var source = $('#cmp-map-source');
  if (source) source.addEventListener('change', function () { cmp.rule.output.source_tag_code = this.value; markDirty(); });
  var dft = $('#cmp-map-default');
  if (dft) dft.addEventListener('input', function () { cmp.rule.output.default_value = this.value; markDirty(); });
  var add = $('#cmp-add-map');
  if (add) add.addEventListener('click', function () {
    captureOutput();
    cmp.rule.output.entries = (cmp.rule.output.entries || []).concat([{ from: '', to: '' }]);
    markDirty();
    renderOutputFields();
  });
  host.querySelectorAll('[data-remove-map]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      captureOutput();
      cmp.rule.output.entries.splice(parseInt(btn.getAttribute('data-remove-map'), 10), 1);
      markDirty();
      renderOutputFields();
    });
  });
  host.querySelectorAll('[data-map-from], [data-map-to]').forEach(function (input) {
    input.addEventListener('input', markDirty);
  });
}

function renderOutputMap() {
  if (cmp.rule.output.mode === 'mapping') renderOutputFields();
}

function renderCmpActions() {
  var canManage = canByPermission('op.composition.manage', ['ADMIN', 'TENANT_ADMIN']);
  var host = $('#cmp-actions');
  if (!host) return;
  var published = cmp.editing && cmp.editing.status === 'published';
  var html = '';
  if (canManage) {
    if (cmp.editing) {
      html += '<button class="btn btn-ghost btn-sm" id="cmp-versions">版本</button>';
      html += '<button class="btn btn-link btn-danger btn-sm" id="cmp-delete">删除</button>';
    }
    if (published) {
      html += '<button class="btn btn-ghost btn-sm" id="cmp-disable">停用</button>';
    }
    html += '<button class="btn btn-ghost btn-sm" id="cmp-save">' + (cmp.editing ? '保存修改' : '保存草稿') + '</button>';
    if (!published) {
      html += '<button class="btn btn-primary btn-sm" id="cmp-publish">保存并发布</button>';
    }
  } else {
    html += '<span class="muted">只读：维护需平台管理员或租户管理员</span>';
  }
  host.innerHTML = html;
  if ($('#cmp-save')) $('#cmp-save').addEventListener('click', function () { saveDraft(false); });
  if ($('#cmp-publish')) $('#cmp-publish').addEventListener('click', function () { saveDraft(true); });
  if ($('#cmp-disable')) $('#cmp-disable').addEventListener('click', disableComposition);
  if ($('#cmp-versions')) $('#cmp-versions').addEventListener('click', openVersions);
  if ($('#cmp-delete')) $('#cmp-delete').addEventListener('click', confirmDeleteComposition);
}

/* ============================================================
 * 预检
 * ============================================================ */
function runPreview() {
  captureEditor();
  captureOutput();
  var objectId = $('#cmp-preview-oid').value.trim();
  if (!objectId) { toast('请输入对象 ID', 'err'); return; }
  var payload = {
    tenant_id: currentTenant(),
    object_type: cmp.meta.object_type,
    object_id: objectId,
    output_type: cmp.outputType,
    rule: cleanRule()
  };
  var btn = $('#cmp-preview-btn');
  btn.disabled = true;
  btn.textContent = '预检中…';
  api('/tag-compositions/preview', { method: 'POST', body: payload }).then(function (res) {
    cmp.preview = res;
    renderPreview();
  }).catch(function (e) {
    cmp.preview = { error: e.message };
    renderPreview();
  }).finally(function () {
    if ($('#cmp-preview-btn')) { btn.disabled = false; btn.textContent = '预检'; }
  });
}

function renderPreview() {
  var host = $('#cmp-preview-body');
  if (!host) return;
  var p = cmp.preview;
  if (!p) { host.innerHTML = '<div class="cmp-preview-empty">输入对象 ID 后预检，可查看参与标签的当前值与判定过程</div>'; return; }
  if (p.error) { host.innerHTML = '<div class="cmp-preview-error">' + esc(p.error) + '</div>'; return; }
  var statusText = p.status === 'effective' ? '命中' : (p.status === 'none' ? '未知（存在缺失值）' : '未命中');
  var statusCls = p.status === 'effective' ? 'badge-green' : (p.status === 'none' ? 'badge-orange' : 'badge-gray');
  var html = '<div class="cmp-preview-result">' +
    '<span class="badge ' + statusCls + '">' + statusText + '</span>' +
    '<span class="cmp-preview-value">' + (p.value === null || p.value === undefined ? '--' : esc(p.value)) + '</span>' +
    '<span class="muted mono">' + esc(p.object_type) + ' / ' + esc(p.object_id) + '</span></div>';
  html += '<div class="cmp-trace">' + (p.trace || []).map(function (t) {
    return '<div class="cmp-trace-row">' +
      '<span class="cmp-trace-name">' + esc(t.tag_name || t.tag_code) + '</span>' +
      '<span class="cmp-trace-op">' + esc(OP_LABEL[t.op] || t.op) + ' ' + esc(t.expected || '') + '</span>' +
      '<span class="cmp-trace-actual' + (t.missing ? ' is-missing' : '') + '">' + (t.missing ? '无值' : esc(t.actual)) + '</span>' +
      '<span class="badge ' + (t.matched ? 'badge-green' : 'badge-gray') + '">' + (t.matched ? '命中' : '不命中') + '</span>' +
      '</div>';
  }).join('') + '</div>';
  if (p.warnings && p.warnings.length) {
    html += '<div class="cmp-preview-warn">' + p.warnings.map(esc).join('<br>') + '</div>';
  }
  host.innerHTML = html;
  if (host.scrollIntoView) host.scrollIntoView({ block: 'nearest' });
}

/* ============================================================
 * 保存 / 发布 / 停用 / 版本 / 删除
 * ============================================================ */
function currentTenant() {
  var chosen = selectedTenantChoices('cmp');
  return chosen || cmp.meta.tenant_id || workTenantId();
}

function cleanRule() {
  var groups = (cmp.rule.groups || []).map(function (g) {
    return {
      logic: g.logic,
      conditions: g.conditions.filter(function (c) { return c.tag_code; }).map(function (c) {
        return { tag_code: c.tag_code, op: c.op, value: c.value, value_to: c.value_to };
      })
    };
  }).filter(function (g) { return g.conditions.length; });
  return {
    logic: cmp.rule.logic,
    groups: groups,
    output: {
      mode: cmp.rule.output.mode,
      value: cmp.rule.output.value,
      source_tag_code: cmp.rule.output.source_tag_code,
      default_value: cmp.rule.output.default_value,
      entries: cmp.rule.output.entries
    },
    missing_policy: cmp.rule.missing_policy
  };
}

function buildPayload() {
  captureEditor();
  captureOutput();
  var name = ($('#cmp-name') ? $('#cmp-name').value : cmp.meta.tag_name || '').trim();
  var code = ($('#cmp-code') ? $('#cmp-code').value : cmp.meta.tag_code || '').trim();
  var category = $('#cmp-category') ? $('#cmp-category').value : cmp.meta.category_code;
  var otype = $('#cmp-otype') ? $('#cmp-otype').value : cmp.meta.object_type;
  var desc = $('#cmp-desc') ? $('#cmp-desc').value.trim() : cmp.meta.description;
  var rule = cleanRule();
  if (!name) throw new Error('请填写标签名称');
  if (!cmp.editing && !code) throw new Error('请填写标签编码');
  if (!rule.groups.length) throw new Error('至少配置一条有效条件');
  if (rule.output.mode === 'mapping' && !rule.output.source_tag_code) throw new Error('映射输出需要选择来源标签');
  return {
    tag_code: cmp.editing ? cmp.editing.tag_code : code,
    tag_name: name,
    category_code: category,
    object_type: otype,
    tenant_id: cmp.editing ? cmp.editing.tenant_id : currentTenant(),
    output_type: cmp.outputType,
    description: desc,
    rule: rule
  };
}

function saveDraft(thenPublish) {
  var payload;
  try {
    payload = buildPayload();
  } catch (e) {
    toast(e.message, 'err');
    return;
  }
  var isEdit = !!cmp.editing;
  var req = isEdit
    ? api('/tag-compositions/' + encodeURIComponent(cmp.editing.tag_code), { method: 'PUT', body: payload })
    : api('/tag-compositions', { method: 'POST', body: payload });
  req.then(function (view) {
    cmp.editing = view;
    cmp.meta = {
      tag_code: view.tag_code, tag_name: view.tag_name, category_code: view.category_code,
      object_type: view.object_type, tenant_id: view.tenant_id, description: view.description
    };
    if (thenPublish) {
      return api('/tag-compositions/' + encodeURIComponent(view.tag_code) + '/publish', { method: 'POST' })
        .then(function (published) {
          cmp.editing = published;
          cmp.dirty = false;
          toast('已发布：' + published.tag_name, 'ok');
          return reloadComps().then(function () { renderEditorView(); });
        });
    }
    cmp.dirty = false;
    toast(isEdit
      ? (view.status === 'published' ? '已保存，修改立即生效（v' + view.meta_version + '）' : '已保存（v' + view.meta_version + '）')
      : '已保存为草稿', 'ok');
    return reloadComps().then(function () { renderEditorView(); });
  }).catch(function (e) {
    toast(e.message, 'err');
  });
}

function disableComposition() {
  if (!cmp.editing) return;
  api('/tag-compositions/' + encodeURIComponent(cmp.editing.tag_code) + '/disable', { method: 'POST' })
    .then(function (view) {
      cmp.editing = view;
      cmp.dirty = false;
      toast('已停用', 'ok');
      reloadComps();
      renderEditorView();
    }).catch(function (e) { toast(e.message, 'err'); });
}

function openVersions() {
  if (!cmp.editing) return;
  var code = cmp.editing.tag_code;
  api('/tags/' + encodeURIComponent(code) + '/versions?limit=50').then(function (rows) {
    var body = '<div class="cmp-version-list">' + (rows || []).map(function (v) {
      return '<div class="cmp-version-row">' +
        '<span class="mono">v' + v.version + '</span>' +
        '<span>' + esc(v.change_type) + '</span>' +
        '<span class="muted">' + esc(v.change_by || '') + '</span>' +
        '<span class="muted">' + fmtDt(v.created_at) + '</span>' +
        '<button type="button" class="btn btn-ghost btn-sm" data-rollback="' + v.version + '">回滚</button>' +
        '</div>';
    }).join('') + '</div>';
    openModal('版本历史 · ' + code, body, [{ text: '关闭', cls: 'btn-ghost', onClick: closeModal }], 'lg');
    $('#modal-body').querySelectorAll('[data-rollback]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var version = btn.getAttribute('data-rollback');
        api('/tag-compositions/' + encodeURIComponent(code) + '/rollback/' + version, { method: 'POST' })
          .then(function (view) {
            closeModal();
            cmp.editing = view;
            applyView(view);
            cmp.dirty = false;
            toast('已回滚到 v' + version, 'ok');
            reloadComps();
            renderEditorView();
          }).catch(function (e) { toast(e.message, 'err'); });
      });
    });
  }).catch(function (e) { toast(e.message, 'err'); });
}

function confirmDeleteComposition() {
  if (!cmp.editing) return;
  var view = cmp.editing;
  var body = '<div class="notice notice-warn">删除后组合规则不可恢复（标签历史版本仍保留），派生标签将归档并从画像中立即消失；标签编码仍被占用。</div>' +
    '<div class="cmp-delete-summary">' +
    '  <div><span>标签名称</span><b>' + esc(view.tag_name) + '</b></div>' +
    '  <div><span>标签编码</span><b class="mono">' + esc(view.tag_code) + '</b></div>' +
    '  <div><span>当前状态</span><b>' + esc((COMP_STATUS[view.status] || {}).label || view.status) + '</b></div>' +
    '</div>' +
    '<div class="form-tip">若该组合被其他组合引用，或已上架标签市场，删除会被拒绝并提示原因。</div>';
  openModal('删除组合标签', body, [
    { text: '取消', cls: 'btn-ghost', onClick: closeModal },
    { text: '确认删除', cls: 'btn-danger', onClick: doDeleteComposition }
  ]);
}

function doDeleteComposition() {
  if (!cmp.editing) return;
  var code = cmp.editing.tag_code;
  api('/tag-compositions/' + encodeURIComponent(code), { method: 'DELETE' }).then(function () {
    closeModal();
    toast('已删除组合：' + code, 'ok');
    cmp.editing = null;
    cmp.dirty = false;
    cmp.view = 'list';
    renderListView();
    reloadComps();
  }).catch(function (e) { toast(e.message, 'err'); });
}

/* ============================================================
 * 标签查看抽屉
 * ============================================================ */
function initDrawer() {
  if ($('#cmp-drawer')) return;
  var host = document.createElement('div');
  host.id = 'cmp-drawer';
  host.className = 'cmp-drawer hidden';
  host.innerHTML =
    '<div class="cmp-drawer-mask" data-close-drawer></div>' +
    '<aside class="cmp-drawer-panel" role="dialog" aria-label="标签查看">' +
    '  <header class="cmp-drawer-head">' +
    '    <div class="cmp-drawer-title"><b id="cmp-drawer-name">--</b><span class="mono" id="cmp-drawer-code"></span></div>' +
    '    <button class="modal-close" data-close-drawer aria-label="关闭">×</button>' +
    '  </header>' +
    '  <div class="cmp-drawer-meta" id="cmp-drawer-meta"></div>' +
    '  <div class="cmp-drawer-tabs" id="cmp-drawer-tabs">' +
    '    <button type="button" data-dtab="current" class="active">当前值</button>' +
    '    <button type="button" data-dtab="history">流水</button>' +
    '    <button type="button" data-dtab="refs">引用</button>' +
    '  </div>' +
    '  <div class="cmp-drawer-body" id="cmp-drawer-body"></div>' +
    '  <footer class="cmp-drawer-foot" id="cmp-drawer-foot"></footer>' +
    '</aside>';
  document.body.appendChild(host);
  host.querySelectorAll('[data-close-drawer]').forEach(function (el) {
    el.addEventListener('click', closeTagDrawer);
  });
  host.querySelectorAll('[data-dtab]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      host.querySelectorAll('[data-dtab]').forEach(function (b) { b.classList.toggle('active', b === btn); });
      if (cmp.drawer) cmp.drawer.tab = btn.getAttribute('data-dtab');
      renderDrawerTab(btn.getAttribute('data-dtab'));
    });
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && cmp.drawer && !$('#cmp-drawer').classList.contains('hidden')) closeTagDrawer();
  });
}

function openTagDrawer(code) {
  initDrawer();
  var meta = metaOf(code);
  cmp.drawer = { code: code, tab: 'current', usage: null };
  $('#cmp-drawer').classList.remove('hidden');
  document.body.classList.add('modal-open');
  $('#cmp-drawer-name').textContent = meta ? meta.tag_name : code;
  $('#cmp-drawer-code').textContent = code;
  $('#cmp-drawer-meta').innerHTML = meta
    ? '<span class="badge badge-gray">' + esc(meta.value_type) + '</span>' +
      '<span class="badge badge-blue">' + esc(TAG_TYPE_LABEL[meta.tag_type] || meta.tag_type || '--') + '</span>' +
      '<span class="badge badge-gray">' + esc(meta.sensitivity || '--') + '</span>' +
      (meta.object_type ? '<span class="muted">对象 ' + esc(meta.object_type) + '</span>' : '') +
      (meta.category_code ? '<span class="muted">类目 ' + esc(meta.category_code) + '</span>' : '')
    : '<span class="muted">标签信息加载中</span>';
  $('#cmp-drawer-tabs').querySelectorAll('[data-dtab]').forEach(function (b) {
    b.classList.toggle('active', b.getAttribute('data-dtab') === 'current');
  });
  $('#cmp-drawer-foot').innerHTML =
    '<button class="btn btn-ghost btn-sm" id="cmp-drawer-add">加入当前组合</button>';
  var addBtn = $('#cmp-drawer-add');
  if (addBtn) {
    addBtn.disabled = !meta || !supportsObjectType(meta, cmp.meta.object_type);
    addBtn.addEventListener('click', function () {
      addConditionWith(code);
      closeTagDrawer();
    });
  }
  $('#cmp-drawer-body').innerHTML = '<div class="cmp-preview-empty">正在读取使用情况…</div>';
  api('/tags/' + encodeURIComponent(code) + '/usage' + qs({ tenantId: workTenantId() || '' }))
    .then(function (res) {
      if (!cmp.drawer || cmp.drawer.code !== code) return;
      cmp.drawer.usage = res;
      renderDrawerTab(cmp.drawer.tab);
    })
    .catch(function (e) {
      $('#cmp-drawer-body').innerHTML = '<div class="cmp-preview-error">' + esc(e.message) + '</div>';
    });
}

function closeTagDrawer() {
  cmp.drawer = null;
  var host = $('#cmp-drawer');
  if (host) host.classList.add('hidden');
  document.body.classList.remove('modal-open');
}

function renderDrawerTab(tab) {
  var host = $('#cmp-drawer-body');
  var u = cmp.drawer && cmp.drawer.usage;
  if (!host || !u) return;
  if (tab === 'current') {
    var html = '';
    if (u.derived) {
      html += '<div class="cmp-usage-note">派生标签不存 latest，按输入标签最近有值的对象实时计算（本次评估 ' +
        u.evaluated + ' 个对象）。' +
        (u.composition_status && u.composition_status !== 'published'
          ? ' 该组合当前为' + ((COMP_STATUS[u.composition_status] || {}).label || u.composition_status) + '，以下为试算结果。'
          : '') + '</div>';
      html += !(u.current_values || []).length
        ? '<div class="cmp-preview-empty">当前租户下暂无可计算对象：输入标签还没有生效值</div>'
        : '<div class="cmp-usage-list">' + u.current_values.map(function (v) {
          var st = DERIVED_ROW_STATUS[v.status] || ['badge-gray', v.status];
          return '<div class="cmp-usage-row is-derived">' +
            '<span class="mono">' + esc(v.object_type) + ' / ' + esc(v.object_id) + '</span>' +
            '<span class="cmp-usage-value">' + esc(v.tag_value == null ? '--' : v.tag_value) + '</span>' +
            '<span class="badge ' + st[0] + '">' + st[1] + '</span>' +
            '<span class="cmp-usage-detail" title="' + esc(v.detail || '') + '">' + esc(v.detail || '--') + '</span>' +
            '</div>';
        }).join('') + '</div>';
    } else if (!(u.current_values || []).length) {
      html += '<div class="cmp-preview-empty">该租户下暂无当前值</div>';
    } else {
      html += '<div class="cmp-usage-list">' + u.current_values.map(function (v) {
        return '<div class="cmp-usage-row">' +
          '<span class="mono">' + esc(v.object_type) + ' / ' + esc(v.object_id) + '</span>' +
          '<span class="cmp-usage-value">' + esc(v.tag_value == null ? '--' : v.tag_value) + '</span>' +
          badge(v.status) +
          '<span class="muted">' + fmtDt(v.operate_time) + '</span>' +
          '<span class="muted">' + esc(v.operator_name || '--') + '</span>' +
          '</div>';
      }).join('') + '</div>' +
        '<div class="cmp-usage-note">共 ' + u.current_total + ' 条当前值，展示最近 ' +
        (u.current_values || []).length + ' 条</div>';
    }
    host.innerHTML = html;
    return;
  }
  if (tab === 'history') {
    if (u.derived) {
      host.innerHTML = '<div class="cmp-preview-empty">派生标签由查询期计算，不产生写入流水</div>';
      return;
    }
    host.innerHTML = (u.recent_values || []).length
      ? '<div class="cmp-usage-list">' + u.recent_values.map(function (v) {
        return '<div class="cmp-usage-row">' +
          '<span class="mono">' + esc(v.object_type) + ' / ' + esc(v.object_id) + '</span>' +
          '<span>' + esc(v.tag_value == null ? '--' : v.tag_value) + '</span>' +
          badge(v.status) +
          '<span class="muted">' + fmtDt(v.operate_time) + '</span>' +
          '<span class="muted">' + esc(v.operator_name || '--') + '</span>' +
          '</div>';
      }).join('') + '</div>'
      : '<div class="cmp-preview-empty">暂无流水</div>';
    return;
  }
  host.innerHTML = (u.referenced_by || []).length
    ? '<div class="cmp-usage-list">' + u.referenced_by.map(function (r) {
      return '<div class="cmp-usage-row is-refs">' +
        '<span class="mono">' + esc(r.tag_code) + '</span>' +
        '<span>' + esc(r.tag_name) + '</span>' +
        '<span class="badge badge-gray">' + esc(COMP_STATUS[r.status] ? COMP_STATUS[r.status].label : r.status) + '</span>' +
        '</div>';
    }).join('') + '</div>'
    : '<div class="cmp-preview-empty">暂未被组合引用</div>';
}

/* ============================================================
 * 视图状态
 * ============================================================ */
function applyView(view) {
  cmp.outputType = view.output_type;
  cmp.meta = {
    tag_code: view.tag_code,
    tag_name: view.tag_name,
    category_code: view.category_code || '',
    object_type: view.object_type,
    tenant_id: view.tenant_id,
    description: view.description || ''
  };
  cmp.rule = view.rule || defaultRule();
  if (!cmp.rule.output) cmp.rule.output = defaultRule().output;
  if (!cmp.rule.groups || !cmp.rule.groups.length) cmp.rule.groups = defaultRule().groups;
  cmp.rule.groups = cmp.rule.groups.map(function (g) {
    return {
      logic: g.logic || 'AND',
      conditions: (g.conditions || []).map(function (c) {
        return { tag_code: c.tag_code, op: c.op, value: c.value, value_to: c.value_to };
      })
    };
  });
  cmp.preview = null;
  cmp.dirty = false;
}
