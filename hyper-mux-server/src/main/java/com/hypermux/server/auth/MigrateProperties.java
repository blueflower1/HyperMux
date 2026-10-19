package com.hypermux.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** hypermux.migrate.*：启动迁移与管理员自举。 */
@ConfigurationProperties(prefix = "hypermux.migrate")
public class MigrateProperties {

    private boolean enabled = true;
    private String adminBootstrapUsername = "admin";
    private String adminBootstrapPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminBootstrapUsername() {
        return adminBootstrapUsername;
    }

    public void setAdminBootstrapUsername(String v) {
        this.adminBootstrapUsername = v;
    }

    public String getAdminBootstrapPassword() {
        return adminBootstrapPassword;
    }

    public void setAdminBootstrapPassword(String v) {
        this.adminBootstrapPassword = v;
    }
}
