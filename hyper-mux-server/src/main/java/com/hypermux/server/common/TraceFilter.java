package com.hypermux.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 每请求生成 traceId：写入 MDC（日志）与响应头 X-Trace-Id（排查用）。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE = "trace";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String trace = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MDC.put(TRACE, trace);
        response.setHeader(HEADER, trace);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE);
        }
    }
}
