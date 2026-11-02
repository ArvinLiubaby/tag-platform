package com.tagplatform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器：把 Controller/Service 层抛出的异常统一收敛为 {code, message, request_id, data}。
 * - BizException：按 ErrorCode.httpStatus 返回（1xxx→401/403，2xxx/3xxx→200，频控→429，5xxx→500），data 原样透传
 * - 参数解析类异常：2xxx（PARAM_INVALID），HTTP 200
 * - 其他异常：5001（SYSTEM_ERROR），HTTP 500，记录堆栈
 * 说明：过滤器（OpenApiAuthFilter/ConsoleApiAuthFilter）在进入 MVC 前自行输出统一错误响应，不经过本处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode.httpStatus() >= 500) {
            log.error("业务异常(5xx) code={} msg={}", errorCode.code(), e.getMessage(), e);
        } else {
            log.warn("业务异常 code={} msg={} request_id={}", errorCode.code(), e.getMessage(),
                    RequestContext.getRequestId());
        }
        return ResponseEntity.status(errorCode.httpStatus())
                .body(new ApiResponse<>(errorCode.code(), e.getMessage(), RequestContext.getRequestId(), e.getData()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Object>> handleParam(Exception e) {
        log.warn("参数异常 msg={} request_id={}", e.getMessage(), RequestContext.getRequestId());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception e) {
        log.error("系统异常 request_id={}", RequestContext.getRequestId(), e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR));
    }
}
