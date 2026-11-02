/* 控制台共享页面工具（依赖 core 的 DOM/格式化基础函数） */
import * as core from './core.js';

const { $, esc, parseJsonValue } = core;

export function tenantOptions(tenants, selected) {
  var ids = [];
  (tenants || []).forEach(function (t) {
    var id = t.tenant_id || t.tenantId;
    if (id && ids.indexOf(id) < 0) ids.push(id);
  });
  if (ids.indexOf('PUBLIC') < 0) ids.unshift('PUBLIC');
  var selectedSet = {};
  String(selected || '').split(',').forEach(function (v) {
    var id = v.trim();
    if (id) selectedSet[id] = true;
  });
  return ids.map(function (id) {
    return '<option value="' + esc(id) + '"' + (selectedSet[id] ? ' selected' : '') + '>' +
      (id === 'PUBLIC' ? 'PUBLIC（全部租户）' : esc(id)) + '</option>';
  }).join('');
}

export function tenantChoiceOptions(tenants, selected, prefix) {
  var ids = [];
  (tenants || []).forEach(function (t) {
    var id = t.tenant_id || t.tenantId;
    if (id && ids.indexOf(id) < 0) ids.push(id);
  });
  if (ids.indexOf('PUBLIC') < 0) ids.unshift('PUBLIC');
  var selectedSet = {};
  String(selected || '').split(',').forEach(function (v) {
    var id = v.trim();
    if (id) selectedSet[id] = true;
  });
  return '<div class="tenant-picker">' + ids.map(function (id) {
    var checked = selectedSet[id] ? ' checked' : '';
    var label = id === 'PUBLIC' ? 'PUBLIC（全部租户）' : id;
    return '<label class="tenant-choice"><input type="checkbox" data-tenant-pick="' + prefix +
      '" value="' + esc(id) + '"' + checked + '><span>' + esc(label) + '</span></label>';
  }).join('') + '</div>';
}

export function selectedTenantChoices(prefix) {
  var values = [];
  document.querySelectorAll('[data-tenant-pick="' + prefix + '"]:checked').forEach(function (el) {
    values.push(el.value);
  });
  return values.join(',');
}

export function selectedTenants(id) {
  var el = $('#' + id);
  if (!el) return '';
  return Array.from(el.selectedOptions || []).map(function (o) { return o.value; }).join(',');
}

export function sameVersionValue(a, b) {
  if (a === b) return true;
  if (a === null && b === null) return true;
  if (a === undefined && b === undefined) return true;
  return String(a) === String(b);
}

export function formatTagVersionValue(key, value) {
  if (value === null || value === undefined || value === '') return '--';
  if (key === 'value_multiple') return value ? '多选' : '单选';
  if (key === 'need_value_audit') return value ? '需审核' : '免审';
  if (key === 'writable') return value ? '允许' : '禁止';
  if (key === 'value_range') {
    var parsed = parseJsonValue(String(value), null);
    if (parsed && Array.isArray(parsed.enum)) return '枚举：' + parsed.enum.join(' / ');
    return String(value);
  }
  if (key === 'tenant_id' || key === 'object_type') {
    return String(value).split(',').map(function (v) { return v.trim(); }).filter(Boolean).join(' / ');
  }
  return String(value);
}

export function renderPager(sel, total, pageState, reload) {
  var pages = Math.max(Math.ceil((total || 0) / pageState.size), 1);
  var options = [10, 20, 50, 100].map(function (n) {
    return '<option value="' + n + '"' + (n === pageState.size ? ' selected' : '') + '>' + n + '</option>';
  }).join('');
  $(sel).innerHTML =
    '<div class="pager-info">共 <b>' + (total || 0) + '</b> 条</div>' +
    '<div class="pager-right">' +
    '  <label class="pager-size">每页 <select>' + options + '</select></label>' +
    '  <button class="btn btn-ghost btn-sm" data-pager="prev" ' + (pageState.page <= 1 ? 'disabled' : '') + '>上一页</button>' +
    '  <span class="pager-page"><b>' + pageState.page + '</b> / ' + pages + '</span>' +
    '  <button class="btn btn-ghost btn-sm" data-pager="next" ' + (pageState.page >= pages ? 'disabled' : '') + '>下一页</button>' +
    '</div>';
  $(sel + ' [data-pager="prev"]').addEventListener('click', function () { pageState.page--; reload(); });
  $(sel + ' [data-pager="next"]').addEventListener('click', function () { pageState.page++; reload(); });
  $(sel + ' .pager-size select').addEventListener('change', function () {
    pageState.size = parseInt(this.value, 10) || 20;
    pageState.page = 1;
    reload();
  });
}

export function toBackendTime(value) {
  if (!value) return '';
  return value.replace('T', ' ') + (value.length === 16 ? ':00' : '');
}
