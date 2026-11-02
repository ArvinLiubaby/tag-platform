package com.tagplatform.console;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置角色与权限矩阵（第一阶段只读口径）。
 * 权限判断仍由各接口的 RBAC 代码执行；本类只用于控制台「角色与权限」页展示。
 */
public final class RolePermissionCatalog {

    private RolePermissionCatalog() {
    }

    private static final List<RoleInfo> ROLES = List.of(
            new RoleInfo("ADMIN", "平台管理员", "平台级管理，可访问全部租户与平台配置", false, false, false),
            new RoleInfo("TENANT_ADMIN", "租户管理员", "租户级管理，可管理本租户用户、工作台与任务", true, true, false),
            new RoleInfo("TASK_MANAGER", "任务管理员", "本租户任务创建、派发、审核与监控", true, false, true),
            new RoleInfo("TAGGER", "打标员", "本租户工作台打标与任务执行", true, false, true),
            new RoleInfo("AUDITOR", "审核员", "本租户任务审核与只读查看", true, false, true),
            new RoleInfo("VIEWER", "只读", "只读画像、标签与打标记录", true, false, true)
    );

    private static final List<PermissionGroup> PAGE_GROUPS = List.of(
            new PermissionGroup("page", "页面访问", "控制台导航与页面入口；租户管理员的数据范围仅限本租户", List.of(
                    item("page.dashboard", "数据看板", "全局开放 API 调用与标签运营看板（不含租户管理员）",
                            "ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.workbench", "打标工作台", "对象搜索、画像 overlay 与动态打标表单",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR"),
                    item("page.my_tasks", "我的任务", "待办对象与本人/任务池任务",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR"),
                    item("page.tasks", "任务管理", "任务创建、导入、发布、派发、审核与流转",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "AUDITOR"),
                    item("page.task_monitor", "任务监控", "任务进度、逾期、待审核与人员工作量",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "AUDITOR"),
                    item("page.tags", "标签字典", "标签定义、值域、上下线、写入开关与版本历史",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.tag_compose", "标签组合", "用已有标签编排派生标签，预检后发布；值由查询期实时计算",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.tag_view", "标签查看", "按标签浏览当前值与打标流水，含组合标签的实时计算结果",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.audit_pending", "待审核队列", "历史标签值只读队列（值审核已停用）",
                            "ADMIN", "TENANT_ADMIN", "AUDITOR"),
                    item("page.records", "打标记录", "按对象与时间检索打标流水",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.profile", "对象画像", "读取对象当前标签值",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.tenants", "租户与凭证", "租户信息与 AppKey 凭证；租户管理员只看本租户",
                            "ADMIN", "TENANT_ADMIN"),
                    item("page.users", "用户管理", "控制台账号与角色分配",
                            "ADMIN", "TENANT_ADMIN"),
                    item("page.roles", "角色与权限", "只读查看内置角色能力矩阵",
                            "ADMIN", "TENANT_ADMIN"),
                    item("page.logs", "审计日志", "平台/租户操作审计追溯；租户管理员仅本租户",
                            "ADMIN", "TENANT_ADMIN", "AUDITOR"),
                    item("page.market", "标签市场", "浏览市场标签；租户管理员可上架与申请",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("page.docs", "接口文档", "开放 API 接入说明",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER")
            ))
    );

    private static final List<PermissionGroup> OPERATION_GROUPS = List.of(
            new PermissionGroup("operation", "业务操作", "写入、任务、用户与治理操作；实际接口仍做租户范围校验", List.of(
                    item("op.workbench.submit", "工作台打标提交", "提交标签值；敏感标签进入审核，普通标签直写",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER"),
                    item("op.task.manage", "任务创建 / 发布 / 派发 / 取消", "管理任务全生命周期",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER"),
                    item("op.task.review", "任务项审核", "任务项通过、驳回与返工",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "AUDITOR"),
                    item("op.user.manage", "用户账号管理", "平台管理员管理全部账号；租户管理员仅本租户操作类角色",
                            "ADMIN", "TENANT_ADMIN"),
                    item("op.market.list", "标签上架市场", "租户管理员上架本租户可见的在线标签",
                            "TENANT_ADMIN"),
                    item("op.market.apply", "申请市场标签", "租户管理员为所在租户提交使用申请",
                            "TENANT_ADMIN"),
                    item("op.market.review", "标签市场审批", "平台管理员审批租户使用申请并扩展适用租户",
                            "ADMIN"),
                    item("op.market.requests", "查看市场申请", "平台管理员查看全部；租户管理员查看本租户申请",
                            "ADMIN", "TENANT_ADMIN"),
                    item("op.tag.manage", "标签定义维护", "平台管理员管理全部；租户管理员仅限归属本租户（tenant_id 第一个租户）",
                            "ADMIN", "TENANT_ADMIN"),
                    item("op.tag.audit", "标签定义审核", "标签注册/变更审核决策",
                            "ADMIN"),
                    item("op.tag.version.view", "标签版本查看", "查看版本历史与字段 diff",
                            "ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER"),
                    item("op.tag.version.rollback", "标签版本回滚", "平台管理员全部；租户管理员仅限归属本租户",
                            "ADMIN", "TENANT_ADMIN"),
                    item("op.composition.manage", "标签组合维护与发布", "平台管理员管理全部；租户管理员仅限归属本租户的组合标签",
                            "ADMIN", "TENANT_ADMIN"),
                    item("op.tenant.manage", "租户与凭证维护", "新建租户、启停租户、创建/重置/启停凭证",
                            "ADMIN"),
                    item("op.audit.value", "标签值审核处理", "历史值审核接口已停用（3013），仅保留只读队列",
                            "ADMIN", "AUDITOR")
            ))
    );

    /** 导航菜单 -> 页面权限点。前端菜单由 /auth/permissions 返回的 menus 驱动。 */
    private static final Map<String, String> MENU_PERMISSIONS = new LinkedHashMap<>();

    static {
        MENU_PERMISSIONS.put("workbench", "page.workbench");
        MENU_PERMISSIONS.put("my-tasks", "page.my_tasks");
        MENU_PERMISSIONS.put("tasks", "page.tasks");
        MENU_PERMISSIONS.put("task-monitor", "page.task_monitor");
        MENU_PERMISSIONS.put("dashboard", "page.dashboard");
        MENU_PERMISSIONS.put("tags", "page.tags");
        MENU_PERMISSIONS.put("tag-compose", "page.tag_compose");
        MENU_PERMISSIONS.put("tag-viewer", "page.tag_view");
        MENU_PERMISSIONS.put("market", "page.market");
        MENU_PERMISSIONS.put("audit", "page.audit_pending");
        MENU_PERMISSIONS.put("records", "page.records");
        MENU_PERMISSIONS.put("profile", "page.profile");
        MENU_PERMISSIONS.put("tenants", "page.tenants");
        MENU_PERMISSIONS.put("roles", "page.roles");
        MENU_PERMISSIONS.put("users", "page.users");
        MENU_PERMISSIONS.put("logs", "page.logs");
        MENU_PERMISSIONS.put("docs", "page.docs");
    }

    public static Catalog catalog() {
        return new Catalog(ROLES, PAGE_GROUPS, OPERATION_GROUPS);
    }

    public static boolean hasPermission(String role, String permissionCode) {
        if (role == null || permissionCode == null) {
            return false;
        }
        return allItems().stream()
                .anyMatch(item -> permissionCode.equals(item.code())
                        && item.roles().contains(role));
    }

    public static Set<String> permissionsForRole(String role) {
        Set<String> permissions = new LinkedHashSet<>();
        allItems().stream()
                .filter(item -> item.roles().contains(role))
                .forEach(item -> permissions.add(item.code()));
        return permissions;
    }

    public static List<String> menusForRole(String role) {
        return MENU_PERMISSIONS.entrySet().stream()
                .filter(entry -> hasPermission(role, entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<PermissionItem> allItems() {
        return java.util.stream.Stream.concat(
                        PAGE_GROUPS.stream().flatMap(group -> group.items().stream()),
                        OPERATION_GROUPS.stream().flatMap(group -> group.items().stream()))
                .toList();
    }

    private static PermissionItem item(String code, String name, String description, String... roles) {
        return new PermissionItem(code, name, description, List.of(roles));
    }

    public record RoleInfo(String code, String name, String description, boolean tenantScoped,
                           boolean onePerTenant, boolean assignableByTenantAdmin) {
    }

    public record PermissionItem(String code, String name, String description, List<String> roles) {
    }

    public record PermissionGroup(String code, String name, String description, List<PermissionItem> items) {
    }

    public record Catalog(List<RoleInfo> roles, List<PermissionGroup> pageGroups,
                          List<PermissionGroup> operationGroups) {
    }
}
