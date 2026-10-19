package com.hypermux.server.apprelease;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hypermux.apk.*：应用自更新 APK 的存放目录（管理端上传后落此处，App 侧流式下载）。
 */
@ConfigurationProperties(prefix = "hypermux.apk")
public class AppReleaseProperties {

    /** APK 存放目录（每平台一个文件：{platform}-latest.apk） */
    private String dir = System.getProperty("user.home") + "/.hypermux/apks";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
