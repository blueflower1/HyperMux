package com.hypermux.server.common;

import com.hypermux.server.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截器 + 静态资源（管理面板同源部署）。
 * 顺序：hypermux.static-dirs（docker 挂载的外部 admin-dist）优先于 classpath:/static/。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String staticDirs;
    private final String corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${hypermux.static-dirs:}") String staticDirs,
                     @Value("${hypermux.cors-origins:*}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.staticDirs = staticDirs == null ? "" : staticDirs;
        this.corsOrigins = corsOrigins == null ? "*" : corsOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/app/**", "/api/admin/**")
                .excludePathPatterns("/api/app/auth/login", "/api/admin/auth/login",
                        // 扫码登录：TV 创建登录码与轮询状态是匿名端点（scan/confirm 需手机端 token，不豁免）
                        "/api/app/auth/qr/create", "/api/app/auth/qr/status",
                        // 应用自更新：未登录也要能检查/下载（契约 §3.11）
                        "/api/app/update/check", "/api/app/update/download");
    }

    /**
     * CORS：web 观看端支持「自定义/预设服务器」跨源访问 API。
     * 鉴权走 Authorization 头（JWT），不吃 Cookie，故 allowCredentials=false + 通配源不引入 CSRF 面；
     * preflight OPTIONS 由框架在拦截器之前直接应答。可用 hypermux.cors-origins 收紧（逗号分隔，置空则禁用）。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsOrigins.isBlank()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /** SPA 静态托管：根路径转发到后台前端 index.html（前端使用 hash 路由，无需服务端兜底）。 */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        List<String> locations = new ArrayList<>();
        if (!staticDirs.isBlank()) {
            for (String dir : staticDirs.split(",")) {
                String d = dir.trim();
                if (d.isBlank()) {
                    continue;
                }
                if (!d.endsWith("/")) {
                    d = d + "/";
                }
                if (!d.startsWith("file:")) {
                    d = "file:" + d;
                }
                locations.add(d);
            }
        }
        locations.add("classpath:/static/");
        registry.addResourceHandler("/**").addResourceLocations(locations.toArray(new String[0]));
    }
}
