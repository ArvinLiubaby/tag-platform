package com.tagplatform.write.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.write.service.TagValueWriteService;
import com.tagplatform.write.web.dto.DeleteRequest;
import com.tagplatform.write.web.dto.WriteRequest;
import com.tagplatform.write.web.dto.WriteResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API · 标签值写入与作废（POST /api/v1/open/tag-values/write、/delete）。
 * tenant_id 从凭证上下文注入（请求体不接受 tenant_id，防越权）。
 * 旧前缀 /open/v1 在兼容期内继续可用。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenWriteController {

    private final TagValueWriteService tagValueWriteService;

    public OpenWriteController(TagValueWriteService tagValueWriteService) {
        this.tagValueWriteService = tagValueWriteService;
    }

    @PostMapping("/tag-values/write")
    public ApiResponse<WriteResult> write(@RequestBody WriteRequest request) {
        WriteResult result = tagValueWriteService.write(request,
                RequestContext.getTenantId(), RequestContext.getRequestId());
        return ApiResponse.ok(result);
    }

    @PostMapping("/tag-values/delete")
    public ApiResponse<WriteResult> delete(@RequestBody DeleteRequest request) {
        WriteResult result = tagValueWriteService.delete(request,
                RequestContext.getTenantId(), RequestContext.getRequestId());
        return ApiResponse.ok(result);
    }
}
