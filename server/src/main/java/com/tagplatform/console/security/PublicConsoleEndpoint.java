package com.tagplatform.console.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 控制台无需权限点的接口；通常用于登录、当前用户、版本等公共端点。
 * 控制台 Token 过滤器仍会对非登录/版本接口进行登录态校验。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicConsoleEndpoint {
}
