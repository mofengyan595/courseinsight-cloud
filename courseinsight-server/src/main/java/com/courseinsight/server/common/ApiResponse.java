package com.courseinsight.server.common;

/**
 * 所有接口统一使用的响应结构。
 *
 * @param code    业务状态码：成功为 0，失败时使用对应的 HTTP 状态码
 * @param message 给调用方看的结果说明
 * @param data    接口真正返回的数据；失败时通常为 null
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
