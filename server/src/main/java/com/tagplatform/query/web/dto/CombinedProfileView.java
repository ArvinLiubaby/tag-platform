package com.tagplatform.query.web.dto;

import java.util.List;

/**
 * 司机+车辆组合标签内容查询视图（GET /api/v1/open/objects/profile/combined）。
 * driver / vehicle 段未查询时为 null；include_empty=true 时无值标签以 status=none 占位返回。
 */
public record CombinedProfileView(
        String tenantId,
        ProfileSection driver,
        ProfileSection vehicle,
        int total) {

    public record ProfileSection(
            String objectType,
            String objectId,
            List<ProfileView.TagItem> tags) {
    }
}
