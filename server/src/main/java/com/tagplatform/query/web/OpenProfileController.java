package com.tagplatform.query.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.CombinedProfileView;
import com.tagplatform.query.web.dto.ProfileView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放 API · 标签内容查询：
 *   - 单对象画像（GET /api/v1/open/objects/{object_id}/profile）
 *   - 司机+车辆组合标签内容（GET /api/v1/open/objects/profile/combined）
 * 旧前缀 /open/v1 在兼容期内继续可用。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenProfileController {

    private final ProfileQueryService profileQueryService;

    public OpenProfileController(ProfileQueryService profileQueryService) {
        this.profileQueryService = profileQueryService;
    }

    @GetMapping("/objects/{objectId}/profile")
    public ApiResponse<ProfileView> profile(@PathVariable("objectId") String objectId,
                                            @RequestParam("object_type") String objectType,
                                            @RequestParam(value = "include_pending", defaultValue = "false")
                                            boolean includePending) {
        return ApiResponse.ok(profileQueryService.profile(
                RequestContext.getTenantId(), objectType, objectId, includePending));
    }

    /**
     * 司机+车辆组合标签内容：driver_id / vehicle_plate 至少传一个；include_empty=true 时无值标签占位返回；
     * tag_codes 非空时（逗号分隔）仅返回这些标签，可精确取「清单-1015」的 32 个特征值。
     */
    @GetMapping("/objects/profile/combined")
    public ApiResponse<CombinedProfileView> combinedProfile(
            @RequestParam(value = "driver_id", required = false) String driverId,
            @RequestParam(value = "vehicle_plate", required = false) String vehiclePlate,
            @RequestParam(value = "include_pending", defaultValue = "false") boolean includePending,
            @RequestParam(value = "include_empty", defaultValue = "true") boolean includeEmpty,
            @RequestParam(value = "tag_codes", required = false) String tagCodes) {
        return ApiResponse.ok(profileQueryService.combinedProfile(
                RequestContext.getTenantId(), driverId, vehiclePlate, includePending, includeEmpty,
                parseTagCodes(tagCodes)));
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
