package com.hypermux.server.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 自动填充 create_time / update_time。 */
@Component
public class TimeFillHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        strictInsertFill(metaObject, "createTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", OffsetDateTime.class, OffsetDateTime.now());
    }
}