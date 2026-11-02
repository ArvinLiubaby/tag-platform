package com.tagplatform.console.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 控制台权限点声明。方法级优先于类级默认权限。
 * 未声明权限点且未标记 @PublicConsoleEndpoint 的控制台接口会被默认拒绝。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
