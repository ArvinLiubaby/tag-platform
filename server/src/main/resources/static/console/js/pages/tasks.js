/** 控制台页面模块 · tasks */
import * as core from '../core.js';
const { TOKEN_KEY, USER_KEY, state, PAGE_TITLES, PAGE_SUBTITLES, STATUS_BADGE, ROLE_LABEL, ROLE_BADGE, PRIORITY_LABEL, PRIORITY_BADGE, $, esc, dash, badge, emptyState, tableWrap, tableLoading, STAT_ICONS, statIcon, statCards, copyText, setBusy, toastTimer, toast, openModal, closeModal, api, qs, loadPermissions, loadAppVersion, showLogin, navBadgeLoaded, navBadgeLoading, updateNavPending, loadNavBadges, showApp, applyNavPermissions, signIn, signOutHooks, onSignOut, signOut, bindLogin, isAdmin, isTenantAdmin, permissionsLoaded, can, canByPermission, canManageUsers, canViewAuditQueue, canViewAuditLogs, canViewTenants, canViewRoles, canTag, canReview, canManageTasks, canManageTagDefinitions, workTenantId, setWorkTenant, fillTenantSelect, fmtDt, parseJsonValue } = core;

  /* ============================================================
   * 页面 11：我的任务
   * ============================================================ */
  var myTasksState = { status: '', context: null, items: [], tasks: [] };

  function renderMyTasks() {
    myTasksState.status = '';
    $('#content').innerHTML =
      '<div id="my-task-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">待办对象 <span class="count-chip" id="my-item-count">--</span></div>' +
      '    <div class="card-actions">' +
      '      <select id="my-item-status" class="select-sm"><option value="">全部待办</option>' +
      '        <option value="pending">待领取</option><option value="claimed">已领取</option>' +
      '        <option value="in_progress">进行中</option><option value="submitted">待审核</option>' +
      '        <option value="rejected">已驳回</option></select>' +
      '      <button class="btn btn-ghost btn-sm" id="my-refresh">刷新</button></div></div>' +
      '  <div id="my-item-table"></div>' +
      '</div>' +
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">我的任务 <span class="count-chip" id="my-task-count">--</span></div>' +
      '    <div class="card-actions"><span class="muted">任务池与本人在办任务</span></div></div>' +
      '  <div id="my-task-table"></div>' +
      '</div>';
    $('#my-item-status').addEventListener('change', function () { myTasksState.status = this.value; loadMyItems(); });
    $('#my-refresh').addEventListener('click', function () { loadMyContext(); loadMyItems(); loadMyTasks(); });
    loadMyContext();
    loadMyItems();
    loadMyTasks();
  }

  function loadMyContext() {
    api('/workbench/context').then(function (ctx) {
      myTasksState.context = ctx;
      if ($('#my-task-stats')) {
        $('#my-task-stats').innerHTML = statCards([
          { label: '待办对象', value: ctx.my_pending_item_count || 0, hint: '待领取 / 进行中 / 返工', tone: 'amber', icon: 'clock' },
          { label: '在办任务', value: ctx.my_task_count || 0, hint: '任务池 + 指派给我', tone: 'blue', icon: 'tag' },
          { label: '待我审核', value: ctx.review_item_count || 0, hint: '任务项审核队列', tone: 'purple', icon: 'shield' },
          { label: '班组', value: ctx.team || '--', hint: ctx.operator_name || '操作员', tone: 'gray', icon: 'users' }
        ]);
      }
    }).catch(function () {});
  }

  function loadMyItems() {
    $('#my-item-table').innerHTML = tableLoading(6, 5);
    api('/workbench/items' + qs({ status: myTasksState.status, limit: 100 })).then(function (rows) {
      myTasksState.items = rows || [];
      if ($('#my-item-count')) $('#my-item-count').textContent = rows.length;
      if (!rows.length) { $('#my-item-table').innerHTML = emptyState('暂无待办', '任务派发后待办对象会出现在这里'); return; }
      var html = '<table><thead><tr><th>对象</th><th>任务</th><th>状态</th><th>操作员</th><th>更新时间</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (x) {
        var item = x.item || {}; var task = x.task || {};
        var values = (x.values || []).map(function (v) { return v.tag_code + '=' + (v.tag_value || ''); }).join(' · ');
        html += '<tr>' +
          '<td><div class="cell-strong mono">' + esc(item.object_id) + '</div><div class="cell-sub">' + esc(item.object_type) + '</div></td>' +
          '<td><div>' + esc(task.title || '') + '</div><div class="cell-sub mono">' + esc(task.task_code || '') + '</div></td>' +
          '<td>' + badge(item.status) + '</td>' +
          '<td>' + dash(item.operator_name) + '</td>' +
          '<td class="nowrap">' + fmtDt(item.updated_at) + '</td>' +
          '<td><button class="btn btn-primary btn-sm" data-my-open="' + item.id + '">去处理</button>' +
          (values ? '<div class="cell-sub wb-values-hint">' + esc(values) + '</div>' : '') + '</td></tr>';
      });
      $('#my-item-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#my-item-table').querySelectorAll('[data-my-open]').forEach(function (btn) {
        btn.addEventListener('click', function () { location.hash = '#/workbench?item=' + btn.getAttribute('data-my-open'); });
      });
    }).catch(function (e) { $('#my-item-table').innerHTML = emptyState('加载失败', e.message); });
  }

  function loadMyTasks() {
    $('#my-task-table').innerHTML = tableLoading(6, 4);
    api('/workbench/my-tasks?limit=50').then(function (rows) {
      myTasksState.tasks = rows || [];
      if ($('#my-task-count')) $('#my-task-count').textContent = rows.length;
      if (!rows.length) { $('#my-task-table').innerHTML = emptyState('暂无任务', '任务发布后会出现在这里'); return; }
      var html = '<table><thead><tr><th>任务</th><th>对象类型</th><th>状态</th><th>进度</th><th>截止</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (x) {
        var t = x.task; var s = x.stats || {};
        html += '<tr>' +
          '<td><div class="cell-strong">' + esc(t.title) + '</div><div class="cell-sub mono">' + esc(t.task_code) + '</div></td>' +
          '<td>' + esc(t.object_type) + '</td>' +
          '<td>' + badge(t.status) + '</td>' +
          '<td>' + taskProgress(s, t.status) + '</td>' +
          '<td class="nowrap">' + fmtDt(t.due_at) + '</td>' +
          '<td><button class="btn btn-ghost btn-sm" data-my-task="' + t.id + '">查看</button></td></tr>';
      });
      $('#my-task-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#my-task-table').querySelectorAll('[data-my-task]').forEach(function (btn) {
        btn.addEventListener('click', function () { openTaskDetail(Number(btn.getAttribute('data-my-task'))); });
      });
    }).catch(function (e) { $('#my-task-table').innerHTML = emptyState('加载失败', e.message); });
  }


  /* ============================================================
   * 页面 12：任务管理
   * ============================================================ */
  var tasksState = { status: '', rows: [], objectTypes: [], assignees: [] };
  var taskCreateState = {
    scope: 'all', objectType: '', total: 0, options: [], selected: {}, keyword: '',
    totalVersion: 0, optionsVersion: 0
  };

  function taskProgress(stats, status) {
    var total = stats.total || 0;
    var done = (stats.approved || 0) + (stats.skipped || 0);
    var pct = total ? Math.round(done / total * 100) : 0;
    var tone = status === 'completed' ? ' is-done'
      : status === 'cancelled' ? ' is-muted'
      : status === 'reviewing' ? ' is-review' : '';
    var text = done + ' / ' + total + (pct < 100 ? ' · ' + pct + '%' : '');
    return '<div class="task-progress' + tone + '"><div class="progress-track"><i style="width:' + pct + '%"></i></div>' +
      '<span>' + text + '</span></div>';
  }

  function renderTasks() {
    tasksState.status = '';
    $('#content').innerHTML =
      '<div id="task-stats"></div>' +
      '<div class="card">' +
      '  <div class="card-head"><div class="card-title">打标任务 <span class="count-chip" id="task-count">--</span></div>' +
      '    <div class="card-actions">' +
      (canManageTasks() ? '<button class="btn btn-primary btn-sm" id="task-create">＋ 新建任务</button>' : '') +
      '      <button class="btn btn-ghost btn-sm" id="task-refresh">刷新</button></div></div>' +
      '  <div class="filter-bar">' +
      (isAdmin() ? '<div class="field"><span>租户</span><select id="task-tenant"></select></div>' : '') +
      '    <div class="field"><span>状态</span><select id="task-status">' +
      '      <option value="">全部状态</option><option value="draft">草稿</option><option value="published">已发布</option>' +
      '      <option value="in_progress">进行中</option><option value="reviewing">审核中</option>' +
      '      <option value="completed">已完成</option><option value="cancelled">已取消</option></select></div>' +
      '    <button class="btn btn-primary" id="task-search">查询</button>' +
      '  </div>' +
      '  <div id="task-table"></div>' +
      '</div>';
    if (isAdmin()) {
      fillTenantSelect('task-tenant');
      $('#task-tenant').addEventListener('change', function () { setWorkTenant(this.value); loadTasks(); });
    }
    $('#task-status').addEventListener('change', function () { tasksState.status = this.value; loadTasks(); });
    $('#task-search').addEventListener('click', loadTasks);
    $('#task-refresh').addEventListener('click', loadTasks);
    if ($('#task-create')) $('#task-create').addEventListener('click', openCreateTask);
    loadTasks();
  }

  function loadTasks() {
    $('#task-table').innerHTML = tableLoading(8, 5);
    api('/tasks' + qs({ tenantId: workTenantId(), status: tasksState.status, limit: 100 })).then(function (rows) {
      tasksState.rows = rows || [];
      if ($('#task-count')) $('#task-count').textContent = rows.length;
      var running = rows.filter(function (x) { return ['published', 'in_progress', 'reviewing'].indexOf(x.task.status) >= 0; }).length;
      var review = 0;
      rows.forEach(function (x) { review += (x.stats && x.stats.submitted) || 0; });
      if ($('#task-stats')) {
        $('#task-stats').innerHTML = statCards([
          { label: '任务总数', value: rows.length, hint: '当前租户', icon: 'tag' },
          { label: '执行中', value: running, hint: '已发布 / 进行中 / 审核中', tone: 'blue', icon: 'clock' },
          { label: '待审核项', value: review, hint: '提交后等待审核', tone: 'amber', icon: 'shield' },
          { label: '已完成', value: rows.filter(function (x) { return x.task.status === 'completed'; }).length, hint: '状态为已完成', tone: 'green', icon: 'check' }
        ]);
      }
      if (!rows.length) { $('#task-table').innerHTML = emptyState('暂无任务', '点击新建任务开始派发打标'); return; }
      var html = '<table><thead><tr><th>任务</th><th>对象类型</th><th>状态</th><th>进度</th><th>优先级</th>' +
        '<th>执行人 / 班组</th><th>截止</th><th>操作</th></tr></thead><tbody>';
      rows.forEach(function (x) {
        var t = x.task; var s = x.stats || {};
        var owner = t.assignee_name || t.team || (t.assignment_mode === 'pool' ? '任务池' : '--');
        html += '<tr>' +
          '<td><div class="cell-strong">' + esc(t.title) + '</div><div class="cell-sub mono">' + esc(t.task_code) + '</div></td>' +
          '<td>' + esc(t.object_type) + '</td>' +
          '<td>' + badge(t.status) + '</td>' +
          '<td>' + taskProgress(s, t.status) + '</td>' +
          '<td><span class="badge ' + (PRIORITY_BADGE[t.priority] || 'badge-gray') + '">' +
          esc(PRIORITY_LABEL[t.priority] || t.priority) + '</span></td>' +
          '<td>' + esc(owner) + (t.review_required ? '<div class="cell-sub">需审核</div>' : '') + '</td>' +
          '<td class="nowrap">' + fmtDt(t.due_at) + '</td>' +
          '<td><button class="btn btn-ghost btn-sm" data-task-detail="' + t.id + '">详情</button></td></tr>';
      });
      $('#task-table').innerHTML = tableWrap(html + '</tbody></table>');
      $('#task-table').querySelectorAll('[data-task-detail]').forEach(function (btn) {
        btn.addEventListener('click', function () { openTaskDetail(Number(btn.getAttribute('data-task-detail'))); });
      });
    }).catch(function (e) { $('#task-table').innerHTML = emptyState('加载失败', e.message); });
  }

  function openCreateTask() {
    Promise.all([
      api('/workbench/object-types'),
      api('/tasks/assignees')
    ]).then(function (res) {
      var types = res[0] || []; var assignees = res[1] || [];
      tasksState.objectTypes = types;
      tasksState.assignees = assignees;
      var preferredType = types.some(function (t) { return t.object_type === 'driver'; })
        ? 'driver' : (types[0] ? types[0].object_type : '');
      taskCreateState = { scope: 'all', objectType: preferredType, total: 0, options: [],
        selected: {}, keyword: '', totalVersion: 0, optionsVersion: 0 };
      var body = '<div class="modal-form">' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">01</span><div><h4>基础信息</h4>' +
        '    <p>定义任务名称、对象类型、派发方式和截止时间。</p></div></div>' +
        '  <div class="form-grid">' +
        '    <div class="field span-2"><span>任务标题 <i class="req">*</i></span><input id="nt-title" placeholder="如：10 月客服补标 · 华东车队"></div>' +
        '    <div class="field"><span>对象类型 <i class="req">*</i></span><select id="nt-otype">' +
        types.map(function (t) {
          return '<option value="' + esc(t.object_type) + '"' + (t.object_type === preferredType ? ' selected' : '') +
            '>' + esc(t.object_type) + ' ' + esc(t.type_name || '') + '</option>';
        }).join('') + '</select></div>' +
        '    <div class="field"><span>优先级</span><select id="nt-priority">' +
        '      <option value="normal">普通</option><option value="high">高</option><option value="urgent">紧急</option><option value="low">低</option></select></div>' +
        '    <div class="field"><span>截止时间</span><input id="nt-due" type="datetime-local"></div>' +
        '    <div class="field"><span>派发方式</span><select id="nt-mode">' +
        '      <option value="pool">任务池（先到先得）</option><option value="assignee">指定打标员</option><option value="team">指定班组</option></select></div>' +
        '    <div class="field hidden" id="nt-assignee-field"><span>打标员</span><select id="nt-assignee">' +
        assignees.map(function (u) { return '<option value="' + u.id + '">' + esc(u.display_name) + ' · ' + esc(u.username) + '</option>'; }).join('') +
        '</select></div>' +
        '    <div class="field hidden" id="nt-team-field"><span>班组</span><input id="nt-team" placeholder="如：客服一组"></div>' +
        '    <div class="field span-2"><span>任务说明</span><textarea id="nt-desc" rows="2" placeholder="打标口径、注意事项"></textarea></div>' +
        '  </div>' +
        '</section>' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">02</span><div><h4>对象范围</h4>' +
        '    <p>默认使用对象索引中的全部对象，也可以按条件筛选或手动输入。</p></div></div>' +
        '  <div class="object-scope">' +
        '    <label class="scope-option"><input type="radio" name="nt-object-scope" value="all" checked>' +
        '      <span><b>全部对象（默认）</b><small id="nt-object-total">统计中…</small></span></label>' +
        '    <label class="scope-option"><input type="radio" name="nt-object-scope" value="pick">' +
        '      <span><b>指定对象</b><small>从对象索引筛选，或手动输入</small></span></label>' +
        '  </div>' +
        '  <div class="object-scope-hint" id="nt-object-all-hint">正在统计当前对象类型在对象索引中的数量…</div>' +
        '  <div class="object-picker hidden" id="nt-object-picker">' +
        '    <div class="object-search"><input id="nt-object-keyword" placeholder="按对象 ID 筛选，如 DRV85">' +
        '      <button type="button" class="btn btn-ghost btn-sm" id="nt-object-search">筛选</button></div>' +
        '    <div class="object-options" id="nt-object-options"></div>' +
        '    <div class="object-selected" id="nt-object-selected"></div>' +
        '    <textarea id="nt-objects-manual" rows="2" placeholder="还可手动输入对象 ID（每行一个或逗号分隔）"></textarea>' +
        '  </div>' +
        '</section>' +
        '<section class="form-section">' +
        '  <div class="form-section-head"><span class="form-section-index">03</span><div><h4>目标标签</h4>' +
        '    <p>选择需要采集的标签；敏感标签提交后在审核通过前不会写入。</p></div>' +
        '    <span class="count-chip" id="nt-tag-selected">0</span></div>' +
        '  <div class="tag-picker-toolbar"><input id="nt-tag-search" placeholder="搜索标签名称或 tag_code">' +
        '    <span class="muted">已选择 <b id="nt-tag-selected-text">0</b> 个标签</span></div>' +
        '  <div id="nt-tags" class="tag-choice-grid">加载中…</div>' +
        '</section>' +
        '<section class="form-section form-section-compact">' +
        '  <div class="form-section-head"><span class="form-section-index">04</span><div><h4>发布与审核</h4>' +
        '    <p>普通标签提交即生效；开启任务审核后所有提交先进入审核。</p></div></div>' +
        '  <div class="option-grid">' +
        '    <label class="option-card"><input id="nt-review" type="checkbox">' +
        '      <span><b>任务级审核</b><small>普通标签提交后也需审核；敏感标签始终强制审核</small></span></label>' +
        '    <label class="option-card"><input id="nt-publish" type="checkbox" checked>' +
        '      <span><b>创建后立即发布</b><small>关闭后先保存为草稿，稍后再导入对象并发布</small></span></label>' +
        '  </div>' +
        '</section>' +
        '</div>';
      openModal('新建打标任务', body, [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '创建任务', cls: 'btn-primary', onClick: createTaskSubmit }
      ], 'xl');
      $('#nt-mode').addEventListener('change', function () {
        $('#nt-assignee-field').classList.toggle('hidden', this.value !== 'assignee');
        $('#nt-team-field').classList.toggle('hidden', this.value !== 'team');
      });
      document.querySelectorAll('input[name="nt-object-scope"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
          taskCreateState.scope = this.value;
          $('#nt-object-picker').classList.toggle('hidden', this.value !== 'pick');
          $('#nt-object-all-hint').classList.toggle('hidden', this.value === 'pick');
          if (this.value === 'pick') ntLoadObjectOptions();
        });
      });
      $('#nt-object-search').addEventListener('click', function () {
        taskCreateState.keyword = $('#nt-object-keyword').value.trim();
        ntLoadObjectOptions();
      });
      $('#nt-object-keyword').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); $('#nt-object-search').click(); }
      });
      $('#nt-otype').addEventListener('change', function () {
        taskCreateState.objectType = this.value;
        taskCreateState.selected = {};
        taskCreateState.keyword = '';
        taskCreateState.options = [];
        if ($('#nt-object-keyword')) $('#nt-object-keyword').value = '';
        if ($('#nt-objects-manual')) $('#nt-objects-manual').value = '';
        ntRenderObjectSelected();
        ntLoadObjectTotal();
        if (taskCreateState.scope === 'pick') ntLoadObjectOptions();
        loadNewTaskTags(this.value);
      });
      $('#nt-tag-search').addEventListener('input', filterNtTags);
      ntLoadObjectTotal();
      ntRenderObjectSelected();
      loadNewTaskTags(preferredType);
    }).catch(function (e) { toast('初始化失败：' + e.message, 'err'); });
  }

  function loadNewTaskTags(objectType) {
    var host = $('#nt-tags');
    if (!host) return;
    host.innerHTML = '加载中…';
    api('/workbench/tags?objectType=' + encodeURIComponent(objectType)).then(function (rows) {
      rows = rows || [];
      if (!rows.length) { host.innerHTML = '<div class="wb-empty-line">该对象类型暂无可用标签</div>'; return; }
      host.innerHTML = rows.map(function (t) {
        return '<label class="tag-choice" data-tag-text="' + esc((t.tag_name + ' ' + t.tag_code).toLowerCase()) + '">' +
          '<input type="checkbox" data-nt-tag="' + esc(t.tag_code) + '">' +
          '<span class="tag-choice-main"><span class="tag-choice-name">' + esc(t.tag_name) + '</span>' +
          '<span class="tag-choice-code mono">' + esc(t.tag_code) + '</span></span>' +
          (t.need_value_audit ? '<span class="tag-choice-flag">敏感</span>' : '') +
          '</label>';
      }).join('');
      host.onchange = updateNtTagCount;
      updateNtTagCount();
      filterNtTags();
    }).catch(function (e) { host.innerHTML = '<div class="wb-empty-line">加载失败：' + esc(e.message) + '</div>'; });
  }

  function updateNtTagCount() {
    var count = document.querySelectorAll('[data-nt-tag]:checked').length;
    if ($('#nt-tag-selected')) $('#nt-tag-selected').textContent = count;
    if ($('#nt-tag-selected-text')) $('#nt-tag-selected-text').textContent = count;
  }

  function filterNtTags() {
    var keyword = ($('#nt-tag-search') ? $('#nt-tag-search').value : '').trim().toLowerCase();
    document.querySelectorAll('#nt-tags .tag-choice').forEach(function (choice) {
      var text = choice.getAttribute('data-tag-text') || '';
      choice.classList.toggle('hidden', !!keyword && text.indexOf(keyword) < 0);
    });
  }

  function ntLoadObjectTotal() {
    var type = taskCreateState.objectType;
    if (!type) return;
    var version = ++taskCreateState.totalVersion;
    api('/tasks/object-index' + qs({ objectType: type, limit: 1 })).then(function (page) {
      if (version !== taskCreateState.totalVersion) return;
      taskCreateState.total = page.total || 0;
      var label = $('#nt-object-total');
      if (label) label.textContent = taskCreateState.total ? '对象索引 ' + taskCreateState.total + ' 个' : '对象索引暂无对象';
      var hint = $('#nt-object-all-hint');
      if (!hint) return;
      if (!taskCreateState.total) {
        hint.className = 'object-scope-hint warn';
        hint.innerHTML = '对象索引中暂无 ' + esc(type) + ' 对象；请切换到「指定对象」手动输入，或先通过历史任务导入对象。';
      } else if (taskCreateState.total > 10000) {
        hint.className = 'object-scope-hint warn';
        hint.innerHTML = '对象索引共 ' + taskCreateState.total + ' 个，超过单任务上限 10000；请改为指定对象分批创建。';
      } else {
        hint.className = 'object-scope-hint';
        hint.innerHTML = '默认将对象索引中的全部 <b>' + taskCreateState.total + '</b> 个 ' +
          esc(type) + ' 对象加入任务（创建时快照）。';
      }
    }).catch(function (e) {
      if (version !== taskCreateState.totalVersion) return;
      var hint = $('#nt-object-all-hint');
      if (hint) { hint.className = 'object-scope-hint warn'; hint.textContent = '对象数量统计失败：' + e.message; }
    });
  }

  function ntLoadObjectOptions() {
    var type = taskCreateState.objectType;
    var host = $('#nt-object-options');
    if (!type || !host) return;
    var version = ++taskCreateState.optionsVersion;
    host.innerHTML = '<div class="wb-empty-line">加载中…</div>';
    api('/tasks/object-index' + qs({
      objectType: type, keyword: taskCreateState.keyword, limit: 100
    })).then(function (page) {
      if (version !== taskCreateState.optionsVersion) return;
      taskCreateState.options = page.items || [];
      ntRenderObjectOptions();
    }).catch(function (e) {
      if (version !== taskCreateState.optionsVersion) return;
      host.innerHTML = '<div class="wb-empty-line">加载失败：' + esc(e.message) + '</div>';
    });
  }

  function ntRenderObjectOptions() {
    var host = $('#nt-object-options');
    if (!host) return;
    if (!taskCreateState.options.length) {
      host.innerHTML = '<div class="wb-empty-line">' +
        (taskCreateState.keyword ? '没有匹配的对象' : '对象索引暂无对象') +
        '，可在下方手动输入</div>';
      return;
    }
    host.innerHTML = taskCreateState.options.map(function (row) {
      var checked = !!taskCreateState.selected[row.object_id];
      return '<label class="object-option' + (checked ? ' selected' : '') + '">' +
        '<input type="checkbox" data-nt-object="' + esc(row.object_id) + '"' + (checked ? ' checked' : '') + '>' +
        '<span class="mono">' + esc(row.object_id) + '</span>' +
        '<small>' + fmtDt(row.last_seen) + '</small></label>';
    }).join('');
    host.querySelectorAll('[data-nt-object]').forEach(function (box) {
      box.addEventListener('change', function () {
        var id = box.getAttribute('data-nt-object');
        if (box.checked) taskCreateState.selected[id] = true;
        else delete taskCreateState.selected[id];
        var row = box.closest('.object-option');
        if (row) row.classList.toggle('selected', box.checked);
        ntRenderObjectSelected();
      });
    });
  }

  function ntRenderObjectSelected() {
    var host = $('#nt-object-selected');
    if (!host) return;
    var ids = Object.keys(taskCreateState.selected);
    if (!ids.length) {
      host.innerHTML = '<span class="muted">尚未选择对象</span>';
      return;
    }
    host.innerHTML = '<span class="muted">已选 ' + ids.length + ' 个：</span>' + ids.map(function (id) {
      return '<span class="object-chip"><span class="mono">' + esc(id) + '</span>' +
        '<button type="button" data-nt-remove="' + esc(id) + '" aria-label="移除">×</button></span>';
    }).join('');
    host.querySelectorAll('[data-nt-remove]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        delete taskCreateState.selected[btn.getAttribute('data-nt-remove')];
        var box = document.querySelector('[data-nt-object="' + btn.getAttribute('data-nt-remove') + '"]');
        if (box) {
          box.checked = false;
          var row = box.closest('.object-option');
          if (row) row.classList.remove('selected');
        }
        ntRenderObjectSelected();
      });
    });
  }

  function ntCollectObjectIds() {
    var ids = Object.keys(taskCreateState.selected);
    var manual = $('#nt-objects-manual') ? $('#nt-objects-manual').value : '';
    manual.split(/[\s,;]+/).forEach(function (id) {
      var value = id.trim();
      if (value && ids.indexOf(value) < 0) ids.push(value);
    });
    return ids;
  }

  function createTaskSubmit() {
    var title = $('#nt-title').value.trim();
    if (!title) { toast('请填写任务标题', 'err'); return; }
    var scopeRadio = document.querySelector('input[name="nt-object-scope"]:checked');
    var allObjects = !scopeRadio || scopeRadio.value === 'all';
    var objectIds = allObjects ? [] : ntCollectObjectIds();
    if (allObjects && !taskCreateState.total) {
      toast('对象索引中暂无该类型对象，请切换为「指定对象」手动输入', 'err'); return;
    }
    if (allObjects && taskCreateState.total > 10000) {
      toast('对象数超过单任务上限 10000，请改为指定对象分批创建', 'err'); return;
    }
    if (!allObjects && !objectIds.length) {
      toast('请选择对象，或切换为「全部对象」', 'err'); return;
    }
    var tagCodes = [];
    document.querySelectorAll('[data-nt-tag]:checked').forEach(function (el) { tagCodes.push(el.getAttribute('data-nt-tag')); });
    if (!tagCodes.length) { toast('请至少选择一个目标标签', 'err'); return; }
    var mode = $('#nt-mode').value;
    var body = {
      tenant_id: workTenantId(),
      title: title,
      description: $('#nt-desc').value.trim() || null,
      object_type: $('#nt-otype').value,
      priority: $('#nt-priority').value,
      assignment_mode: mode,
      assignee_user_id: mode === 'assignee' ? Number($('#nt-assignee').value) : null,
      team: mode === 'team' ? $('#nt-team').value.trim() : null,
      review_required: $('#nt-review').checked,
      due_at: $('#nt-due').value ? $('#nt-due').value + ':00' : null,
      tag_codes: tagCodes,
      object_ids: objectIds,
      all_objects: allObjects,
      publish: $('#nt-publish').checked
    };
    if (mode === 'assignee' && !body.assignee_user_id) { toast('请选择打标员', 'err'); return; }
    if (mode === 'team' && !body.team) { toast('请填写班组', 'err'); return; }
    var btn = this;
    setBusy(btn, true);
    api('/tasks', { method: 'POST', body: body }).then(function (view) {
      closeModal();
      toast('任务已创建' + (body.publish ? '并发布' : ''), 'ok');
      loadTasks();
      if (view && view.task) openTaskDetail(view.task.id);
    }).catch(function (e) {
      setBusy(btn, false);
      toast(e.message, 'err');
    });
  }

  /* ============================================================
   * 任务详情 / 审核 / 导入
   * ============================================================ */
  function openTaskDetail(taskId) {
    api('/tasks/' + taskId).then(function (detail) {
      var task = detail.task;
      var stats = detail.stats || {};
      var tags = detail.tags || [];
      var items = detail.items || [];
      var events = detail.events || [];
      var progress = taskProgress(stats, task.status);
      var canManage = canManageTasks();
      var html =
        '<div class="task-detail-head">' +
        '  <div><div class="task-detail-title">' + esc(task.title) + '</div>' +
        '  <div class="task-detail-meta mono">' + esc(task.task_code) + ' · ' + esc(task.tenant_id) + '</div></div>' +
        '  <div class="row-actions">' + badge(task.status) +
        '    <span class="badge ' + (PRIORITY_BADGE[task.priority] || 'badge-gray') + '">' + esc(PRIORITY_LABEL[task.priority] || task.priority) + '</span></div>' +
        '</div>' +
        '<div class="task-detail-grid">' +
        '  <div><b>对象类型</b><span>' + esc(task.object_type) + '</span></div>' +
        '  <div><b>执行人 / 班组</b><span>' + esc(task.assignee_name || task.team || (task.assignment_mode === 'pool' ? '任务池' : '--')) + '</span></div>' +
        '  <div><b>审核</b><span>' + (task.review_required ? '需要审核' : '普通标签免审') + (task.reviewer_name ? ' · ' + esc(task.reviewer_name) : '') + '</span></div>' +
        '  <div><b>截止时间</b><span>' + fmtDt(task.due_at) + '</span></div>' +
        '  <div><b>创建人</b><span>' + esc(task.created_by) + '</span></div>' +
        '  <div><b>创建时间</b><span>' + fmtDt(task.created_at) + '</span></div>' +
        '</div>' +
        '<div class="task-detail-block"><div class="task-detail-block-head"><b>进度</b><span>' + progress + '</span></div>' +
        '  <div class="task-stat-chips">' + taskStatChips(stats) + '</div>' +
        '  <div class="task-tag-chips">' + tags.map(function (t) {
          return '<span class="chip chip-static mono">' + esc(t.tag_code) + (t.required ? ' *' : '') + '</span>';
        }).join('') + '</div></div>' +
        (canManage || canReview() ? '<div class="task-detail-actions">' +
          (canManage && task.status === 'draft' ? '<button class="btn btn-primary btn-sm" data-task-publish="1">发布任务</button>' : '') +
          (canManage && ['draft', 'published', 'in_progress'].indexOf(task.status) >= 0 ? '<button class="btn btn-ghost btn-sm" data-task-import="1">导入对象</button>' : '') +
          (canManage && ['completed', 'cancelled'].indexOf(task.status) < 0 ? '<button class="btn btn-ghost btn-sm" data-task-assign="1">改派</button>' : '') +
          (canManage && ['completed', 'cancelled'].indexOf(task.status) < 0 ? '<button class="btn btn-danger btn-sm" data-task-cancel="1">取消任务</button>' : '') +
          '<button class="btn btn-ghost btn-sm" data-task-reload="1">刷新</button>' +
          '</div>' : '') +
        '<div class="task-detail-block"><div class="task-detail-block-head"><b>任务项</b><span>最多展示 200 条</span></div>' +
        (items.length ? taskItemsTable(items) : emptyState('暂无对象', task.status === 'draft' ? '导入对象后发布任务' : '任务没有对象')) +
        '</div>' +
        '<div class="task-detail-block"><div class="task-detail-block-head"><b>流转记录</b><span>最近 100 条</span></div>' +
        '<div class="task-events">' + (events.length ? events.map(function (ev) {
          return '<div class="task-event"><span class="task-event-dot"></span>' +
            '<div><b>' + esc(eventLabel(ev.event_type)) + '</b> <span class="mono">' + esc(ev.operator || '') + '</span>' +
            '<div class="cell-sub">' + fmtDt(ev.created_at) + (ev.remark ? ' · ' + esc(ev.remark) : '') + '</div></div></div>';
        }).join('') : '<div class="wb-empty-line">暂无流转记录</div>') + '</div></div>';

      openModal('任务详情', html, [{ text: '关闭', cls: 'btn-ghost', onClick: closeModal }], 'xl');
      var scope = $('#modal-body');
      scope.querySelectorAll('[data-task-reload]').forEach(function (b) {
        b.addEventListener('click', function () { openTaskDetail(task.id); });
      });
      scope.querySelectorAll('[data-task-publish]').forEach(function (b) {
        b.addEventListener('click', function () { confirmTaskAction('发布会校验目标标签与对象，确认发布？', '/tasks/' + task.id + '/publish', {}, task.id); });
      });
      scope.querySelectorAll('[data-task-import]').forEach(function (b) {
        b.addEventListener('click', function () { openImportTask(task); });
      });
      scope.querySelectorAll('[data-task-assign]').forEach(function (b) {
        b.addEventListener('click', function () { openAssignTask(task); });
      });
      scope.querySelectorAll('[data-task-cancel]').forEach(function (b) {
        b.addEventListener('click', function () { confirmTaskAction('取消后未完成项将跳过，已通过标签值保留。确认取消？', '/tasks/' + task.id + '/cancel', {}, task.id); });
      });
      scope.querySelectorAll('[data-task-review]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          openReviewItem(task, Number(btn.getAttribute('data-task-review')));
        });
      });
    }).catch(function (e) { toast('任务详情加载失败：' + e.message, 'err'); });
  }

  function taskStatChips(s) {
    var items = [
      ['待领取', s.pending, 'pending'], ['已领取', s.claimed, 'claimed'], ['进行中', s.in_progress, 'in_progress'],
      ['待审核', s.submitted, 'submitted'], ['已通过', s.approved, 'approved'],
      ['驳回/返工', s.rejected, 'rejected'], ['已跳过', s.skipped, 'skipped']
    ];
    return items.map(function (it) {
      return '<span class="task-stat-chip"><b>' + (it[1] || 0) + '</b><span>' + it[0] + '</span></span>';
    }).join('');
  }

  function taskItemsTable(items) {
    var html = '<div class="table-wrap"><table><thead><tr><th>对象</th><th>状态</th><th>操作员</th>' +
      '<th>标签值</th><th>提交时间</th><th>审核</th></tr></thead><tbody>';
    items.forEach(function (x) {
      var item = x.item || {}; var values = x.values || [];
      var valueHtml = values.length ? values.map(function (v) {
        return '<div class="task-value-line"><span class="mono">' + esc(v.tag_code) + '</span> = <b>' + esc(v.tag_value) + '</b>' +
          '<span class="badge ' + (STATUS_BADGE[v.status] ? STATUS_BADGE[v.status][0] : 'badge-gray') + '">' +
          esc(STATUS_BADGE[v.status] ? STATUS_BADGE[v.status][1] : v.status) + '</span></div>';
      }).join('') : '<span class="muted">--</span>';
      var review = '';
      if (item.status === 'submitted' && canReview()) {
        review = '<button class="btn btn-primary btn-sm" data-task-review="' + item.id + '">审核</button>';
      } else if (item.review_remark) {
        review = '<span class="cell-sub" title="' + esc(item.review_remark) + '">' + esc(item.review_remark) + '</span>';
      } else { review = '--'; }
      html += '<tr>' +
        '<td><div class="cell-strong mono">' + esc(item.object_id) + '</div><div class="cell-sub">' + esc(item.object_type) + '</div></td>' +
        '<td>' + badge(item.status) + '</td>' +
        '<td>' + esc(item.operator_name || '--') + '<div class="cell-sub">' + esc(item.operator_id || '') + '</div></td>' +
        '<td class="cell-wrap">' + valueHtml + '</td>' +
        '<td class="nowrap">' + fmtDt(item.submitted_at) + '</td>' +
        '<td>' + review + '</td></tr>';
    });
    return tableWrap(html + '</tbody></table></div>');
  }

  function eventLabel(type) {
    var labels = {
      create: '创建', import: '导入对象', publish: '发布', claim: '领取', submit: '提交',
      approve: '审核通过', reject: '驳回', skip: '跳过', cancel: '取消', complete: '完成',
      assign: '改派', progress: '开始执行', review: '进入审核', update: '编辑'
    };
    return labels[type] || type;
  }

  function confirmTaskAction(message, path, body, taskId) {
    openModal('确认操作', '<p>' + esc(message) + '</p>', [
      { text: '取消', cls: 'btn-ghost', onClick: closeModal },
      { text: '确认', cls: 'btn-primary', onClick: function () {
          var btn = this;
          setBusy(btn, true);
          api(path, { method: 'POST', body: body || {} }).then(function () {
            closeModal();
            toast('操作成功', 'ok');
            openTaskDetail(taskId);
            loadTasks();
          }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
        } }
    ]);
  }

  function openImportTask(task) {
    openModal('导入对象 · ' + task.task_code,
      '<div class="field"><span>粘贴对象 ID（每行一个或逗号分隔）</span>' +
      '<textarea id="import-objects" rows="7" placeholder="DRV85001&#10;DRV85002"></textarea></div>' +
      '<p class="warn-text">按对象类型正则校验，自动去重；已在任务中的对象会忽略。</p>',
      [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '导入', cls: 'btn-primary', onClick: function () {
            var ids = $('#import-objects').value.split(/[\s,;]+/).map(function (s) { return s.trim(); }).filter(Boolean);
            if (!ids.length) { toast('请填写对象 ID', 'err'); return; }
            var btn = this;
            setBusy(btn, true);
            api('/tasks/' + task.id + '/items/import', { method: 'POST', body: { object_ids: ids, csv: null } })
              .then(function () {
                closeModal();
                toast('对象已导入', 'ok');
                openTaskDetail(task.id);
                loadTasks();
              }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
          } }
      ]);
  }

  function openAssignTask(task) {
    api('/tasks/assignees').then(function (rows) {
      rows = rows || [];
      openModal('改派任务 · ' + task.task_code,
        '<div class="form-grid">' +
        '  <div class="field"><span>指定打标员</span><select id="assign-user"><option value="">不指定（任务池）</option>' +
        rows.map(function (u) { return '<option value="' + u.id + '">' + esc(u.display_name) + ' · ' + esc(u.username) + '</option>'; }).join('') +
        '</select></div>' +
        '  <div class="field"><span>班组</span><input id="assign-team" value="' + esc(task.team || '') + '" placeholder="如：客服一组"></div>' +
        '</div><p class="warn-text">指定打标员后，任务内未领取项将预分配给该用户；已领取项保持不变。</p>',
        [
          { text: '取消', cls: 'btn-ghost', onClick: closeModal },
          { text: '确认改派', cls: 'btn-primary', onClick: function () {
              var btn = this;
              var userId = $('#assign-user').value ? Number($('#assign-user').value) : null;
              var team = $('#assign-team').value.trim() || null;
              setBusy(btn, true);
              api('/tasks/' + task.id + '/assign', { method: 'POST', body: { assignee_user_id: userId, team: team } })
                .then(function () {
                  closeModal();
                  toast('改派成功', 'ok');
                  openTaskDetail(task.id);
                  loadTasks();
                }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
            } }
        ]);
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function openReviewItem(task, itemId) {
    api('/workbench/items/' + itemId).then(function (ctx) {
      if (!ctx || !ctx.item) { toast('任务项未找到，请刷新后重试', 'err'); return; }
      var item = { item: ctx.item, values: ctx.values || [] };
      var values = item.values || [];
      var body =
        '<div class="review-head"><b class="mono">' + esc(item.item.object_id) + '</b>' +
        '<span>' + esc(task.task_code) + '</span>' + badge(item.item.status) + '</div>' +
        '<div class="review-values">' + values.map(function (v) {
          return '<div class="review-value"><span class="mono">' + esc(v.tag_code) + '</span>' +
            '<b>' + esc(v.tag_value) + '</b>' + (v.remark ? '<div class="cell-sub">理由：' + esc(v.remark) + '</div>' : '') +
            (v.evidence_url ? '<div class="cell-sub mono">' + esc(v.evidence_url) + '</div>' : '') + '</div>';
        }).join('') + '</div>' +
        '<div class="field"><span>审核意见</span><textarea id="review-remark" rows="2" placeholder="通过可留空；驳回必须填写原因"></textarea></div>';
      openModal('任务项审核', body, [
        { text: '取消', cls: 'btn-ghost', onClick: closeModal },
        { text: '驳回', cls: 'btn-danger', onClick: function () { submitReview(task, itemId, false, this); } },
        { text: '通过并写入', cls: 'btn-primary', onClick: function () { submitReview(task, itemId, true, this); } }
      ], 'lg');
    }).catch(function (e) { toast(e.message, 'err'); });
  }

  function submitReview(task, itemId, approved, btn) {
    var remark = $('#review-remark').value.trim();
    if (!approved && !remark) { toast('驳回必须填写原因', 'err'); return; }
    setBusy(btn, true);
    api('/tasks/' + task.id + '/items/' + itemId + '/review', {
      method: 'POST', body: { approved: approved, remark: remark || null }
    }).then(function () {
      closeModal();
      toast(approved ? '审核通过，标签值已写入流水' : '已驳回，等待打标员返工', 'ok');
      openTaskDetail(task.id);
      loadTasks();
    }).catch(function (e) { setBusy(btn, false); toast(e.message, 'err'); });
  }


  /* ============================================================
   * 页面 13：任务监控
   * ============================================================ */
  function renderTaskMonitor() {
    $('#content').innerHTML =
      '<div class="filter-bar card">' +
      (isAdmin() ? '<div class="field"><span>租户</span><select id="tm-tenant"></select></div>' : '') +
      '  <button class="btn btn-ghost" id="tm-refresh">刷新</button>' +
      '</div>' +
      '<div id="tm-stats"></div>' +
      '<div class="task-monitor-grid">' +
      '  <section class="card"><div class="card-head"><div class="card-title">任务状态分布</div></div><div class="card-body" id="tm-status"></div></section>' +
      '  <section class="card"><div class="card-head"><div class="card-title">人员工作量</div></div><div id="tm-workload"></div></section>' +
      '</div>' +
      '<div class="card"><div class="card-head"><div class="card-title">最近任务</div></div><div id="tm-recent"></div></div>';
    if (isAdmin()) {
      fillTenantSelect('tm-tenant');
      $('#tm-tenant').addEventListener('change', function () { setWorkTenant(this.value); loadTaskMonitor(); });
    }
    $('#tm-refresh').addEventListener('click', loadTaskMonitor);
    loadTaskMonitor();
  }

  function loadTaskMonitor() {
    api('/tasks/monitor' + qs({ tenantId: workTenantId() })).then(function (data) {
      var counts = data.status_counts || {};
      var running = (counts.published || 0) + (counts.in_progress || 0) + (counts.reviewing || 0);
      if ($('#tm-stats')) {
        $('#tm-stats').innerHTML = statCards([
          { label: '任务总数', value: data.total || 0, hint: '当前租户', icon: 'tag' },
          { label: '执行中', value: running, hint: '发布 / 进行 / 审核', tone: 'blue', icon: 'clock' },
          { label: '逾期任务', value: data.overdue || 0, hint: '超过截止时间', tone: 'amber', icon: 'pause' },
          { label: '待审核项', value: data.pending_review || 0, hint: '审核队列积压', tone: 'purple', icon: 'shield' }
        ]);
      }
      var order = ['draft', 'published', 'in_progress', 'reviewing', 'completed', 'cancelled'];
      var max = 1;
      order.forEach(function (k) { max = Math.max(max, counts[k] || 0); });
      $('#tm-status').innerHTML = order.map(function (k) {
        var n = counts[k] || 0;
        var pct = Math.round(n / max * 100);
        return '<div class="tm-status-row"><span>' + badge(k) + '</span>' +
          '<div class="progress-track' + (n ? '' : ' is-zero') + '"><i style="width:' + pct + '%"></i></div><b>' + n + '</b></div>';
      }).join('');

      var workloads = data.workloads || [];
      if (!workloads.length) {
        $('#tm-workload').innerHTML = emptyState('暂无人员数据', '任务被领取后显示工作量');
      } else {
        var html = '<div class="table-wrap"><table><thead><tr><th>打标员</th><th class="num">领取</th><th class="num">完成</th><th class="num">驳回</th></tr></thead><tbody>';
        workloads.forEach(function (w) {
          html += '<tr><td>' + esc(w.display_name || '--') + '</td><td class="num">' + w.assigned + '</td>' +
            '<td class="num">' + w.completed + '</td><td class="num">' + w.rejected + '</td></tr>';
        });
        $('#tm-workload').innerHTML = html + '</tbody></table></div>';
      }

      var recent = data.recent_tasks || [];
      if (!recent.length) { $('#tm-recent').innerHTML = emptyState('暂无任务', '创建任务后这里展示最近任务'); return; }
      var rows = '<div class="table-wrap"><table><thead><tr><th>任务</th><th>状态</th><th>进度</th><th>截止</th><th>创建人</th><th>操作</th></tr></thead><tbody>';
      recent.forEach(function (x) {
        var t = x.task || x; var s = x.stats || {};
        rows += '<tr><td><div class="cell-strong">' + esc(t.title) + '</div><div class="cell-sub mono">' + esc(t.task_code) + '</div></td>' +
          '<td>' + badge(t.status) + '</td><td>' + taskProgress(s, t.status) + '</td><td class="nowrap">' + fmtDt(t.due_at) + '</td><td>' + esc(t.created_by) + '</td>' +
          '<td><button class="btn btn-ghost btn-sm" data-tm-open="' + t.id + '">详情</button></td></tr>';
      });
      $('#tm-recent').innerHTML = rows + '</tbody></table></div>';
      $('#tm-recent').querySelectorAll('[data-tm-open]').forEach(function (btn) {
        btn.addEventListener('click', function () { openTaskDetail(Number(btn.getAttribute('data-tm-open'))); });
      });
    }).catch(function (e) {
      if ($('#tm-status')) $('#tm-status').innerHTML = emptyState('加载失败', e.message);
    });
  }


  export { renderMyTasks, renderTasks, renderTaskMonitor, openTaskDetail };
