package com.hypermux.server.apprelease;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hypermux.server.common.BizException;
import com.hypermux.server.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 应用自更新（契约 §3.11）：管理端发布（multipart 上传 APK）→ 服务器存盘 + 落{@code app_release}；
 * App 侧按 versionCode 检查更新并流式下载。每个平台仅保留最新一条，文件覆盖式发布。
 */
@Slf4j
@Service
public class AppReleaseService {

    /** 支持的平台（表主键白名单，防任意文件命名） */
    public static final List<String> PLATFORMS = List.of("mobile", "tv");

    private static final long MAX_APK_BYTES = 300L * 1024 * 1024;

    private final AppReleaseMapper mapper;
    private final AppReleaseProperties props;

    public AppReleaseService(AppReleaseMapper mapper, AppReleaseProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** 最新发布（无则 null —— 自托管服务器未发布时 App 侧表现为"无更新"）。 */
    public AppReleaseEntity latest(String platform) {
        String p = normalizePlatform(platform);
        return mapper.selectById(p);
    }

    /**
     * 发布新版本：校验 → 先写 .uploading 临时文件（崩溃可清理）→ 原子替换正式文件 → upsert 记录。
     * 顺序上"文件成功"才落库，保证记录存在时文件必然存在。
     */
    @Transactional
    public AppReleaseEntity publish(String platform, MultipartFile file, int versionCode,
                                    String versionName, String releaseNotes, boolean mandatory) {
        String p = normalizePlatform(platform);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "APK 文件不能为空");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!original.endsWith(".apk")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 .apk 文件");
        }
        long size = file.getSize();
        if (size > MAX_APK_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "APK 超过 300MB 上限");
        }
        String vName = versionName == null ? "" : versionName.trim();
        if (vName.isEmpty() || vName.length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "versionName 必填且不超过 32 字");
        }
        if (versionCode <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "versionCode 必须大于 0");
        }

        AppReleaseEntity existing = mapper.selectById(p);
        if (existing != null && existing.getVersionCode() != null && versionCode <= existing.getVersionCode()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "新版本号必须大于当前已发布版本（当前 " + existing.getVersionCode() + "）");
        }

        String fileName = p + "-latest.apk";
        Path dir;
        try {
            dir = Path.of(props.getDir());
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("APK 目录不可用: " + props.getDir(), e);
        }
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".uploading");
        String sha256 = null;
        try {
            file.transferTo(tmp);
            sha256 = sha256Of(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 原子替换失败的理论 fallback（同目录不跨设备，基本不会触发）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new BizException(ErrorCode.INTERNAL, "APK 保存失败: " + e.getMessage());
        }

        AppReleaseEntity entity = existing != null ? existing : new AppReleaseEntity();
        entity.setPlatform(p);
        entity.setVersionCode(versionCode);
        entity.setVersionName(vName);
        entity.setFileName(fileName);
        entity.setSha256(sha256);
        entity.setSizeBytes(size);
        entity.setReleaseNotes(releaseNotes == null ? "" : releaseNotes.trim());
        entity.setMandatory(mandatory ? 1 : 0);
        if (existing == null) {
            mapper.insert(entity);
        } else {
            // 显式按主键列更新（platform 为 INPUT 主键，updateById 依赖框架映射，这里更直白）
            mapper.update(entity, new LambdaUpdateWrapper<AppReleaseEntity>()
                    .eq(AppReleaseEntity::getPlatform, p));
        }
        log.info("[apprelease] 已发布 {} {} (v{}, {} bytes, sha256={})", p, vName, versionCode, size,
                entity.getSha256());
        return entity;
    }

    /** App 侧下载：返回文件路径 + 展示文件名（{hypermux-platform-versionName.apk}）。记录或文件缺失 → 404。 */
    public ReleasePackage resolvePackage(String platform) {
        AppReleaseEntity entity = latest(platform);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该平台暂未发布更新包");
        }
        Path file = Path.of(props.getDir()).normalize().resolve(entity.getFileName());
        if (!file.getFileName().toString().endsWith(".apk") || !Files.isRegularFile(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "更新包文件不存在，请重新发布");
        }
        String downloadName = "hypermux-" + entity.getPlatform() + "-" + entity.getVersionName() + ".apk";
        return new ReleasePackage(file, sizeQuietly(file, entity.getSizeBytes()), downloadName);
    }

    public record ReleasePackage(Path path, long sizeBytes, String downloadName) {
    }

    private static String normalizePlatform(String platform) {
        String p = platform == null ? "" : platform.trim().toLowerCase();
        if (!PLATFORMS.contains(p)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的平台: " + (platform == null ? "" : platform));
        }
        return p;
    }

    private static String sha256Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static long sizeQuietly(Path file, long fallback) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return fallback;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
