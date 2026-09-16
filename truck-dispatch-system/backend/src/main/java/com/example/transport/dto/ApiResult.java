package com.example.transport.dto;

/**
 * 统一的写操作响应结构（POST 类接口返回，方便前端弹出成功/失败提示）
 *
 * @param success 是否成功
 * @param message 提示信息
 * @param data    附带的数据（可为空）
 * @param <T>     附带数据类型
 */
public record ApiResult<T>(boolean success, String message, T data) {

    /** 成功响应（不带数据） */
    public static <T> ApiResult<T> ok(String message) {
        return new ApiResult<>(true, message, null);
    }

    /** 成功响应（带数据） */
    public static <T> ApiResult<T> ok(String message, T data) {
        return new ApiResult<>(true, message, data);
    }

    /** 失败响应 */
    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(false, message, null);
    }
}
