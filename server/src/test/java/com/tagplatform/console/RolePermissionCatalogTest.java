package com.tagplatform.console;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionCatalogTest {

    @Test
    void catalogCoversBuiltInRolesAndKeyRestrictions() {
        RolePermissionCatalog.Catalog catalog = RolePermissionCatalog.catalog();
        assertThat(catalog.roles()).extracting(RolePermissionCatalog.RoleInfo::code)
                .containsExactly("ADMIN", "TENANT_ADMIN", "TASK_MANAGER", "TAGGER", "AUDITOR", "VIEWER");

        Set<String> roleCodes = catalog.roles().stream()
                .map(RolePermissionCatalog.RoleInfo::code).collect(Collectors.toSet());
        catalog.pageGroups().forEach(group -> group.items().forEach(item ->
                assertThat(roleCodes).containsAll(item.roles())));
        catalog.operationGroups().forEach(group -> group.items().forEach(item ->
                assertThat(roleCodes).containsAll(item.roles())));

        assertThat(item(catalog, "page.users").roles()).containsExactly("ADMIN", "TENANT_ADMIN");
        assertThat(item(catalog, "page.dashboard").roles()).doesNotContain("TENANT_ADMIN");
        assertThat(item(catalog, "op.tag.manage").roles()).containsExactly("ADMIN", "TENANT_ADMIN");
        assertThat(item(catalog, "op.tag.version.rollback").roles()).containsExactly("ADMIN", "TENANT_ADMIN");
        assertThat(item(catalog, "op.user.manage").roles()).containsExactly("ADMIN", "TENANT_ADMIN");
        assertThat(item(catalog, "page.tenants").roles()).containsExactly("ADMIN", "TENANT_ADMIN");
        assertThat(item(catalog, "page.audit_pending").roles()).containsExactly("ADMIN", "TENANT_ADMIN", "AUDITOR");
        assertThat(item(catalog, "page.logs").roles()).containsExactly("ADMIN", "TENANT_ADMIN", "AUDITOR");

        assertThat(RolePermissionCatalog.hasPermission("TAGGER", "page.workbench")).isTrue();
        assertThat(RolePermissionCatalog.hasPermission("TAGGER", "page.tenants")).isFalse();
        assertThat(RolePermissionCatalog.menusForRole("TAGGER")).contains("workbench", "tags", "market")
                .doesNotContain("tenants", "users", "logs", "audit");
        assertThat(RolePermissionCatalog.menusForRole("TENANT_ADMIN")).contains("users", "roles", "tenants")
                .doesNotContain("dashboard");
        assertThat(RolePermissionCatalog.permissionsForRole("VIEWER")).doesNotContain("op.tag.manage");
    }

    private RolePermissionCatalog.PermissionItem item(RolePermissionCatalog.Catalog catalog, String code) {
        return catalog.pageGroups().stream().flatMap(g -> g.items().stream())
                .filter(i -> code.equals(i.code())).findFirst()
                .or(() -> catalog.operationGroups().stream().flatMap(g -> g.items().stream())
                        .filter(i -> code.equals(i.code())).findFirst())
                .orElseThrow();
    }
}
