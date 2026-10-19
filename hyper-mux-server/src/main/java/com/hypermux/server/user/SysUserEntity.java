package com.hypermux.server.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** sys_user：管理端(role=1) / App 端(role=0) 共用。 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    public static final int ROLE_APP_USER = 0;
    public static final int ROLE_ADMIN = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickName;

    private String avatarUrl;

    private Integer role;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private OffsetDateTime lastLoginTime;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}