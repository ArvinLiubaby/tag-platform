package com.tagplatform.query.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.OperatorRecordsView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 开放 API · 操作员打标记录（GET /api/v1/open/operators/{operator_id}/tag-records）。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenOperatorRecordsController {

    private final ProfileQueryService profileQueryService;

    public OpenOperatorRecordsController(ProfileQueryService profileQueryService) {
        this.profileQueryService = profileQueryService;
    }

    @GetMapping("/operators/{operatorId}/tag-records")
    public ApiResponse<OperatorRecordsView> tagRecords(
            @PathVariable("operatorId") String operatorId,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "tag_code", required = false) String tagCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(profileQueryService.operatorRecords(
                RequestContext.getTenantId(), operatorId, objectType, objectId, tagCode, from, to, page, size));
    }
}
