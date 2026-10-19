package com.hypermux.server.common;

/**
 * 统一响应包装（30-api-contract §2）：{@code {code,msg,data}}，code==0 表示成功。
 */
public record R<T>(int code, String msg, T data) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static R<Void> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
