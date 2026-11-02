/* 标签画像平台 · 控制台路由入口 */
import * as core from './core.js';
import './ui.js';
import { renderTags } from './pages/tags.js';
import { renderAudit, renderTenants, renderLogs, renderUsers, renderRoles, openChangeOwnPassword } from './pages/governance.js';
import { renderRecords, renderProfile, renderDocs, renderDashboard, stopDashboard } from './pages/analytics.js';
import { renderMarket } from './pages/market.js';
import { renderCompose } from './pages/compose.js';
import { renderTagViewer } from './pages/tagviewer.js';
import { renderWorkbench } from './pages/workbench.js';
import { renderMyTasks, renderTasks, renderTaskMonitor } from './pages/tasks.js';

const { $, state, PAGE_TITLES, PAGE_SUBTITLES, bindLogin, loadAppVersion, loadPermissions,
  showLogin, signOut, api, emptyState, USER_KEY } = core;

  var RENDERERS = {
    workbench: renderWorkbench, 'my-tasks': renderMyTasks, tasks: renderTasks,
    'task-monitor': renderTaskMonitor,
    dashboard: renderDashboard, tags: renderTags, 'tag-compose': renderCompose, 'tag-viewer': renderTagViewer, market: renderMarket, audit: renderAudit, records: renderRecords,
    profile: renderProfile, tenants: renderTenants, users: renderUsers, roles: renderRoles,
    logs: renderLogs, docs: renderDocs
  };

  function route() {
    stopDashboard();
    if (!state.token) { showLogin(); return; }
    core.showApp();
    var name = (location.hash || '#/tags').replace(/^#\//, '').split('?')[0];
    if (!RENDERERS[name]) name = 'tags';
    state.route = name;
    $('#page-title').textContent = PAGE_TITLES[name];
    $('#page-subtitle').textContent = PAGE_SUBTITLES[name] || '';
    $('#nav').querySelectorAll('a').forEach(function (a) {
      a.classList.toggle('active', a.getAttribute('data-nav') === name);
    });
    if (state.menus !== null && state.menus.indexOf(name) < 0) {
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '当前角色无权访问该页面') + '</div>';
      return;
    }
    RENDERERS[name]();
  }

  function boot() {
    bindLogin(route);
    $("#btn-change-password").addEventListener("click", openChangeOwnPassword);
    loadAppVersion();
    window.addEventListener('hashchange', route);
    document.addEventListener('fullscreenchange', function () {
      var btn = $('#dash-fullscreen');
      if (btn) btn.textContent = document.fullscreenElement ? '退出全屏' : '全屏';
    });
    if (state.token) {
      // 会话恢复：校验 Token 有效性
      api('/auth/me').then(function (me) {
        state.user = me;
        sessionStorage.setItem(USER_KEY, JSON.stringify(me));
        return loadPermissions();
      }).then(function () {
        route();
      }).catch(function () {
        signOut(true);
        route();
      });
    } else {
      showLogin();
    }
  }

  document.addEventListener('DOMContentLoaded', boot);
