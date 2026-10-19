package com.hypermux.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** hypermux.crypto.*：token 落库加密密钥。 */
@ConfigurationProperties(prefix = "hypermux.crypto")
public class TokenCipherProperties {

    private String tokenKey = "dev-crypto-secret-change-me-please-012345";

    public String getTokenKey() {
        return tokenKey;
    }

    public void setTokenKey(String tokenKey) {
        this.tokenKey = tokenKey;
    }
}
