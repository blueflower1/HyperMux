package com.hypermux.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** hypermux.jwt.*：签名密钥与两端 token 有效期。 */
@ConfigurationProperties(prefix = "hypermux.jwt")
public class JwtProperties {

    private String secret = "dev-secret-change-me-please-0123456789";
    private Duration appTtl = Duration.ofDays(7);
    private Duration adminTtl = Duration.ofHours(12);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAppTtl() {
        return appTtl;
    }

    public void setAppTtl(Duration appTtl) {
        this.appTtl = appTtl;
    }

    public Duration getAdminTtl() {
        return adminTtl;
    }

    public void setAdminTtl(Duration adminTtl) {
        this.adminTtl = adminTtl;
    }
}
