package com.tagplatform.console;

import com.tagplatform.console.security.PublicConsoleEndpoint;
import com.tagplatform.console.security.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台权限覆盖：所有 console 接口必须声明权限点或公共端点，且权限点必须存在于统一目录。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_permission_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_permission_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class PermissionCoverageTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void allConsoleHandlersDeclareKnownPermission() {
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String path : entry.getKey().getPatternValues()) {
                if (!path.startsWith("/api/v1/console") && !path.startsWith("/console/api")) {
                    continue;
                }
                HandlerMethod method = entry.getValue();
                boolean isPublic = method.getMethodAnnotation(PublicConsoleEndpoint.class) != null
                        || method.getBeanType().isAnnotationPresent(PublicConsoleEndpoint.class);
                RequirePermission permission = method.getMethodAnnotation(RequirePermission.class);
                if (permission == null) {
                    permission = method.getBeanType().getAnnotation(RequirePermission.class);
                }
                assertThat(isPublic || permission != null)
                        .as("console endpoint must declare permission: " + method)
                        .isTrue();
                if (permission != null) {
                    assertThat(permissionExists(permission.value()))
                            .as("permission must exist in RolePermissionCatalog: " + permission.value())
                            .isTrue();
                }
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(20);
    }

    private boolean permissionExists(String permission) {
        RolePermissionCatalog.Catalog catalog = RolePermissionCatalog.catalog();
        return catalog.pageGroups().stream().flatMap(group -> group.items().stream())
                .anyMatch(item -> permission.equals(item.code()))
                || catalog.operationGroups().stream().flatMap(group -> group.items().stream())
                .anyMatch(item -> permission.equals(item.code()));
    }
}
