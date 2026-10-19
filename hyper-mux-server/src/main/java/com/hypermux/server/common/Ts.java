package com.hypermux.server.common;

import java.time.OffsetDateTime;

/** 时间工具：统一毫秒时间戳出口（契约：时间一律毫秒）。 */
public final class Ts {

    private Ts() {
    }

    public static long milli(OffsetDateTime t) {
        return t == null ? 0L : t.toInstant().toEpochMilli();
    }
}
