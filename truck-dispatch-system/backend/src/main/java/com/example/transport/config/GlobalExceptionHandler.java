package com.example.transport.config;

import com.example.transport.dto.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把参数校验类异常统一包装成 JSON 失败响应，
 * 避免默认的白页错误堆栈直接暴露给前端；未预期异常的完整堆栈写入后端日志便于排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 非法参数（业务校验失败）-> 400 风格的 {success:false, message:...} */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResult.fail(e.getMessage());
    }

    /** 其它未预期异常 -> 500 风格响应，同时把完整堆栈打到后端日志 */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception e) {
        log.error("接口处理发生未预期异常", e);
        return ApiResult.fail("服务器内部错误：" + e.getMessage());
    }
}
