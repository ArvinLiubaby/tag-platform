/* 标签画像平台 · 控制台路由入口 */
import * as core from './core.js';
import './ui.js';
import { renderTags } from './pages/tags.js?v=20261111-4';
import { renderMonitor } from './pages/monitor.js?v=20261111-3';
import { renderAudit, renderTenants, renderLogs, renderUsers, renderRoles, openChangeOwnPassword } from './pages/governance.js';
import { renderRecords, renderProfile, renderDocs, renderDashboard, stopDashboard } from './pages/analytics.js?v=20261113-1';
import { renderMarket } from './pages/market.js';
import { renderCompose } from './pages/compose.js';
import { renderTagViewer } from './pages/tagviewer.js?v=20261111-1';
import { renderWorkbench } from './pages/workbench.js';
import { renderMyTasks, renderTasks, renderTaskMonitor } from './pages/tasks.js';

const { $, state, PAGE_TITLES, PAGE_SUBTITLES, bindLogin, loadAppVersion, loadPermissions,
  showLogin, signOut, api, emptyState, toast, USER_KEY } = core;

  var RENDERERS = {
    workbench: renderWorkbench, 'my-tasks': renderMyTasks, tasks: renderTasks,
    'task-monitor': renderTaskMonitor,
    dashboard: renderDashboard, tags: renderTags, 'tag-compose': renderCompose, 'tag-viewer': renderTagViewer, market: renderMarket, audit: renderAudit, records: renderRecords,
    profile: renderProfile, monitor: renderMonitor, tenants: renderTenants, users: renderUsers, roles: renderRoles,
    logs: renderLogs, docs: renderDocs
  };

  /** 当前账号有权限的默认首页：取菜单列表里第一个有渲染器的页面 */
  function defaultRoute() {
    var menus = state.menus || [];
    for (var i = 0; i < menus.length; i++) {
      if (RENDERERS[menus[i]]) return menus[i];
    }
    return null;
  }

  function route() {
    stopDashboard();
    if (!state.token) { showLogin(); return; }
    core.showApp();
    var name = (location.hash || '#/tags').replace(/^#\//, '').split('?')[0];
    if (!RENDERERS[name]) name = 'tags';
    if (state.menus !== null && state.menus.indexOf(name) < 0) {
      // 切换低权限账号后，浏览器仍停留在上一个账号的页面：改跳该账号的默认首页，不停在无权限页
      var fallback = defaultRoute();
      if (fallback) {
        var denied = PAGE_TITLES[name] || name;
        var target = PAGE_TITLES[fallback] || fallback;
        location.hash = '#/' + fallback;
        // 延后一拍：避免登录成功的「欢迎回来」提示在本轮同步代码里把跳转提示覆盖掉
        setTimeout(function () {
          toast('当前账号无权访问「' + denied + '」，已跳转到「' + target + '」');
        }, 0);
        return;
      }
      state.route = name;
      $('#page-title').textContent = PAGE_TITLES[name];
      $('#page-subtitle').textContent = PAGE_SUBTITLES[name] || '';
      $('#content').innerHTML = '<div class="card">' +
        emptyState('无访问权限', '当前角色无权访问该页面') + '</div>';
      return;
    }
    state.route = name;
    $('#page-title').textContent = PAGE_TITLES[name];
    $('#page-subtitle').textContent = PAGE_SUBTITLES[name] || '';
    $('#nav').querySelectorAll('a').forEach(function (a) {
      a.classList.toggle('active', a.getAttribute('data-nav') === name);
    });
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
        localStorage.setItem(USER_KEY, JSON.stringify(me));
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
