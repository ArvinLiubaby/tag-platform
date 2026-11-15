package com.tagplatform.query.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.ProfileBatchView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放 API · 标签内容查询（画像查询唯一入口）：
 *   GET /api/v1/open/objects/profiles —— 单对象 / 多对象 / 空占位 / 精确取码
 * 旧前缀 /open/v1 在兼容期内继续可用。
 *
 * 已于 v1.22.0 下线（能力全部并入本接口）：
 *   GET /objects/{object_id}/profile    单对象画像
 *   GET /objects/profile/combined       司机+车辆组合标签内容
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenProfileController {

    private final ProfileQueryService profileQueryService;

    public OpenProfileController(ProfileQueryService profileQueryService) {
        this.profileQueryService = profileQueryService;
    }

    /**
     * 批量对象画像（画像查询统一入口）：object_type / object_id 逗号分隔多值，按位置配对、不做笛卡尔积。
     * 只查一个对象时传 1 组即可；include_empty=true 返回 status=none 空占位，tag_codes 精确取码。
     * include_empty 默认 false（只返回有值标签），与原组合画像接口默认 true 不同，迁移时需显式传值。
     */
    @GetMapping("/objects/profiles")
    public ApiResponse<ProfileBatchView> profiles(
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "include_pending", defaultValue = "false") boolean includePending,
            @RequestParam(value = "include_empty", defaultValue = "false") boolean includeEmpty,
            @RequestParam(value = "tag_codes", required = false) String tagCodes) {
        return ApiResponse.ok(profileQueryService.profiles(RequestContext.getTenantId(),
                objectType, objectId, includePending, includeEmpty, parseTagCodes(tagCodes)));
    }

    /** 逗号分隔标签码 → Set；空串/空白 → 空集合（不过滤） */
    private static Set<String> parseTagCodes(String tagCodes) {
        if (tagCodes == null || tagCodes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(tagCodes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toSet());
    }
}
