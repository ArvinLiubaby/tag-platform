/** 控制台页面模块 · 标签查看（原始标签与组合标签的当前值 / 打标值） */
import * as core from '../core.js';
import { renderPager } from '../shared.js';

const { $, esc, api, qs, toast, emptyState, badge, fmtDt, tableWrap, tableLoading, workTenantId, parseJsonValue } = core;

const TYPE_LABEL = { fact: '事实', rule: '规则', algo: '算法', derived: '组合' };
const OBJECT_TYPES = [
  { value: 'driver', label: 'driver 司机' },
  { value: 'vehicle', label: 'vehicle 车辆' },
  { value: 'fleet', label: 'fleet 车队' }
];
const DERIVED_STATUS = {
  effective: ['badge-green', '命中'],
  not_matched: ['badge-gray', '未命中'],
  none: ['badge-orange', '未知']
};

var tv = {
  tags: [],
  tenants: [],
  keyword: '',
  kind: '',
  objectType: '',
  tenant: '',
  selected: null,
  tab: 'latest',
  latest: null,
  history: { page: 1, size: 20, objectId: '' },
  loading: false
};

export function renderTagViewer() {
  tv.keyword = '';
  tv.kind = '';
  tv.objectType = '';
  tv.tenant = workTenantId() || 'risk_control';
  tv.selected = null;
  tv.tab = 'latest';
  tv.latest = null;
  tv.history = { page: 1, size: 20, objectId: '' };

  $('#content').innerHTML =
    '<div class="tv-layout">' +
    '  <aside class="card tv-side">' +
    '    <div class="card-head"><div class="card-title">标签 <span class="count-chip" id="tv-count">--</span></div>' +
    '      <span class="muted">原始 + 组合</span></div>' +
    '    <div class="tv-side-body">' +
    '      <div class="tv-search"><input id="tv-search" placeholder="搜索名称 / code"><button class="btn btn-ghost btn-sm" id="tv-search-btn">查询</button></div>' +
    '      <div class="tv-filters">' +
    '        <select id="tv-kind"><option value="">全部类型</option><option value="derived">组合标签</option><option value="raw">原始标签</option></select>' +
    '        <select id="tv-object"><option value="">全部对象</option>' + OBJECT_TYPES.map(function (o) {
      return '<option value="' + o.value + '">' + o.label + '</option>';
    }).join('') + '</select>' +
    '      </div>' +
    '      <div id="tv-list" class="tv-list"></div>' +
    '    </div>' +
    '  </aside>' +
    '  <section class="card tv-main">' +
    '    <div class="card-head tv-head">' +
    '      <div class="tv-title"><b id="tv-name">标签详情</b><span class="mono" id="tv-code"></span></div>' +
    '      <div class="tv-badges" id="tv-badges"></div>' +
    '      <div class="card-actions">' +
    '        <a class="btn btn-ghost btn-sm hidden" id="tv-compose-link">组合规则</a>' +
    '        <button class="btn btn-ghost btn-sm" id="tv-refresh">刷新</button>' +
    '      </div>' +
    '    </div>' +
    '    <div class="tv-toolbar">' +
    '      <div class="tv-tabs" id="tv-tabs">' +
    '        <button type="button" data-ttab="latest" class="active">当前值</button>' +
    '        <button type="button" data-ttab="history">打标值</button>' +
    '      </div>' +
    '      <div class="tv-tools" id="tv-tools"></div>' +
    '    </div>' +
    '    <div id="tv-body"></div>' +
    '    <div class="pager hidden" id="tv-pager"></div>' +
    '  </section>' +
    '</div>';

  $('#tv-search-btn').addEventListener('click', function () {
    tv.keyword = $('#tv-search').value.trim();
    renderList();
  });
  $('#tv-search').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { tv.keyword = this.value.trim(); renderList(); }
  });
  $('#tv-kind').addEventListener('change', function () { tv.kind = this.value; renderList(); });
  $('#tv-object').addEventListener('change', function () { tv.objectType = this.value; renderList(); });
  $('#tv-refresh').addEventListener('click', function () {
    if (tv.selected) loadSelected(tv.selected, true);
    else loadTags();
  });
  $('#content').querySelectorAll('[data-ttab]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      tv.tab = btn.getAttribute('data-ttab');
      $('#content').querySelectorAll('[data-ttab]').forEach(function (b) { b.classList.toggle('active', b === btn); });
      renderTab();
    });
  });

  $('#tv-body').innerHTML = emptyState('选择左侧标签', '查看当前值与打标记录');
  loadTags();
}

function loadTags() {
  api('/tags?includeArchived=false').then(function (rows) {
    tv.tags = (rows || []).filter(function (t) { return t.status !== 'archived'; });
    renderList();
    var deep = /[?&]tag=([^&]+)/.exec(location.hash || '');
    if (deep) {
      var code = decodeURIComponent(deep[1]);
      var found = tv.tags.filter(function (t) { return t.tag_code === code; })[0];
      if (found) selectTag(found);
    }
  }).catch(function (e) {
    $('#tv-list').innerHTML = emptyState('加载失败', e.message);
  });
}

function tagMatches(t) {
  if (tv.kind === 'derived' && t.tag_type !== 'derived') return false;
  if (tv.kind === 'raw' && t.tag_type === 'derived') return false;
  if (tv.objectType) {
    var binding = t.object_type || '';
    if (binding && binding.split(',').map(function (v) { return v.trim(); }).indexOf(tv.objectType) < 0) return false;
  }
  if (tv.keyword) {
    var kw = tv.keyword.toLowerCase();
    if ((t.tag_code + ' ' + t.tag_name).toLowerCase().indexOf(kw) < 0) return false;
  }
  return true;
}

function renderList() {
  var rows = tv.tags.filter(tagMatches);
  $('#tv-count').textContent = rows.length;
  var host = $('#tv-list');
  if (!rows.length) {
    host.innerHTML = emptyState('没有匹配的标签', '调整搜索或筛选条件');
    return;
  }
  host.innerHTML = rows.map(function (t) {
    var derived = t.tag_type === 'derived';
    var active = tv.selected && tv.selected.tag_code === t.tag_code ? ' active' : '';
    return '<button type="button" class="tv-item' + active + '" data-tag="' + esc(t.tag_code) + '">' +
      '<span class="tv-item-main"><b>' + esc(t.tag_name) +
      (derived ? ' <span class="badge badge-blue">组合</span>' : '') + '</b>' +
      '<small class="mono">' + esc(t.tag_code) + ' · ' + esc(t.value_type) + ' · ' + esc(t.sensitivity || '') + '</small></span>' +
      '<span class="badge ' + (t.status === 'online' ? 'badge-green' : 'badge-gray') + '">' +
      (t.status === 'online' ? '在线' : '离线') + '</span></button>';
  }).join('');
  host.querySelectorAll('[data-tag]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var tag = tv.tags.filter(function (t) { return t.tag_code === btn.getAttribute('data-tag'); })[0];
      if (tag) selectTag(tag);
    });
  });
}

function selectTag(tag) {
  tv.selected = tag;
  tv.tenant = tv.tenant || workTenantId() || 'risk_control';
  tv.tab = 'latest';
  tv.history = { page: 1, size: 20, objectId: '' };
  $('#content').querySelectorAll('[data-ttab]').forEach(function (b) {
    b.classList.toggle('active', b.getAttribute('data-ttab') === 'latest');
  });
  renderList();
  renderHead();
  loadSelected(tag.tag_code, false);
}

function renderHead() {
  var t = tv.selected;
  if (!t) return;
  $('#tv-name').textContent = t.tag_name;
  $('#tv-code').textContent = t.tag_code;
  var derived = t.tag_type === 'derived';
  $('#tv-badges').innerHTML =
    '<span class="badge ' + (derived ? 'badge-blue' : 'badge-gray') + '">' + (derived ? '组合标签' : '原始标签') + '</span>' +
    '<span class="badge badge-gray">' + esc(t.value_type) + '</span>' +
    '<span class="badge badge-gray">' + esc(t.sensitivity || '--') + '</span>' +
    (t.category_code ? '<span class="muted">类目 ' + esc(t.category_code) + '</span>' : '') +
    (t.object_type ? '<span class="muted">对象 ' + esc(t.object_type) + '</span>' : '');
  var link = $('#tv-compose-link');
  link.classList.toggle('hidden', !derived);
  if (derived) link.setAttribute('href', '#/tag-compose?tag=' + encodeURIComponent(t.tag_code));
  renderTools();
}

function renderTools() {
  var host = $('#tv-tools');
  if (tv.tab === 'latest') {
    host.innerHTML = '<label class="tv-tenant">租户 <input id="tv-tenant" value="' + esc(tv.tenant) + '"></label>' +
      '<span class="muted">' + (tv.selected && tv.selected.tag_type === 'derived'
        ? '抽样实时计算' : '读取 tag_value_latest') + '</span>';
    $('#tv-tenant').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') applyTenant();
    });
    $('#tv-tenant').addEventListener('change', applyTenant);
    return;
  }
  host.innerHTML = '<label class="tv-tenant">租户 <input id="tv-tenant" value="' + esc(tv.tenant) + '"></label>' +
    '<label class="tv-tenant">对象 ID <input id="tv-oid" value="' + esc(tv.history.objectId) + '" placeholder="可选"></label>' +
    '<button class="btn btn-ghost btn-sm" id="tv-his-query">查询</button>';
  $('#tv-his-query').addEventListener('click', function () {
    tv.tenant = $('#tv-tenant').value.trim();
    tv.history.objectId = $('#tv-oid').value.trim();
    tv.history.page = 1;
    loadHistory();
  });
}

function applyTenant() {
  tv.tenant = $('#tv-tenant').value.trim();
  if (!tv.tenant) { toast('租户不能为空', 'err'); return; }
  loadSelected(tv.selected.tag_code, false);
}

function loadSelected(code, force) {
  if (!tv.selected) return;
  if (tv.tab === 'latest') loadLatest(); else loadHistory();
}

function loadLatest() {
  var host = $('#tv-body');
  host.innerHTML = tableLoading(7, 5);
  $('#tv-pager').classList.add('hidden');
  api('/tag-values/latest' + qs({
    tenantId: tv.tenant, tagCode: tv.selected.tag_code, limit: 50
  })).then(function (page) {
    tv.latest = page;
    renderLatest(page);
  }).catch(function (e) {
    host.innerHTML = emptyState('加载失败', e.message);
  });
}

function renderLatest(page) {
  var host = $('#tv-body');
  if (!page.rows || !page.rows.length) {
    host.innerHTML = emptyState(page.derived ? '暂无可计算对象' : '暂无当前值',
      page.derived ? '输入标签在该租户下还没有生效值' : '切换租户或稍后再试');
    return;
  }
  var html;
  if (page.derived) {
    html = '<table><thead><tr><th>对象</th><th>计算结果</th><th>状态</th><th>输入快照</th></tr></thead><tbody>';
    page.rows.forEach(function (r) {
      var st = DERIVED_STATUS[r.status] || ['badge-gray', r.status];
      html += '<tr><td class="mono">' + esc(r.object_type) + ' / ' + esc(r.object_id) + '</td>' +
        '<td class="cell-strong">' + esc(r.tag_value == null ? '--' : r.tag_value) + '</td>' +
        '<td><span class="badge ' + st[0] + '">' + st[1] + '</span></td>' +
        '<td class="cell-sub" title="' + esc(r.detail || '') + '">' + esc(r.detail || '--') + '</td></tr>';
    });
    host.innerHTML = tableWrap(html + '</tbody></table>');
    $('#tv-body').insertAdjacentHTML('afterbegin',
      '<div class="tv-note">组合标签不存 latest，按输入标签最近有值的对象实时计算：命中 ' +
      page.total + ' / 评估 ' + page.evaluated + ' 个对象</div>');
    return;
  }
  html = '<table><thead><tr><th>对象</th><th>当前值</th><th>状态</th><th>业务时间</th>' +
    '<th>操作时间</th><th>操作人</th><th>来源</th></tr></thead><tbody>';
  page.rows.forEach(function (r) {
    html += '<tr><td class="mono">' + esc(r.object_type) + ' / ' + esc(r.object_id) + '</td>' +
      '<td class="cell-strong">' + esc(r.tag_value == null ? '--' : r.tag_value) + '</td>' +
      '<td>' + badge(r.status) + '</td>' +
      '<td class="nowrap muted">' + fmtDt(r.biz_time) + '</td>' +
      '<td class="nowrap muted">' + fmtDt(r.operate_time) + '</td>' +
      '<td>' + esc(r.operator_name || '--') + '</td>' +
      '<td class="muted">' + esc(r.source || '--') + '</td></tr>';
  });
  host.innerHTML = tableWrap(html + '</tbody></table>');
  $('#tv-body').insertAdjacentHTML('afterbegin',
    '<div class="tv-note">tag_value_latest 当前值 共 ' + page.total + ' 条，展示最近 ' + page.rows.length + ' 条</div>');
}

function loadHistory() {
  if (tv.selected.tag_type === 'derived') {
    $('#tv-body').innerHTML = emptyState('组合标签没有写入流水', '派生值在查询期计算，不会写入 tag_value_history');
    $('#tv-pager').classList.add('hidden');
    return;
  }
  $('#tv-body').innerHTML = tableLoading(7, 5);
  api('/tag-values/history' + qs({
    tenantId: tv.tenant, tagCode: tv.selected.tag_code, objectId: tv.history.objectId,
    page: tv.history.page, size: tv.history.size
  })).then(function (page) {
    if (!page.records || !page.records.length) {
      $('#tv-body').innerHTML = emptyState('暂无打标记录', '调整对象 ID 或稍后再试');
      $('#tv-pager').classList.add('hidden');
      return;
    }
    var html = '<table><thead><tr><th>对象</th><th>值</th><th>状态</th><th>业务时间</th>' +
      '<th>操作时间</th><th>操作人</th><th>来源</th></tr></thead><tbody>';
    page.records.forEach(function (r) {
      html += '<tr><td class="mono">' + esc(r.object_type) + ' / ' + esc(r.object_id) + '</td>' +
        '<td class="cell-strong">' + esc(r.tag_value == null ? '--' : r.tag_value) + '</td>' +
        '<td>' + badge(r.status) + '</td>' +
        '<td class="nowrap muted">' + fmtDt(r.biz_time) + '</td>' +
        '<td class="nowrap muted">' + fmtDt(r.operate_time) + '</td>' +
        '<td>' + esc(r.operator_name || '--') + '</td>' +
        '<td class="muted">' + esc(r.source || '--') + '</td></tr>';
    });
    $('#tv-body').innerHTML = tableWrap(html + '</tbody></table>');
    var pager = $('#tv-pager');
    pager.classList.remove('hidden');
    renderPager('#tv-pager', page.total, tv.history, loadHistory);
  }).catch(function (e) {
    $('#tv-body').innerHTML = emptyState('加载失败', e.message);
  });
}

function renderTab() {
  if (!tv.selected) {
    $('#tv-body').innerHTML = emptyState('选择左侧标签', '查看当前值与打标记录');
    $('#tv-pager').classList.add('hidden');
    return;
  }
  renderTools();
  if (tv.tab === 'latest') loadLatest(); else loadHistory();
}
