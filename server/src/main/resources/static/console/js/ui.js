/* ============================================================
 * 控制台表单控件现代化层（ui.js）
 * 目标：把原生 <select> 与 <input type=date/datetime-local> 升级为
 *       自绘的下拉选与日历时间选择，视觉对齐 Linear / Stripe / AntD。
 * 原则：原生控件始终保留在 DOM 中作为唯一"值载体"，页面代码继续
 *       使用 el.value 与 change 事件；本层只负责展示与交互，
 *       并在程序化改值（如筛选重置、fillTenantSelect 重建选项）后自动跟随。
 * ============================================================ */

var CHEVRON = '<svg class="ui-caret" viewBox="0 0 16 16" aria-hidden="true">' +
  '<path d="M4.2 6.1a.9.9 0 0 1 1.3 0L8 8.6l2.5-2.5a.9.9 0 1 1 1.3 1.3l-3.1 3.2a.9.9 0 0 1-1.3 0L4.2 7.4a.9.9 0 0 1 0-1.3z"/>' +
  '</svg>';
var CHECK = '<svg class="ui-check" viewBox="0 0 16 16" aria-hidden="true">' +
  '<path d="M6.2 11.3 3.4 8.5a.9.9 0 0 1 1.3-1.3l1.5 1.5 3.9-4.4a.9.9 0 1 1 1.35 1.2L7.4 11.4a.9.9 0 0 1-1.2-.1z"/></svg>';
var CAL_ICON = '<svg class="ui-caret" viewBox="0 0 16 16" aria-hidden="true">' +
  '<path d="M5.2 1.6a.8.8 0 0 1 1.6 0v.9h2.4v-.9a.8.8 0 1 1 1.6 0v.9h.9c.7 0 1.3.6 1.3 1.3v.7H3.2v-.7c0-.7.6-1.3 1.3-1.3h.7v-.9zM3.2 5.2h9.6v6.4c0 .7-.6 1.3-1.3 1.3H4.5c-.7 0-1.3-.6-1.3-1.3V5.2z"/></svg>';
var CLOCK_ICON = '<svg class="ui-caret" viewBox="0 0 16 16" aria-hidden="true">' +
  '<path d="M8 1.6a6.4 6.4 0 1 1 0 12.8 6.4 6.4 0 0 1 0-12.8zm.8 2.5a.8.8 0 0 0-1.6 0v3.5c0 .3.13.57.35.72l2.2 1.55a.8.8 0 1 0 .9-1.33L8.8 7.28V4.1z"/></svg>';

var controls = [];
var byNative = typeof WeakMap === 'function' ? new WeakMap() : null;
var openCtl = null;

function esc(s) {
  return String(s === null || s === undefined ? '' : s).replace(/[&<>"]/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}
function pad(n) { return (n < 10 ? '0' : '') + n; }

function fieldLabel(el) {
  var field = el.closest ? el.closest('.field') : null;
  var span = field ? field.querySelector(':scope > span') : null;
  return span ? span.textContent.trim() : '';
}

function variantClass(el) {
  if (el.closest && el.closest('.pager-size')) return ' ui-ctl--pager';
  if (el.classList.contains('select-sm')) return ' ui-ctl--sm';
  if (el.classList.contains('wb-input')) return ' ui-ctl--wb';
  return '';
}

function register(ctl) {
  controls.push(ctl);
  if (byNative) byNative.set(ctl.host, ctl);
}
function has(ctl) { return byNative ? byNative.has(ctl.host) : false; }
function isOpen(ctl) { return !!(ctl.panel && ctl.panel.parentNode); }

/* ---------------- 原生值变更广播 ---------------- */
function fireChange(el) {
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

/* ============================================================
 * 下拉选
 * ============================================================ */
function enhanceSelect(sel) {
  if (sel.tagName !== 'SELECT' || (byNative && byNative.has(sel))) return;
  if (sel.multiple || sel.size > 1) return;
  var wrap = document.createElement('div');
  wrap.className = 'ui-select' + variantClass(sel);
  var trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.className = 'ui-select-trigger';
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  var label = fieldLabel(sel);
  if (label) trigger.setAttribute('aria-label', label);
  trigger.innerHTML = '<span class="ui-select-label"></span>' + CHEVRON;
  sel.classList.add('ui-native');
  sel.tabIndex = -1;
  sel.parentNode.insertBefore(wrap, sel);
  wrap.appendChild(trigger);
  wrap.appendChild(sel);
  var ctl = {
    kind: 'select', host: sel, wrap: wrap, trigger: trigger,
    labelEl: trigger.querySelector('.ui-select-label'), panel: null,
    keyword: '', active: -1, sig: null, dis: null
  };
  register(ctl);
  trigger.addEventListener('click', function (e) {
    e.preventDefault();
    e.stopPropagation();
    if (isOpen(ctl)) closePanel(ctl); else openSelect(ctl);
  });
  trigger.addEventListener('keydown', function (e) {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].indexOf(e.key) >= 0 && !isOpen(ctl)) {
      e.preventDefault();
      openSelect(ctl);
    }
  });
  syncSelect(ctl, true);
}

function optionList(ctl) {
  var sel = ctl.host, kw = ctl.keyword;
  var out = [];
  for (var i = 0; i < sel.options.length; i++) {
    var o = sel.options[i];
    if (o.hidden) continue;
    if (kw) {
      var hay = ((o.textContent || '') + ' ' + o.value).toLowerCase();
      if (hay.indexOf(kw) < 0) continue;
    }
    out.push(o);
  }
  return out;
}

function signatureOf(sel) {
  var s = '';
  for (var i = 0; i < sel.options.length; i++) s += sel.options[i].value + '' + (sel.options[i].textContent || '') + '';
  return s + '|' + sel.selectedIndex;
}

function renderOptions(ctl) {
  var list = ctl.panel.querySelector('.ui-select-list');
  var sel = ctl.host;
  var opts = optionList(ctl);
  if (!opts.length) {
    list.innerHTML = '<div class="ui-empty">无匹配选项</div>';
    return;
  }
  var html = '';
  opts.forEach(function (o) {
    var i = Array.prototype.indexOf.call(sel.options, o);
    html += '<div class="ui-option' + (o.selected ? ' is-selected' : '') +
      (i === ctl.active ? ' is-active' : '') + (o.disabled ? ' is-disabled' : '') +
      '" role="option" aria-selected="' + (o.selected ? 'true' : 'false') + '" data-i="' + i + '">' +
      '<span class="ui-option-text">' + esc((o.textContent || '').trim() || o.value) + '</span>' +
      (o.selected ? CHECK : '') + '</div>';
  });
  list.innerHTML = html;
  var act = list.querySelector('.ui-option.is-active');
  if (act) act.scrollIntoView({ block: 'nearest' });
}

function openSelect(ctl) {
  closeOpen();
  var sel = ctl.host;
  var panel = document.createElement('div');
  panel.className = 'ui-pop ui-select-panel';
  panel.setAttribute('role', 'listbox');
  panel.innerHTML = '<div class="ui-select-search hidden"><input type="text" autocomplete="off" placeholder="搜索选项…"></div>' +
    '<div class="ui-select-list"></div>';
  document.body.appendChild(panel);
  ctl.panel = panel;
  ctl.keyword = '';
  ctl.active = sel.selectedIndex;
  renderOptions(ctl);
  positionPanel(ctl);
  ctl.wrap.classList.add('is-open');
  ctl.trigger.setAttribute('aria-expanded', 'true');
  openCtl = ctl;
  var searchBox = panel.querySelector('.ui-select-search');
  if (optionList(ctl).length > 8) {
    searchBox.classList.remove('hidden');
    var input = searchBox.querySelector('input');
    input.focus();
    input.addEventListener('input', function () {
      ctl.keyword = this.value.trim().toLowerCase();
      var opts = optionList(ctl);
      ctl.active = opts.length ? Array.prototype.indexOf.call(sel.options, opts[0]) : -1;
      renderOptions(ctl);
      positionPanel(ctl);
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); moveActive(ctl, e.key === 'ArrowDown' ? 1 : -1); }
    });
  } else {
    ctl.trigger.focus();
  }
  panel.addEventListener('mousedown', function (e) { e.preventDefault(); });
  panel.addEventListener('click', function (e) {
    var opt = e.target.closest ? e.target.closest('.ui-option') : null;
    if (!opt) return;
    chooseOption(ctl, parseInt(opt.getAttribute('data-i'), 10));
  });
  document.addEventListener('keydown', onPanelKey, true);
}

function moveActive(ctl, delta) {
  var opts = optionList(ctl);
  if (!opts.length) return;
  var pos = -1;
  opts.forEach(function (o, i) {
    if (Array.prototype.indexOf.call(ctl.host.options, o) === ctl.active) pos = i;
  });
  var next = opts[Math.min(Math.max(pos + delta, 0), opts.length - 1)];
  ctl.active = Array.prototype.indexOf.call(ctl.host.options, next);
  renderOptions(ctl);
}

function onPanelKey(e) {
  if (!openCtl) return;
  var ctl = openCtl;
  if (['Escape', 'ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].indexOf(e.key) < 0) return;
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closePanel(ctl); ctl.trigger.focus(); return; }
  if (e.key === 'ArrowDown') { e.preventDefault(); e.stopPropagation(); moveActive(ctl, 1); return; }
  if (e.key === 'ArrowUp') { e.preventDefault(); e.stopPropagation(); moveActive(ctl, -1); return; }
  if (e.key === 'Home' || e.key === 'End') {
    var opts = optionList(ctl);
    if (opts.length) {
      e.preventDefault();
      e.stopPropagation();
      ctl.active = Array.prototype.indexOf.call(ctl.host.options, opts[e.key === 'Home' ? 0 : opts.length - 1]);
      renderOptions(ctl);
    }
    return;
  }
  if (e.key === 'Enter' || e.key === ' ') {
    if (ctl.active >= 0) { e.preventDefault(); e.stopPropagation(); chooseOption(ctl, ctl.active); }
  }
}

function chooseOption(ctl, index) {
  var sel = ctl.host;
  var opt = sel.options[index];
  if (!opt || opt.disabled) return;
  if (sel.value !== opt.value) {
    sel.value = opt.value;
    fireChange(sel);
  }
  syncSelect(ctl, true);
  closePanel(ctl);
  ctl.trigger.focus();
}

function syncSelect(ctl, force) {
  var sel = ctl.host;
  var opt = sel.options[sel.selectedIndex];
  var text = opt ? (opt.textContent || '').trim() : '';
  ctl.labelEl.textContent = text || '请选择';
  ctl.labelEl.classList.toggle('is-placeholder', !sel.value);
  var sig = signatureOf(sel);
  if (force || sig !== ctl.sig) {
    ctl.sig = sig;
    if (isOpen(ctl)) renderOptions(ctl);
  }
  var dis = !!sel.disabled;
  if (dis !== ctl.dis) {
    ctl.dis = dis;
    ctl.trigger.disabled = dis;
    ctl.wrap.classList.toggle('is-disabled', dis);
  }
}

/* ============================================================
 * 日历时间选择
 * ============================================================ */
function enhanceDate(input) {
  if (byNative && byNative.has(input)) return;
  var withTime = input.type === 'datetime-local';
  var wrap = document.createElement('div');
  wrap.className = 'ui-date' + variantClass(input);
  var trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.className = 'ui-date-trigger';
  var label = fieldLabel(input);
  if (label) trigger.setAttribute('aria-label', label);
  trigger.innerHTML = (withTime ? CLOCK_ICON : CAL_ICON) + '<span class="ui-date-label"></span>';
  input.classList.add('ui-native');
  input.tabIndex = -1;
  input.parentNode.insertBefore(wrap, input);
  wrap.appendChild(trigger);
  wrap.appendChild(input);
  var ctl = {
    kind: 'date', withTime: withTime, host: input, wrap: wrap, trigger: trigger,
    labelEl: trigger.querySelector('.ui-date-label'), panel: null,
    view: null, draft: null, lastValue: null, dis: null
  };
  register(ctl);
  trigger.addEventListener('click', function (e) {
    e.preventDefault();
    e.stopPropagation();
    if (isOpen(ctl)) closePanel(ctl); else openDate(ctl);
  });
  syncDate(ctl, true);
}

function parseDraft(input, withTime) {
  var v = input.value || '';
  var m = withTime ? /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(v) : /^(\d{4})-(\d{2})-(\d{2})/.exec(v);
  var now = new Date();
  if (m) {
    return {
      y: +m[1], mo: +m[2] - 1, d: +m[3],
      hh: m[4] ? +m[4] : (withTime ? now.getHours() : 0),
      mm: m[5] ? +m[5] : 0
    };
  }
  return null;
}

function draftToValue(ctl, dr) {
  var base = dr.y + '-' + pad(dr.mo + 1) + '-' + pad(dr.d);
  return ctl.withTime ? base + 'T' + pad(dr.hh) + ':' + pad(dr.mm) : base;
}

function fmtLabel(ctl, value) {
  if (!value) return '';
  var dr = parseDraft({ value: value }, ctl.withTime);
  if (!dr) return value;
  var text = dr.y + '-' + pad(dr.mo + 1) + '-' + pad(dr.d);
  if (ctl.withTime) text += ' ' + pad(dr.hh) + ':' + pad(dr.mm);
  return text;
}

function openDate(ctl) {
  closeOpen();
  var now = new Date();
  ctl.draft = parseDraft(ctl.host, ctl.withTime);
  ctl.view = ctl.draft ? { y: ctl.draft.y, mo: ctl.draft.mo } : { y: now.getFullYear(), mo: now.getMonth() };
  var panel = document.createElement('div');
  panel.className = 'ui-pop ui-date-panel';
  panel.innerHTML =
    '<div class="ui-cal-head">' +
    '  <button type="button" class="ui-cal-nav" data-cal="prev" aria-label="上一月">‹</button>' +
    '  <span class="ui-cal-title"></span>' +
    '  <button type="button" class="ui-cal-nav" data-cal="next" aria-label="下一月">›</button>' +
    '</div>' +
    '<div class="ui-cal-week"><i>日</i><i>一</i><i>二</i><i>三</i><i>四</i><i>五</i><i>六</i></div>' +
    '<div class="ui-cal-grid"></div>' +
    '<div class="ui-cal-foot">' +
    '  <div class="ui-cal-presets">' +
    '    <button type="button" data-preset="0">今天</button>' +
    '    <button type="button" data-preset="-1">昨天</button>' +
    '    <button type="button" data-preset="-7">7天前</button>' +
    '    <button type="button" data-preset="-30">30天前</button>' +
    '  </div>' +
    (ctl.withTime ? '<label class="ui-cal-time"><input type="time" step="60"></label>' : '') +
    '  <div class="ui-cal-actions">' +
    '    <button type="button" class="btn btn-ghost btn-sm" data-cal="clear">清空</button>' +
    (ctl.withTime ? '<button type="button" class="btn btn-ghost btn-sm" data-cal="now">此刻</button>' : '') +
    '    <button type="button" class="btn btn-primary btn-sm" data-cal="ok">确定</button>' +
    '  </div>' +
    '</div>';
  document.body.appendChild(panel);
  ctl.panel = panel;
  renderCalendar(ctl);
  positionPanel(ctl);
  ctl.wrap.classList.add('is-open');
  openCtl = ctl;
  panel.addEventListener('mousedown', function (e) { e.preventDefault(); });
  panel.addEventListener('click', function (e) { onDateClick(ctl, e); });
  var time = panel.querySelector('.ui-cal-time input');
  if (time) {
    time.value = pad(ctl.draft ? ctl.draft.hh : new Date().getHours()) + ':' + pad(ctl.draft ? ctl.draft.mm : 0);
    time.addEventListener('change', function () {
      var parts = this.value.split(':');
      if (!ctl.draft) ctl.draft = { y: ctl.view.y, mo: ctl.view.mo, d: new Date().getDate(), hh: 0, mm: 0 };
      ctl.draft.hh = parseInt(parts[0], 10) || 0;
      ctl.draft.mm = parseInt(parts[1], 10) || 0;
    });
  }
  document.addEventListener('keydown', onDateKey, true);
}

function onDateKey(e) {
  if (!openCtl) return;
  if (e.key === 'Escape') {
    var ctl = openCtl;
    e.preventDefault();
    e.stopPropagation();
    closePanel(ctl);
    ctl.trigger.focus();
  }
}

function onDateClick(ctl, e) {
  var t = e.target;
  var day = t.closest ? t.closest('[data-day]') : null;
  if (day) {
    var parts = day.getAttribute('data-day').split('-');
    if (!ctl.draft) ctl.draft = { y: +parts[0], mo: +parts[1] - 1, d: +parts[2], hh: 0, mm: 0 };
    ctl.draft.y = +parts[0]; ctl.draft.mo = +parts[1] - 1; ctl.draft.d = +parts[2];
    if (!ctl.withTime) { commitDate(ctl, draftToValue(ctl, ctl.draft)); closePanel(ctl); ctl.trigger.focus(); return; }
    renderCalendar(ctl);
    return;
  }
  var preset = t.closest ? t.closest('[data-preset]') : null;
  if (preset) {
    var offset = parseInt(preset.getAttribute('data-preset'), 10);
    var d = new Date();
    d.setDate(d.getDate() + offset);
    ctl.draft = { y: d.getFullYear(), mo: d.getMonth(), d: d.getDate(), hh: ctl.draft ? ctl.draft.hh : d.getHours(), mm: ctl.draft ? ctl.draft.mm : d.getMinutes() };
    ctl.view = { y: d.getFullYear(), mo: d.getMonth() };
    if (!ctl.withTime) { commitDate(ctl, draftToValue(ctl, ctl.draft)); closePanel(ctl); ctl.trigger.focus(); return; }
    renderCalendar(ctl);
    return;
  }
  var nav = t.closest ? t.closest('[data-cal]') : null;
  if (!nav) return;
  var act = nav.getAttribute('data-cal');
  if (act === 'prev' || act === 'next') {
    ctl.view.mo += act === 'next' ? 1 : -1;
    if (ctl.view.mo < 0) { ctl.view.mo = 11; ctl.view.y--; }
    if (ctl.view.mo > 11) { ctl.view.mo = 0; ctl.view.y++; }
    renderCalendar(ctl);
  } else if (act === 'clear') {
    commitDate(ctl, '');
    closePanel(ctl);
    ctl.trigger.focus();
  } else if (act === 'now') {
    var n = new Date();
    ctl.draft = { y: n.getFullYear(), mo: n.getMonth(), d: n.getDate(), hh: n.getHours(), mm: n.getMinutes() };
    ctl.view = { y: n.getFullYear(), mo: n.getMonth() };
    var ti = ctl.panel.querySelector('.ui-cal-time input');
    if (ti) ti.value = pad(ctl.draft.hh) + ':' + pad(ctl.draft.mm);
    renderCalendar(ctl);
  } else if (act === 'ok') {
    if (ctl.draft) commitDate(ctl, draftToValue(ctl, ctl.draft));
    closePanel(ctl);
    ctl.trigger.focus();
  }
}

function renderCalendar(ctl) {
  var panel = ctl.panel;
  var y = ctl.view.y, mo = ctl.view.mo;
  panel.querySelector('.ui-cal-title').textContent = y + ' 年 ' + (mo + 1) + ' 月';
  var first = new Date(y, mo, 1);
  var days = new Date(y, mo + 1, 0).getDate();
  var today = new Date();
  var selDay = ctl.draft ? ctl.draft.y + '-' + pad(ctl.draft.mo + 1) + '-' + pad(ctl.draft.d) : '';
  var html = '';
  for (var i = 0; i < first.getDay(); i++) html += '<i class="ui-cal-cell is-blank"></i>';
  for (var d = 1; d <= days; d++) {
    var key = y + '-' + pad(mo + 1) + '-' + pad(d);
    var isToday = today.getFullYear() === y && today.getMonth() === mo && today.getDate() === d;
    html += '<button type="button" class="ui-cal-cell' + (key === selDay ? ' is-sel' : '') +
      (isToday ? ' is-today' : '') + '" data-day="' + key + '">' + d + '</button>';
  }
  panel.querySelector('.ui-cal-grid').innerHTML = html;
}

function commitDate(ctl, value) {
  var input = ctl.host;
  if (input.value === value) { syncDate(ctl, true); return; }
  input.value = value;
  fireChange(input);
  syncDate(ctl, true);
}

function syncDate(ctl, force) {
  var input = ctl.host;
  if (force || ctl.lastValue !== input.value) {
    ctl.lastValue = input.value;
    var text = fmtLabel(ctl, input.value);
    ctl.labelEl.textContent = text || '';
    ctl.labelEl.classList.toggle('is-placeholder', !text);
    if (!text) ctl.labelEl.setAttribute('data-placeholder', ctl.withTime ? '选择时间' : '选择日期');
    else ctl.labelEl.removeAttribute('data-placeholder');
  }
  var dis = !!input.disabled;
  if (dis !== ctl.dis) {
    ctl.dis = dis;
    ctl.trigger.disabled = dis;
    ctl.wrap.classList.toggle('is-disabled', dis);
  }
}

/* ============================================================
 * 浮层定位与关闭
 * ============================================================ */
function positionPanel(ctl) {
  var panel = ctl.panel;
  if (!panel) return;
  var rect = ctl.trigger.getBoundingClientRect();
  var pw = Math.max(rect.width, ctl.kind === 'date' ? 288 : 168);
  panel.style.width = ctl.kind === 'date' ? pw + 'px' : '';
  panel.style.minWidth = Math.round(rect.width) + 'px';
  var ph = panel.offsetHeight;
  var gap = 6;
  var top = rect.bottom + gap;
  if (top + ph > window.innerHeight - 8 && rect.top - gap - ph > 8) top = rect.top - gap - ph;
  var left = Math.min(rect.left, window.innerWidth - pw - 8);
  panel.style.top = Math.max(8, top) + 'px';
  panel.style.left = Math.max(8, left) + 'px';
}

function closePanel(ctl) {
  if (!isOpen(ctl)) return;
  if (ctl.panel.parentNode) ctl.panel.parentNode.removeChild(ctl.panel);
  ctl.panel = null;
  ctl.wrap.classList.remove('is-open');
  if (ctl.trigger.getAttribute('aria-expanded')) ctl.trigger.setAttribute('aria-expanded', 'false');
  if (openCtl === ctl) {
    openCtl = null;
    document.removeEventListener('keydown', onPanelKey, true);
    document.removeEventListener('keydown', onDateKey, true);
  }
}

function closeOpen() { if (openCtl) closePanel(openCtl); }

document.addEventListener('click', function (e) {
  if (!openCtl) return;
  // 点击若落在任一日历/下拉浮层内（含被重渲染后已脱离文档流的元素），交由浮层自身处理
  var target = e.target;
  if (target && target.closest && target.closest('.ui-pop')) return;
  if (target && !target.isConnected && openCtl.panel) return;
  closePanel(openCtl);
});
window.addEventListener('resize', function () { if (openCtl) positionPanel(openCtl); });
window.addEventListener('scroll', function () { if (openCtl) positionPanel(openCtl); }, true);

/* ============================================================
 * 扫描与自动跟随
 * ============================================================ */
var DATE_SELECTOR = 'input[type="date"], input[type="datetime-local"]';

function enhanceControls(root) {
  if (!root || !root.querySelectorAll) return;
  var selects = root.matches && root.matches('select') ? [root] : root.querySelectorAll('select');
  Array.prototype.forEach.call(selects, enhanceSelect);
  var dates = root.matches && root.matches(DATE_SELECTOR) ? [root] : root.querySelectorAll(DATE_SELECTOR);
  Array.prototype.forEach.call(dates, enhanceDate);
}

function syncAll() {
  for (var i = controls.length - 1; i >= 0; i--) {
    var c = controls[i];
    if (!c.host.isConnected || !c.wrap.isConnected) { controls.splice(i, 1); continue; }
    if (c.kind === 'select') syncSelect(c); else syncDate(c);
  }
}

var scheduled = false;
function scheduleEnhance() {
  if (scheduled) return;
  scheduled = true;
  (window.requestAnimationFrame || function (f) { setTimeout(f, 16); })(function () {
    scheduled = false;
    enhanceControls(document.body);
    syncAll();
  });
}

if (typeof MutationObserver === 'function') {
  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      if (records[i].addedNodes && records[i].addedNodes.length) { scheduleEnhance(); return; }
    }
  }).observe(document.body, { childList: true, subtree: true });
}

document.addEventListener('click', syncAll, true);
setInterval(syncAll, 250);

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', function () { enhanceControls(document.body); });
} else {
  enhanceControls(document.body);
}

export { enhanceControls, syncAll };
