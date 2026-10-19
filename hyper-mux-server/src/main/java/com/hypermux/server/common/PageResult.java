package com.hypermux.server.common;

import java.util.List;

/** 分页结果（30-api-contract §3.3）。 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }
}
