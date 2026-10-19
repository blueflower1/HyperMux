package com.hypermux.server.admin.controller;

import com.hypermux.server.admin.service.MetadataService;
import com.hypermux.server.admin.vo.AdminVos;
import com.hypermux.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 元数据刮削（后台一键填充，契约 §4.8）。 */
@RestController
@RequestMapping("/api/admin/metadata")
public class AdminMetadataController {

    private final MetadataService metadataService;

    public AdminMetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/search")
    public R<List<AdminVos.MetadataItemVo>> search(@RequestParam String keyword,
                                                   @RequestParam(required = false) Integer year,
                                                   @RequestParam(required = false) String provider) {
        return R.ok(metadataService.search(keyword, year, provider));
    }

    @GetMapping("/detail")
    public R<AdminVos.MetadataDetailVo> detail(@RequestParam String providerId,
                                               @RequestParam(required = false) String mediaType,
                                               @RequestParam(required = false) String provider) {
        return R.ok(metadataService.detail(providerId, mediaType, provider));
    }

    /** 某条目的全部候选图片（海报/横图），供后台「选择封面」挑选。 */
    @GetMapping("/images")
    public R<AdminVos.MetadataImagesVo> images(@RequestParam String providerId,
                                               @RequestParam(required = false) String mediaType,
                                               @RequestParam(required = false) String provider) {
        return R.ok(metadataService.images(providerId, mediaType, provider));
    }
}
