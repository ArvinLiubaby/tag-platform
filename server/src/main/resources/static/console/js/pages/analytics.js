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
      '        <button class="btn btn-ghost" id="rec-reset">重置</button>' +
      '        <button class="btn btn-primary" id="rec-search">查询</button>' +
      '      </div>' +
      '    </div>' +
      '  </div>' +
      '  <div id="records-table"></div>' +
      '  <div class="pager" id="records-pager"></div>' +
      '</div>';
    $('#rec-search').addEventListener('click', function () { recordsState.page = 1; loadRecords(); });
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
            '<td class="cell-wrap"><b>' + esc(r.tag_value) + '</b></td>' +
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
      '    <div class="card-actions"><span class="muted">实时读取 tag_value_latest</span></div></div>' +
      '  <div class="card-body">' +
      '    <div class="filter-grid">' +
      '      <div class="field"><span>租户 <i class="req">*</i></span><input id="pf-tenant" value="' + esc(workTenantId() || 'risk_control') + '"></div>' +
      '      <div class="field"><span>对象类型 <i class="req">*</i></span>' +
      '        <select id="pf-otype"><option value="driver">driver 司机</option>' +
      '        <option value="vehicle">vehicle 车辆</option></select></div>' +
      '      <div class="field"><span>对象 ID <i class="req">*</i></span><input id="pf-oid" value="DRV85001" placeholder="DRV85001"></div>' +
      '      <div class="field"><span>包含待审核</span>' +
      '        <label class="check-line"><input id="pf-pending" type="checkbox" checked>' +
      '        <span>显示 pending_audit 值</span></label></div>' +
      '    </div>' +
      '    <div class="filter-actions">' +
      '      <div class="quick-row"><span>示例</span>' +
      '        <button type="button" class="chip" data-example="driver|DRV85001">司机 DRV85001</button>' +
      '        <button type="button" class="chip" data-example="vehicle|苏A88801">车辆 苏A88801</button>' +
      '      </div>' +
      '      <button class="btn btn-primary" id="pf-search">查询画像</button>' +
      '    </div>' +
      '  </div>' +
      '  <div id="profile-result">' + emptyState('尚未查询', '输入对象 ID，或点击示例快速查看对象当前标签画像') + '</div>' +
      '</div>';
    $('#pf-search').addEventListener('click', loadProfile);
    $('#pf-otype').addEventListener('change', function () {
      $('#pf-oid').placeholder = this.value === 'driver' ? 'DRV85001' : '苏A88801';
    });
    $('#pf-oid').addEventListener('keydown', function (e) { if (e.key === 'Enter') loadProfile(); });
    $('#pf-tenant').addEventListener('keydown', function (e) { if (e.key === 'Enter') loadProfile(); });
    document.querySelectorAll('[data-example]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var parts = btn.getAttribute('data-example').split('|');
        $('#pf-otype').value = parts[0];
        $('#pf-tenant').value = workTenantId() || 'risk_control';
        $('#pf-oid').value = parts[1];
        $('#pf-oid').placeholder = parts[0] === 'driver' ? 'DRV85001' : '苏A88801';
        loadProfile();
      });
    });
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

    host.innerHTML =
      '<div class="profile-head">' +
      '  <div class="profile-avatar">' + esc((p.object_type || '?').charAt(0).toUpperCase()) + '</div>' +
      '  <div><div class="profile-id">' + esc(p.object_id) + '</div>' +
      '  <div class="profile-meta">' + esc(p.object_type) + ' · 租户 ' + esc(p.tenant_id) +
      '    · 共 ' + tags.length + ' 个标签</div></div>' +
      '  <div class="profile-stats">' +
      '    <div class="pstat"><b>' + counts.value + '</b><span>有值</span></div>' +
      '    <div class="pstat pending"><b>' + counts.pending + '</b><span>待审核</span></div>' +
      '    <div class="pstat none"><b>' + counts.none + '</b><span>未采集</span></div>' +
      '  </div>' +
      '</div>' +
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
      (t.value_type ? ' · ' + esc(t.value_type) : '') + (t.value_multiple ? ' · 多选' : '') + '</div>' +
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
  function docCurl(method, path, query, body) {
    var lines = [
      'BASE_URL="' + origin + '"',
      'APP_KEY="AK_RC_8f2a91"',
      'SECRET="rc-secret-8f2a91-demo"',
      'METHOD="' + method + '"',
      'PATH_="' + path + '"',
      'QUERY="' + (query || '') + '"',
      body ? "BODY='" + body + "'" : 'BODY=""',
      'TS=$(date +%s%3N); NONCE=$(openssl rand -hex 16)',
      'SIGN=$(printf "%s\\n%s\\n%s\\n%s\\n%s\\n%s" "$METHOD" "$PATH_" "$QUERY" "$BODY" "$TS" "$NONCE" \\',
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
    var t = (text || '').trim();
    if (t.charAt(0) === '{' || t.charAt(0) === '[') {
      try { JSON.parse(t); return 'json'; } catch (e) { /* not json */ }
    }
    if (/(^|\n)\s*(curl|BASE_URL=|APP_KEY=|SECRET=|PATH_=)/.test(text || '')) return 'bash';
    return 'text';
  }

  function highlightJson(text) {
    var out = '';
    var i = 0;
    while (i < text.length) {
      var ch = text.charAt(i);
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
      (api.key === 'auth' ? '' : '<button type="button" class="btn btn-ghost btn-sm" data-copy-path="' + esc(api.path) + '">复制路径</button>') +
      '  </div>' +
      '</div>' +
      '<div class="docs-detail-body">' + api.detail + '</div>';
    card.querySelectorAll('[data-copy-path]').forEach(function (btn) {
      btn.addEventListener('click', function () { copyText(btn.getAttribute('data-copy-path'), '路径已复制'); });
    });
    bindDocCopy(card);
  }

  function renderDocs() {
    var origin = location.origin || 'http://localhost:8080';
    var writePath = '/api/v1/open/tag-values/write';
    var queryPath = '/api/v1/open/objects/profile/combined';

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

    var writeFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">biz_request_id</td><td>是</td><td>幂等键，全局唯一；24 小时内重复提交回放首次响应</td></tr>' +
      '<tr><td class="mono">items</td><td>是</td><td>1~1000 条；任一校验失败整批拒绝，message 指明第几条及原因</td></tr>' +
      '<tr><td class="mono">object_type / object_id</td><td>是</td><td>已注册对象类型与 ID（司机 <span class="code-inline">DRV…</span>，车辆为车牌号）</td></tr>' +
      '<tr><td class="mono">tag_code</td><td>是</td><td>在线、可写且适用于该对象的标签</td></tr>' +
      '<tr><td class="mono">tag_value</td><td>是</td><td>一律传字符串：<span class="code-inline">"12.5"</span> / <span class="code-inline">"true"</span> / 枚举值，需通过值域校验</td></tr>' +
      '<tr><td class="mono">operator</td><td>是</td><td><span class="code-inline">operator_id</span> 必填；<span class="code-inline">operator_type</span> ∈ human / system / algo</td></tr>' +
      '<tr><td class="mono">remark</td><td>敏感标签</td><td>≤2048 字符；need_value_audit=true 的标签必填</td></tr>' +
      '<tr><td class="mono">biz_time / operate_time</td><td>否</td><td>yyyy-MM-dd HH:mm:ss；缺省为服务端当前时间</td></tr>' +
      '<tr><td class="mono">source / confidence</td><td>否</td><td>来源标识 ≤64 字符；置信度 高/中/低</td></tr>' +
      '<tr><td class="mono">evidence_url / ext_json</td><td>否</td><td>证据链接 ≤512 字符；扩展 JSON ≤2048 字符</td></tr>' +
      '</tbody></table>';

    var writeBodyPretty = [
      '{',
      '  "biz_request_id": "biz-20261021-0001",',
      '  "items": [{',
      '    "object_type": "driver",',
      '    "object_id": "DRV85001",',
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

    var queryParams =
      '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">driver_id</td><td>二选一</td><td>司机对象 ID，如 <span class="code-inline">DRV85001</span></td></tr>' +
      '<tr><td class="mono">vehicle_plate</td><td>二选一</td><td>车牌号（非 VIN），如 <span class="code-inline">苏A88801</span></td></tr>' +
      '<tr><td class="mono">include_pending</td><td>否</td><td>默认 false；true 时附带待审核值</td></tr>' +
      '<tr><td class="mono">include_empty</td><td>否</td><td>默认 true；false 时仅返回有值标签</td></tr>' +
      '<tr><td class="mono">tag_codes</td><td>否</td><td>逗号分隔标签码；省略时返回该对象全部可见在线标签</td></tr>' +
      '</tbody></table>';

    var queryCurl = [
      'BASE_URL="' + origin + '"',
      'APP_KEY="AK_RC_8f2a91"',
      'SECRET="rc-secret-8f2a91-demo"',
      'PATH_="' + queryPath + '"',
      '# 签名用解码原值，key 按字典序：driver_id → vehicle_plate',
      'QUERY="driver_id=DRV85001&vehicle_plate=苏A88801"',
      '',
      'TS=$(date +%s%3N)',
      'NONCE=$(openssl rand -hex 16)',
      "SIGNATURE=$(printf '%s\\n%s\\n%s\\n%s\\n%s\\n%s' GET \"$PATH_\" \"$QUERY\" \"\" \"$TS\" \"$NONCE\" \\",
      '  | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256)',
      '',
      'curl -s -G "$BASE_URL$PATH_" \\',
      '  --data-urlencode "driver_id=DRV85001" \\',
      '  --data-urlencode "vehicle_plate=苏A88801" \\',
      '  -H "X-App-Key: $APP_KEY" -H "X-Timestamp: $TS" \\',
      '  -H "X-Nonce: $NONCE" -H "X-Signature: $SIGNATURE"'
    ].join('\n');

    var queryResp = [
      '{',
      '  "code": 0,',
      '  "message": "ok",',
      '  "request_id": "…",',
      '  "data": {',
      '    "tenant_id": "risk_control",',
      '    "driver": {',
      '      "object_type": "driver",',
      '      "object_id": "DRV85001",',
      '      "tags": [',
      '        { "tag_code": "driver_relation", "tag_name": "司机关系", "tag_value": "公司员工",',
      '          "value_type": "enum", "status": "effective", "source_mode": "hybrid", "need_value_audit": false },',
      '        { "tag_code": "social_tendency", "tag_name": "社交倾向", "tag_value": null,',
      '          "value_type": "enum", "status": "none", "source_mode": "hybrid", "need_value_audit": true }',
      '      ]',
      '    },',
      '    "vehicle": {',
      '      "object_type": "vehicle",',
      '      "object_id": "苏A88801",',
      '      "tags": [',
      '        { "tag_code": "cargo_type", "tag_name": "货物类型", "tag_value": "普货",',
      '          "value_type": "enum", "status": "effective", "source_mode": "hybrid", "need_value_audit": false }',
      '      ]',
      '    },',
      '    "total": 38',
      '  }',
      '}'
    ].join('\n');

    var writeDetail =
      '<div class="doc-note">平台只追加打标流水（<span class="code-inline">tag_value_history</span>）并投递 Outbox 事件；画像数据源由外部写入程序维护，<b>受理成功不代表画像立即可查</b>。</div>' +
      docSection('请求体字段', writeFields) +
      docSection('请求体示例', docCode('doc-write-body', writeBodyPretty)) +
      docSection('调用示例（curl，含签名）', docCode('doc-write-curl', writeCurl)) +
      docSection('响应示例', docCode('doc-write-resp', writeResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>幂等</b>：相同 <span class="code-inline">biz_request_id</span> 在 24 小时内重复提交回放首次响应；处理中返回 3004。</li>' +
        '<li><b>敏感标签</b>：<span class="code-inline">need_value_audit=true</span> 时 <span class="code-inline">remark</span> 必填（否则 3008），流水状态为 <span class="code-inline">pending_audit</span>。</li>' +
        '<li><b>频控</b>：IP 100 QPS、操作员 20 QPS、同对象同标签 1 QPS，超限返回 HTTP 429。</li>' +
        '<li><b>常见错误</b>：3001 标签不存在 / 3002 未上线 / 3011 标签与对象不匹配 / 3012 写入开关已关闭。</li>' +
        '</ul>');

    var queryDetail =
      '<div class="doc-note">一次返回「司机 + 车辆」的标签当前值（数据源 <span class="code-inline">tag_value_latest</span>，由外部写入程序维护）；默认排除 <span class="code-inline">pending_audit</span> 与已过期值。</div>' +
      docSection('Query 参数', queryParams) +
      docSection('调用示例（curl，含签名）', docCode('doc-query-curl', queryCurl)) +
      docSection('响应示例', docCode('doc-query-resp', queryResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>至少一个对象</b>：<span class="code-inline">driver_id</span> 与 <span class="code-inline">vehicle_plate</span> 至少传一个，否则返回 2001。</li>' +
        '<li><b>占位</b>：<span class="code-inline">include_empty=true</span>（默认）时，无值标签返回 <span class="code-inline">status=none</span>、<span class="code-inline">tag_value=null</span>，可直接渲染待打标清单。</li>' +
        '<li><b>待审核</b>：默认 <span class="code-inline">include_pending=false</span>；置 true 才返回 <span class="code-inline">pending_audit</span> 值，勿用于自动决策。</li>' +
        '<li><b>标签过滤</b>：不传 <span class="code-inline">tag_codes</span> 返回全部可见在线标签；传清单 32 个标签码可精确取 32 个特征值。</li>' +
        '<li><b>中文参数</b>：<span class="code-inline">vehicle_plate</span> 如「苏A88801」在 URL 中需百分号编码，签名仍用解码原值。</li>' +
        '</ul>');

    var tagsPath = '/api/v1/open/tags';
    var tasksPath = '/api/v1/open/tasks';
    var taskItemsPath = '/api/v1/open/tasks/{task_id}/items';
    var taskProgressPath = '/api/v1/open/tasks/{task_id}/progress';
    var profilePath = '/api/v1/open/objects/{object_id}/profile';
    var recordsPath = '/api/v1/open/operators/{operator_id}/tag-records';
    var deletePath = '/api/v1/open/tag-values/delete';

    var tagsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>类型</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">tag_code / tag_name</td><td>string</td><td>标签编码与名称</td></tr>' +
      '<tr><td class="mono">category_code</td><td>string</td><td>标签类目</td></tr>' +
      '<tr><td class="mono">value_type</td><td>string</td><td>boolean / number / enum / string / date</td></tr>' +
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
      '<tr><td class="mono">sensitivity</td><td>string</td><td>L1 / L2 / L3</td></tr>' +
      '<tr><td class="mono">status / version</td><td>string / int</td><td>仅返回 online；version 为定义版本号</td></tr>' +
      '<tr><td class="mono">updated_at</td><td>datetime</td><td>定义最近更新时间</td></tr>' +
      '</tbody></table>';

    var tagsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "tags": [{',
      '      "tag_code": "driver_relation",',
      '      "tag_name": "司机关系",',
      '      "category_code": "driver_relation",',
      '      "value_type": "enum",',
      '      "options": ["公司员工", "第三方派遣", "承包或承租"],',
      '      "value_multiple": false,',
      '      "tag_type": "fact",',
      '      "derived": false,',
      '      "object_type": "driver",',
      '      "source_mode": "manual",',
      '      "need_value_audit": false,',
      '      "writable": true,',
      '      "sensitivity": "L2",',
      '      "status": "online",',
      '      "version": 3',
      '    }],',
      '    "total": 34',
      '  }',
      '}'
    ].join('\n');

    var tagsDetail =
      '<div class="doc-note">返回当前凭证租户可见的 <b>online</b> 标签（公开标签 + 本租户标签），包含原始标签与组合标签；对接方应以此接口作为字段字典的事实源。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">category_code</td><td>否</td><td>按类目过滤</td></tr>' +
        '<tr><td class="mono">object_type</td><td>否</td><td>按对象类型过滤（driver / vehicle / fleet）</td></tr>' +
        '<tr><td class="mono">source_mode</td><td>否</td><td>按来源类型过滤</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', tagsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-tags-curl', docCurl('GET', tagsPath, 'object_type=driver', ''))) +
      docSection('响应示例', docCode('doc-tags-resp', tagsResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>只读接口</b>：无需请求体，签名规则同写接口（GET 的 RAW_BODY 为空串）。</li>' +
        '<li><b>写入前置校验</b>：只有 <span class="code-inline">writable=true</span> 且 <span class="code-inline">object_type</span> 匹配的标签才能推送，组合标签一律不可写。</li>' +
        '<li><b>值域</b>：<span class="code-inline">options</span> 可直接用于下拉；写入时仍会按 value_range 严格校验。</li>' +
        '</ul>');

    var tasksFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">task_code / title</td><td>任务编号与标题</td></tr>' +
      '<tr><td class="mono">object_type</td><td>任务对象类型</td></tr>' +
      '<tr><td class="mono">status</td><td>draft / published / in_progress / reviewing / completed / cancelled</td></tr>' +
      '<tr><td class="mono">priority / assignment_mode</td><td>优先级；pool（任务池）/ assign（指派）</td></tr>' +
      '<tr><td class="mono">total_items / completed_items / rejected_items</td><td>对象总数 / 已完成（通过 + 跳过）/ 驳回数</td></tr>' +
      '<tr><td class="mono">due_at / published_at / completed_at</td><td>截止、发布与完成时间</td></tr>' +
      '<tr><td class="mono">stats</td><td>pending / claimed / in_progress / submitted / approved / rejected / skipped 细分计数</td></tr>' +
      '<tr><td class="mono">tags</td><td>任务要求的标签列表（tag_code / required）</td></tr>' +
      '</tbody></table>';

    var tasksResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "page": 1, "size": 20, "total": 2,',
      '    "records": [{',
      '      "task": {',
      '        "id": 12, "task_code": "TK-20261029-88992", "title": "10 月客服补标 · 华东车队",',
      '        "object_type": "driver", "status": "in_progress", "priority": "high",',
      '        "assignment_mode": "pool", "total_items": 120, "completed_items": 86,',
      '        "rejected_items": 2, "due_at": "2026-11-05 18:00:00"',
      '      },',
      '      "tags": [{"tag_code": "driver_relation", "required": true}],',
      '      "stats": {"total": 120, "pending": 30, "claimed": 2, "in_progress": 0,',
      '                  "submitted": 0, "approved": 86, "rejected": 2, "skipped": 0}',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var tasksDetail =
      '<div class="doc-note">查询当前凭证租户下的打标任务及进度，用于外部打标系统拉取待办与对账。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">status</td><td>否</td><td>按任务状态过滤</td></tr>' +
        '<tr><td class="mono">page / size</td><td>否</td><td>默认 1 / 20，size 上限 100</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', tasksFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-tasks-curl',
        docCurl('GET', tasksPath, 'status=published&page=1&size=20', ''))) +
      docSection('响应示例', docCode('doc-tasks-resp', tasksResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>租户隔离</b>：只返回 AppKey 绑定租户的任务，跨租户任务一律 4003。</li>' +
        '<li><b>进度口径</b>：completed_items = approved + skipped；审核中的任务会进入 <span class="code-inline">reviewing</span>。</li>' +
        '<li><b>详情/对象项</b>：任务详情见 <span class="code-inline">GET /api/v1/open/tasks/{task_id}</span>，对象项分页见 <span class="code-inline">GET /api/v1/open/tasks/{task_id}/items</span>。</li>' +
        '</ul>');

    var itemsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[].item</td><td>object_id、status（pending/claimed/in_progress/submitted/approved/rejected/skipped）、assignee_name、operator_name、submitted_at、reviewed_at</td></tr>' +
      '<tr><td class="mono">records[].values</td><td>该对象在任务内已写入的标签值：tag_code / tag_value / status</td></tr>' +
      '<tr><td class="mono">stats</td><td>任务级进度统计</td></tr>' +
      '</tbody></table>';

    var itemsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "page": 1, "size": 50, "total": 120,',
      '    "stats": {"total": 120, "pending": 30, "approved": 86, "rejected": 2, "skipped": 0},',
      '    "records": [{',
      '      "item": {"object_id": "DRV85001", "status": "approved", "operator_name": "外部打标系统",',
      '               "submitted_at": "2026-10-30 10:20:00", "reviewed_at": "2026-10-30 10:20:00"},',
      '      "values": [{"tag_code": "driver_relation", "tag_value": "第三方派遣", "status": "effective"}]',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var itemsDetail =
      '<div class="doc-note">按任务分页拉取对象项与已写入值，外部系统据此决定哪些对象还需要打标。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">status</td><td>否</td><td>按对象项状态过滤</td></tr>' +
        '<tr><td class="mono">page / size</td><td>否</td><td>默认 1 / 50，size 上限 500</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', itemsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-items-curl',
        docCurl('GET', taskItemsPath.replace('{task_id}', '12'), 'page=1&size=50', ''))) +
      docSection('响应示例', docCode('doc-items-resp', itemsResp));

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
      '<div class="doc-note">外部打标系统完成某对象后实时回传进度；任务状态与统计随之更新，任务全部完成后自动置为 completed。</div>' +
      docSection('请求体', '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">items</td><td>是</td><td>1~100 个对象；同一请求内 object_id 去重</td></tr>' +
        '<tr><td class="mono">items[].object_id</td><td>是</td><td>任务内已存在的对象 ID</td></tr>' +
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
        '<li><b>标签值仍走写入接口</b>：进度回传只推进任务状态，标签值请通过 <span class="code-inline">POST /api/v1/open/tag-values/write</span> 写入。</li>' +
        '</ul>');

    var profileFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">tags[].tag_code / tag_name / tag_value</td><td>标签与当前值（无值占位时 status=none）</td></tr>' +
      '<tr><td class="mono">tags[].status</td><td>effective / pending_audit / revoked / expired / none</td></tr>' +
      '<tr><td class="mono">tags[].derived</td><td>true 表示组合标签，值为查询期实时计算</td></tr>' +
      '<tr><td class="mono">tags[].biz_time / operate_time</td><td>业务时间与操作时间（组合标签为空）</td></tr>' +
      '</tbody></table>';

    var profileResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",',
      '    "tags": [{',
      '      "tag_code": "driver_relation", "tag_name": "司机关系", "tag_value": "第三方派遣",',
      '      "status": "effective", "derived": false, "biz_time": "2026-10-16 12:15:43"',
      '    }, {',
      '      "tag_code": "risk_combo_high", "tag_name": "高风险驾驶组合", "tag_value": "true",',
      '      "status": "effective", "derived": true',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var profileDetail =
      '<div class="doc-note">按单个对象读取当前标签值（tag_value_latest + 组合标签查询期计算），用于实时决策与页面展示。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">object_type</td><td>是</td><td>driver / vehicle / fleet</td></tr>' +
        '<tr><td class="mono">include_pending</td><td>否</td><td>默认 false；true 时返回待审核值，勿用于自动决策</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', profileFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-profile-curl',
        docCurl('GET', profilePath.replace('{object_id}', 'DRV85001'), 'object_type=driver&include_pending=false', ''))) +
      docSection('响应示例', docCode('doc-single-profile-resp', profileResp)) +
      docSection('要点', '<ul class="doc-list">' +
        '<li><b>组合标签</b>：<span class="code-inline">derived=true</span> 的项由平台实时计算，不落 latest。</li>' +
        '<li><b>批量取数</b>：司机 + 车辆一起取用 <span class="code-inline">/objects/profile/combined</span>，避免多次往返。</li>' +
        '</ul>');

    var recordsFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">records[]</td><td>tenant_id / object_type / object_id / tag_code / tag_value / status / biz_time / operate_time / operator_* / source / confidence</td></tr>' +
      '<tr><td class="mono">total / page / size</td><td>分页信息</td></tr>' +
      '</tbody></table>';

    var recordsResp = [
      '{',
      '  "code": 0,',
      '  "data": {',
      '    "operator_id": "OP_SERVICE01",',
      '    "page": 1, "size": 20, "total": 1,',
      '    "records": [{',
      '      "tenant_id": "risk_control", "object_type": "driver", "object_id": "DRV85001",',
      '      "tag_code": "driver_relation", "tag_value": "第三方派遣", "status": "effective",',
      '      "biz_time": "2026-10-16 12:15:43", "operate_time": "2026-10-16 12:15:43",',
      '      "operator_id": "OP_SERVICE01", "operator_name": "客服专员", "source": "客服手工标注"',
      '    }]',
      '  }',
      '}'
    ].join('\n');

    var recordsDetail =
      '<div class="doc-note">按操作员查询其打标流水，用于外部系统对账与审计。</div>' +
      docSection('Query 参数', '<table class="doc-table"><thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
        '<tr><td class="mono">object_type / object_id / tag_code</td><td>否</td><td>按对象或标签过滤</td></tr>' +
        '<tr><td class="mono">from / to</td><td>否</td><td>操作时间范围 yyyy-MM-dd HH:mm:ss</td></tr>' +
        '<tr><td class="mono">page / size</td><td>否</td><td>默认 1 / 20</td></tr>' +
        '</tbody></table>') +
      docSection('响应字段', recordsFields) +
      docSection('调用示例（curl，含签名）', docCode('doc-records-curl',
        docCurl('GET', recordsPath.replace('{operator_id}', 'OP_SERVICE01'), 'page=1&size=20', ''))) +
      docSection('响应示例', docCode('doc-records-resp', recordsResp)) +
      docSection('要点', '<ul class="doc-list"><li>路径中的 operator_id 为写入时提交的操作员 ID。</li>' +
        '<li>流水为追加式存储（tag_value_history），作废也会产生新流水。</li></ul>');

    var deleteFields =
      '<table class="doc-table"><thead><tr><th>字段</th><th>必填</th><th>说明</th></tr></thead><tbody>' +
      '<tr><td class="mono">biz_request_id</td><td>是</td><td>幂等键</td></tr>' +
      '<tr><td class="mono">items[].object_type / object_id / tag_code</td><td>是</td><td>定位要作废的标签值</td></tr>' +
      '<tr><td class="mono">items[].operator</td><td>是</td><td>同写入接口的 operator 结构</td></tr>' +
      '<tr><td class="mono">items[].remark</td><td>否</td><td>作废原因</td></tr>' +
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
        '<li>作废只追加流水并更新状态，不物理删除历史。</li>' +
        '<li>组合标签不可作废（本身不落 latest）。</li>' +
        '</ul>');

    var authDetail =
      '<div class="doc-note">所有开放接口使用 <b>HMAC-SHA256</b> 签名；租户由 AppKey 凭证决定，无需在 URL 中传租户。响应统一为 <span class="code-inline">{code, message, data, request_id}</span>，业务失败 code≠0。</div>' +
      docSection('公共请求头', headerTable) +
      docSection('待签名字符串', docCode('doc-sign', signText) +
        '<p class="doc-tip">CANONICAL_QUERY：query 按 key 字典序、同 key 按 value 字典序，使用<b>URL 解码后的原值</b>；实际请求 URL 使用百分号编码。RAW_BODY 必须与实际发送的字节完全一致，不要重新格式化 JSON；GET 请求 RAW_BODY 为空串。</p>') +
      docSection('最小调用示例（GET /tags）', docCode('doc-auth-curl', docCurl('GET', tagsPath, 'object_type=driver', ''))) +
      docSection('常见错误码', '<ul class="doc-list">' +
        '<li><b>1003</b> 签名不匹配 · <b>1005</b> nonce 重放 · <b>1009</b> 会话/凭证无效</li>' +
        '<li><b>2001</b> 参数缺失 · <b>2002</b> 参数校验失败 · <b>3001</b> 标签不存在 · <b>3012</b> 标签不可写</li>' +
        '<li><b>4003</b> 越权访问其他租户 · <b>5001</b> 系统内部错误（可重试并携带原 request_id 反馈）</li>' +
        '</ul>') +
      docSection('幂等与限流', '<ul class="doc-list">' +
        '<li>写入/作废需带 <span class="code-inline">biz_request_id</span>，24 小时内重复提交回放首次响应。</li>' +
        '<li>IP 与操作员维度有秒级频控；批量写入单次 ≤1000 条，进度回传单次 ≤100 个对象。</li>' +
        '</ul>');

    docsApis = [
      { key: 'auth', method: 'sign', path: '鉴权与签名', name: 'HMAC-SHA256', group: '快速开始',
        desc: '公共请求头、待签名字符串与错误码', detail: authDetail },
      { key: 'tags', method: 'GET', path: tagsPath, name: '标签元数据', group: '快速开始',
        desc: '标签字典：类型、值域、适用对象、可写性与组合标记', detail: tagsDetail },
      { key: 'write', method: 'POST', path: writePath, name: '标签推送', group: '标签写入',
        desc: '批量写入标签值（1~1000 条/批，整批校验）', detail: writeDetail },
      { key: 'delete', method: 'POST', path: deletePath, name: '标签值作废', group: '标签写入',
        desc: '作废对象的标签值（追加流水，不物理删除）', detail: deleteDetail },
      { key: 'profile', method: 'GET', path: profilePath, name: '对象画像（单对象）', group: '画像查询',
        desc: '读取单个对象的当前标签值（含组合标签实时计算）', detail: profileDetail },
      { key: 'query', method: 'GET', path: queryPath, name: '画像查询（司机+车辆）', group: '画像查询',
        desc: '按「司机 + 车辆」读取标签当前值（含占位）', detail: queryDetail },
      { key: 'tasks', method: 'GET', path: tasksPath, name: '任务列表', group: '任务协作',
        desc: '本租户打标任务与进度统计（分页）', detail: tasksDetail },
      { key: 'task-items', method: 'GET', path: taskItemsPath, name: '任务对象项', group: '任务协作',
        desc: '按任务分页拉取对象与已写入值', detail: itemsDetail },
      { key: 'task-progress', method: 'POST', path: taskProgressPath, name: '任务进度回传', group: '任务协作',
        desc: '外部打标系统回传对象完成/跳过，任务进度实时同步', detail: progressDetail },
      { key: 'records', method: 'GET', path: recordsPath, name: '操作员打标记录', group: '对账审计',
        desc: '按操作员查询打标流水，用于对账与审计', detail: recordsDetail }
    ];

    var deepApi = /[?&]api=([^&]+)/.exec(location.hash || '');
    if (deepApi) {
      var wanted = decodeURIComponent(deepApi[1]);
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
      '        <p>实时统计外部租户的接口调用、打标写入与标签查询</p>' +
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
      '      <div class="dash-card-head"><div><h3>调用趋势</h3><span>调用量 / 打标 / 查询</span></div></div>' +
      '      <div class="dash-card-body" id="dash-trend">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-endpoints">' +
      '      <div class="dash-card-head"><div><h3>接口调用分布</h3><span>按 GET / POST 端点</span></div></div>' +
      '      <div class="dash-card-body" id="dash-endpoints">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-top">' +
      '      <div class="dash-card-head"><div><h3>打标 TOP</h3><span>标签写入次数</span></div></div>' +
      '      <div class="dash-card-body" id="dash-top-write">' + dashLoadingBlock() + '</div>' +
      '    </section>' +
      '    <section class="card dash-card dash-top">' +
      '      <div class="dash-card-head"><div><h3>查询 TOP</h3><span>标签被查询次数</span></div></div>' +
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
      { label: '接口调用', value: fmtNum(k.total_calls), hint: '范围累计调用量', tone: 'blue', icon: 'pulse' },
      { label: '打标次数', value: fmtNum(k.total_writes),
        hint: k.total_deletes ? '另作废 ' + fmtNum(k.total_deletes) + ' 条' : '标签写入条目', tone: 'green', icon: 'tag' },
      { label: '标签查询', value: fmtNum(k.total_queries), hint: '有值标签返回次数', tone: 'amber', icon: 'search' },
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
      { key: 'calls', color: '#4c6ef5', label: '调用量' },
      { key: 'writes', color: '#1f9d6b', label: '打标' },
      { key: 'queries', color: '#d29a2a', label: '查询' }
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
          esc(p.label) + ' ' + s.label + '：' + (p[s.key] || 0) + '</title></circle>';
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
      return '<span><i style="background:' + s.color + '"></i>' + s.label +
        ' <b>' + fmtNum(totals[s.key]) + '</b></span>';
    }).join('');

    return '<div class="dash-chart-legend">' + legend + '</div>' +
      '<svg class="dash-chart" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="调用趋势">' +
      '<title>接口调用趋势</title>' + grid +
      (area ? '<path d="' + area + '" fill="rgba(76,110,245,.10)"/>' : '') +
      lines + dots + xLabels + '</svg>';
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
      '<th>时间</th><th>调用方</th><th>接口</th><th>结果</th><th>耗时</th><th>标签</th>' +
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
