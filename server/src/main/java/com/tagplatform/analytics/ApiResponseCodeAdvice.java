package com.tagplatform.analytics;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把统一响应中的业务码写入请求上下文，供开放 API 埋点判定成功/失败。
 * 过滤器直出的错误（鉴权失败等）不经过本 Advice，会由 OpenApiAuthFilter 主动写入。
 */
@RestControllerAdvice
public class ApiResponseCodeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> apiResponse) {
            RequestContext.setBizCode(apiResponse.code());
        }
        return body;
    }
}
