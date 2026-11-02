/** 控制台页面模块 · market */
import * as core from '../core.js';
import { formatTagVersionValue } from '../shared.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 15：标签市场
   * ============================================================ */
  var marketState = { keyword: '', objectType: '', listings: [], requests: [] };

  function renderMarket() {
    marketState.keyword = '';
    marketState.objectType = '';
    $('#content').innerHTML =
      '<div id="market-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">标签市场 <span class="count-chip" id="market-count">--</span></div>' +
      '    <div class="card-actions">' +
      (isTenantAdmin() ? '<button class="btn btn-primary btn-sm" id="market-upload">＋ 上架标签</button>' : '') +
      '      <button class="btn btn-ghost btn-sm" id="market-refresh">刷新</button></div></div>' +
      '  <div class="filter-bar">' +
      '    <div class="field grow"><span>关键字</span><input id="market-keyword" placeholder="搜索标签名称或 tag_code"></div>' +
      '    <div class="field"><span>对象类型</span><select id="market-object">' +
      '      <option value="">全部对象</option><option value="driver">driver 司机</option>' +
      '      <option value="vehicle">vehicle 车辆</option><option value="fleet">fleet 车队</option>' +
      '      <option value="alarm_event">alarm_event 告警事件</option></select></div>' +
      '    <div class="filter-btns">' +
      '      <button class="btn btn-ghost" id="market-reset">重置</button>' +
      '      <button class="btn btn-primary" id="market-search">查询</button>' +
      '    </div>' +
      '  </div>' +
      '  <div class="market-grid" id="market-grid"></div>' +
      '</div>' +
      (isAdmin() || isTenantAdmin() ? '<div class="card"><div class="card-head">' +
        '<div class="card-title">' + (isAdmin() ? '待审批申请' : '我的申请') +
        ' <span class="count-chip" id="market-request-count">--</span></div>' +
        '<div class="card-actions"><button class="btn btn-ghost btn-sm" id="market-request-refresh">刷新</button></div></div>' +
        '<div id="market-request-table"></div></div>' : '');

    if ($('#market-upload')) $('#market-upload').addEventListener('click', openMarketUploadPopup);
    $('#market-refresh').addEventListener('click', loadMarket);
    if ($('#market-request-refresh')) $('#market-request-refresh').addEventListener('click', loadMarket);
    $('#market-search').addEventListener('click', function () {
      marketState.keyword = $('#market-keyword').value.trim();
      marketState.objectType = $('#market-object').value;
      loadMarket();
    });
    $('#market-reset').addEventListener('click', function () {
      $('#market-keyword').value = '';
      $('#market-object').value = '';
      marketState.keyword = '';
      marketState.objectType = '';
      loadMarket();
    });
    $('#market-keyword').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('#market-search').click(); });
    loadMarket();
  }

  function loadMarket() {
    var listingsPromise = api('/tag-market/listings' + qs({
      keyword: marketState.keyword, objectType: marketState.objectType
    }));
    var requestsPromise;
    if (isAdmin()) requestsPromise = api('/tag-market/requests?status=pending');
    else if (isTenantAdmin()) requestsPromise = api('/tag-market/requests');
    else requestsPromise = Promise.resolve([]);
    $('#market-grid').innerHTML = tableLoading(3, 2);
    Promise.all([listingsPromise, requestsPromise]).then(function (res) {
      marketState.listings = res[0] || [];
      marketState.requests = res[1] || [];
      renderMarketStats();
      renderMarketCards();
      renderMarketRequests();
    }).catch(function (e) {
      $('#market-grid').innerHTML = emptyState('市场加载失败', e.message);
    });
  }

  function renderMarketStats() {
    var listings = marketState.listings;
    var available = listings.filter(function (l) { return l.available_to_current_tenant; }).length;
    var applicable = listings.filter(function (l) { return l.can_apply; }).length;
    var pending = marketState.requests.filter(function (r) { return r.status === 'pending'; }).length;
    if ($('#market-count')) $('#market-count').textContent = listings.length;
    if ($('#market-stats')) {
      $('#market-stats').innerHTML = statCards([
        { label: '市场标签', value: listings.length, hint: '当前筛选结果', icon: 'tag' },
        { label: '本租户可用', value: available, hint: '已授权/公开', tone: 'green', icon: 'check' },
        { label: '可申请', value: isTenantAdmin() ? applicable : '--', hint: isTenantAdmin() ? '尚未授权的标签' : '仅租户管理员可申请', tone: 'amber', icon: 'search' },
        { label: isAdmin() ? '待审批申请' : '我的待审', value: pending, hint: isAdmin() ? '平台审批队列' : '本租户申请', tone: 'blue', icon: 'clock' }
      ]);
    }
  }

  function marketRangeChips(range) {
    var txt = String(range || '').trim();
    if (!txt || txt === '--') return '<span class="market-range">--</span>';
    txt = txt.replace(/^(枚举|范围|值域)\s*[:：]\s*/, '');
    var parts = txt.split(/\s*\/\s*/).filter(function (x) { return !!x; });
    if (parts.length < 2 || parts.length > 8) {
      return '<span class="market-range" title="' + esc(range) + '">' + esc(txt) + '</span>';
    }
    var shown = parts.slice(0, 3);
    var rest = parts.length - shown.length;
    return shown.map(function (v) {
      return '<span class="market-range-chip">' + esc(v) + '</span>';
    }).join('') + (rest > 0 ? '<span class="market-range-chip is-more">+' + rest + '</span>' : '');
  }

  function renderMarketCards() {
    var host = $('#market-grid');
    if (!host) return;
    var listings = marketState.listings;
    if (!listings.length) {
      host.innerHTML = emptyState('市场暂无标签', isTenantAdmin() ? '点击右上角「上架标签」，将本租户可用标签分享到市场' : '暂无可浏览的市场标签');
      return;
    }
    host.innerHTML = listings.map(function (l) {
      var statusBadge = '';
      if (l.owned_by_current_tenant) statusBadge = '<span class="badge badge-blue">本租户上架</span>';
      else if (l.available_to_current_tenant) statusBadge = '<span class="badge badge-green">已可使用</span>';
      else if (l.application_status === 'pending') statusBadge = '<span class="badge badge-orange">审批中</span>';
      else if (l.application_status === 'rejected') statusBadge = '<span class="badge badge-red">已驳回</span>';
      else statusBadge = '<span class="badge badge-gray">可申请</span>';
      var range = formatTagVersionValue('value_range', l.value_range);
      var action = '';
      if (l.can_apply && isTenantAdmin()) {
        action = '<button class="btn btn-primary btn-sm" data-market-apply="' + l.id + '">申请使用</button>';
      }
      return '<article class="market-card">' +
        '<div class="market-card-head">' +
        '<span class="market-card-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1.2"/></svg></span>' +
        '<div class="market-card-title"><div class="market-card-name">' + esc(l.tag_name) + '</div>' +
        '<div class="market-card-code mono">' + esc(l.tag_code) + '</div></div>' + statusBadge + '</div>' +
        '<div class="market-card-meta"><span>' + esc(l.owner_tenant_name || l.owner_tenant_id) + ' 上架</span>' +
        '<span>' + esc(l.category_code || '未分类') + ' · ' + esc(l.object_type || '不限对象') + '</span></div>' +
        '<div class="market-card-value"><span class="badge badge-gray">' + esc(l.value_type) +
        (l.value_multiple ? ' · 多选' : '') + '</span>' +
        (l.need_value_audit ? '<span class="badge badge-orange">敏感</span>' : '') +
        '<span class="market-range-wrap" title="' + esc(l.value_range || '') + '">' + marketRangeChips(range) + '</span></div>' +
        '<p class="market-card-desc' + (l.description ? '' : ' is-empty') + '">' + esc(l.description || '暂无市场说明') + '</p>' +
        '<div class="market-card-foot"><span class="market-listed">上架于 ' + fmtDt(l.created_at) + '</span>' + action + '</div>' +
        '</article>';
    }).join('');
    host.querySelectorAll('[data-market-apply]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = Number(btn.getAttribute('data-market-apply'));
        var listing = marketState.listings.filter(function (l) { return l.id === id; })[0];
        if (listing) openMarketApply(listing);
      });
    });
  }

  function renderMarketRequests() {
    var host = $('#market-request-table');
    if (!host) return;
    var rows = marketState.requests || [];
    if ($('#market-request-count')) $('#market-request-count').textContent = rows.length;
    if (!rows.length) {
      host.innerHTML = emptyState(isAdmin() ? '暂无待审批申请' : '暂无申请记录',
        isAdmin() ? '租户管理员提交市场标签申请后会出现在这里' : '申请结果会显示在这里');
      return;
    }
    if (isAdmin()) {
      var html = '<table><thead><tr><th>标签</th><th>申请租户</th><th>申请人</th><th>说明</th><th>申请时间</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (r) {
        html += '<tr>' +
          '<td><div class="cell-strong">' + esc(r.tag_name) + '</div><div class="cell-sub mono">' + esc(r.tag_code) + '</div></td>' +
          '<td><div class="cell-strong">' + esc(r.applicant_tenant_name) + '</div><div class="cell-sub mono">' + esc(r.applicant_tenant_id) + '</div></td>' +
          '<td>' + esc(r.applicant_user) + '</td>' +
          '<td class="cell-wrap">' + esc(r.remark || '--') + '</td>' +
          '<td class="nowrap">' + fmtDt(r.created_at) + '</td>' +
          '<td><div class="row-actions"><button class="btn btn-primary btn-sm" data-market-approve="' + r.id + '">通过</button>' +
          '<button class="btn btn-danger btn-sm" data-market-reject="' + r.id + '">驳回</button></div></td></tr>';
      });
      host.innerHTML = tableWrap(html + '</tbody></table>');
      host.querySelectorAll('[data-market-approve]').forEach(function (btn) {
        btn.addEventListener('click', function () { openMarketReview(Number(btn.getAttribute('data-market-approve')), true); });
      });
      host.querySelectorAll('[data-market-reject]').forEach(function (btn) {
        btn.addEventListener('click', function () { openMarketReview(Number(btn.getAttribute('data-market-reject')), false); });
      });
    } else {
      var html2 = '<table><thead><tr><th>标签</th><th>申请时间</th><th>状态</th><th>审批人</th><th>审批意见</th></tr></thead><tbody>';
      rows.forEach(function (r) {
        html2 += '<tr><td><div class="cell-strong">' + esc(r.tag_name) + '</div><div class="cell-sub mono">' + esc(r.tag_code) + '</div></td>' +
          '<td class="nowrap">' + fmtDt(r.created_at) + '</td><td>' + badge(r.status) + '</td><td>' + dash(r.reviewed_by) + '</td>' +
          '<td class="cell-wrap">' + esc(r.review_remark || '--') + '</td></tr>';
      });
      host.innerHTML = tableWrap(html2 + '</tbody></table>');
    }
  }

  function openMarketUploadPopup() {
    api('/tag-market/eligible').then(function (tags) {
      tags = tags || [];
      if (!tags.length) {
        openModal('上架标签到市场', emptyState('没有可上架的标签', '本租户可见的在线标签已全部在市场中'),
          [{ text: '关闭', cls: 'btn-ghost', onClick: closeModal }], 'lg');
        return;
      }
      var options = tags.map(function (t) {
        return '<option value="' + esc(t.tag_code) + '">' + esc(t.tag_name) + ' · ' + esc(t.tag_code) + '</option>';
      }).join('');
      var body = '<div class="field"><span>选择标签 <i class="req">*</i></span><select id="market-list-tag">' + options + '</select></div>' +
        '<div class="field" style="margin-top:14px"><span>市场说明</span>' +
        '<textarea id="market-list-desc" rows="3" placeholder="描述标签用途、适用场景，方便其他租户申请"></textarea></div>' +
        '<p class="warn-text">上架不会立即改变标签的适用租户；其他租户申请后需平台管理员审批。</p>';
      openModal('上架标签到市场', body, [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '确认上架', cls: 'btn-primary', onClick: function () {
            var btn = this;
            setBusy(btn, true);
            api('/tag-market/listings', { method: 'POST', body: {
              tag_code: $('#market-list-tag').value,
              description: $('#market-list-desc').value.trim() || null
            } }).then(function () {
              closeModal();
              toast('标签已上架市场', 'ok');
              loadMarket();
            }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
          } }
      ], 'lg');
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function openMarketApply(listing) {
    var body = '<div class="market-apply-head"><div><b>' + esc(listing.tag_name) + '</b>' +
      '<span class="mono">' + esc(listing.tag_code) + '</span></div>' +
      '<span class="muted">由 ' + esc(listing.owner_tenant_name) + ' 上架</span></div>' +
      '<div class="field" style="margin-top:14px"><span>申请说明</span>' +
      '<textarea id="market-apply-remark" rows="3" placeholder="说明使用场景，方便平台管理员审批"></textarea></div>' +
      '<p class="warn-text">审批通过后，标签将追加到本租户的适用租户列表，不会影响原租户配置。</p>';
    openModal('申请使用 · ' + listing.tag_code, body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '提交申请', cls: 'btn-primary', onClick: function () {
          var btn = this;
          setBusy(btn, true);
          api('/tag-market/listings/' + listing.id + '/apply', { method: 'POST', body: {
            remark: $('#market-apply-remark').value.trim() || null
          } }).then(function () {
            closeModal();
            toast('申请已提交，等待平台管理员审批', 'ok');
            loadMarket();
          }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
        } }
    ], 'lg');
  }

  function openMarketReview(requestId, approved) {
    var request = (marketState.requests || []).filter(function (r) { return r.id === requestId; })[0];
    if (!request) { toast('申请记录已刷新，请重试', 'err'); return; }
    var body = '<div class="market-apply-head"><div><b>' + esc(request.tag_name) + '</b>' +
      '<span class="mono">' + esc(request.tag_code) + '</span></div>' +
      '<span class="muted">' + esc(request.applicant_tenant_name) + '</span></div>' +
      '<p class="market-review-reason">申请说明：' + esc(request.remark || '--') + '</p>' +
      '<div class="field"><span>审批意见' + (approved ? '' : '（驳回必填）') + '</span>' +
      '<textarea id="market-review-remark" rows="2" placeholder="' + (approved ? '可选' : '填写驳回原因') + '"></textarea></div>';
    openModal((approved ? '通过' : '驳回') + '标签市场申请', body, [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: approved ? '确认通过' : '确认驳回', cls: approved ? 'btn-primary' : 'btn-danger', onClick: function () {
          var remark = $('#market-review-remark').value.trim();
          if (!approved && !remark) { toast('驳回必须填写原因', 'err'); return; }
          var btn = this;
          setBusy(btn, true);
          api('/tag-market/requests/' + requestId + (approved ? '/approve' : '/reject'), {
            method: 'POST', body: { remark: remark || null }
          }).then(function () {
            closeModal();
            toast(approved ? '已通过，租户已获得标签使用权' : '申请已驳回', 'ok');
            loadMarket();
          }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
        } }
    ], 'lg');
  }


  export { renderMarket };
