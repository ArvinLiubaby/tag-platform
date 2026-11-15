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
 * 开放 API · 操作员打标记录：
 *   GET /api/v1/open/tag-records?operator_id=…              —— 推荐入口（query 参数，PATH 为固定字符串）
 *   GET /api/v1/open/operators/{operator_id}/tag-records    —— v1.21.0 已发布的路径式，保留兼容
 * 两者响应结构完全一致；旧前缀 /open/v1 在兼容期内继续可用。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenOperatorRecordsController {

    private final ProfileQueryService profileQueryService;

    public OpenOperatorRecordsController(ProfileQueryService profileQueryService) {
        this.profileQueryService = profileQueryService;
    }

    /**
     * 推荐入口：operator_id 走 query 参数。签名待签串里的 PATH 是固定字符串，
     * 不涉及动态段的 URL 编码，减少一类「编码前后不一致」的签名错误。
     */
    @GetMapping("/tag-records")
    public ApiResponse<OperatorRecordsView> tagRecords(
            @RequestParam(value = "operator_id", required = false) String operatorId,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "tag_code", required = false) String tagCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return doQuery(operatorId, objectType, objectId, tagCode, from, to, page, size);
    }

    /** 旧路径式（v1.21.0 已发布，保留兼容）：operator_id 在路径中，签名 PATH 需对其做 URL 编码 */
    @GetMapping("/operators/{operatorId}/tag-records")
    public ApiResponse<OperatorRecordsView> tagRecordsByPath(
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
        return doQuery(operatorId, objectType, objectId, tagCode, from, to, page, size);
    }

    private ApiResponse<OperatorRecordsView> doQuery(String operatorId, String objectType, String objectId,
                                                     String tagCode, LocalDateTime from, LocalDateTime to,
                                                     int page, int size) {
        return ApiResponse.ok(profileQueryService.operatorRecords(
                RequestContext.getTenantId(), operatorId, objectType, objectId, tagCode, from, to, page, size));
    }
}
