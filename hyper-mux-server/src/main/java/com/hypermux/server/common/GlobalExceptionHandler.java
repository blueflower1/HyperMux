package com.hypermux.server.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：HTTP 状态码与业务 code 保持一致（>=400），见 30-api-contract §2。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Object>> biz(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        HttpStatus status = e.code() >= 400 ? HttpStatus.resolve(e.code()) : null;
        // 4xx 业务异常按 WARN 记录（带请求方法/路径），便于前端报错时后端可查
        log.warn("业务异常 {} {} -> code={} msg={}",
                request.getMethod(), request.getRequestURI(), e.code(), e.getMessage());
        R<Object> body = e.data() == null
                ? R.fail(e.code(), e.getMessage())
                : new R<>(e.code(), e.getMessage(), e.data());
        return ResponseEntity.status(status == null ? HttpStatus.OK : status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> valid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = (fe != null) ? fe.getField() + "：" + fe.getDefaultMessage() : ErrorCode.BAD_REQUEST.msg();
        return ResponseEntity.badRequest().body(R.fail(ErrorCode.BAD_REQUEST.code(), msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(R.fail(ErrorCode.BAD_REQUEST.code(), "请求体无法解析"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<R<Void>> integrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(R.fail(ErrorCode.CONFLICT.code(), "与已有数据冲突"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<R<Void>> dataAccess(DataAccessException e) {
        log.error("数据库访问异常 trace={}", MDC.get("trace"), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.INTERNAL.code(), "数据库暂时不可用"));
    }

    /** 路径不存在（静态资源兜底）：返回 404 而不打 ERROR 堆栈。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<R<Void>> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(ErrorCode.NOT_FOUND.code(), "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e) {
        log.error("未处理异常 trace={}", MDC.get("trace"), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.INTERNAL.code(), ErrorCode.INTERNAL.msg()));
    }
}
