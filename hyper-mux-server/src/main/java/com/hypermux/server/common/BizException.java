package com.hypermux.server.common;

/** 业务异常：由 {@link GlobalExceptionHandler} 统一转成 R（HTTP 状态 = code）。 */
public class BizException extends RuntimeException {

    private final int code;
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode.code(), errorCode.msg(), null);
    }

    public BizException(ErrorCode errorCode, String msg) {
        this(errorCode.code(), msg, null);
    }

    public BizException(ErrorCode errorCode, String msg, Object data) {
        super(msg);
        this.code = errorCode.code();
        this.data = data;
    }

    public BizException(int code, String msg, Object data) {
        super(msg);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }

    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
