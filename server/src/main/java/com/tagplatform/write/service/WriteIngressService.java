package com.tagplatform.write.service;

import com.tagplatform.common.TaskWriteLinkHook;
import com.tagplatform.write.web.dto.WriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签写入统一入站门面：开放 API（HTTP）与 Kafka 消费共用同一套编排——
 * 任务联动前校验 → 写入 → 任务联动后推进，避免两条通道的行为漂移。
 *
 * <p>租户与请求号由调用方解析：HTTP 取自凭证上下文，Kafka 取自消息 / 配置。</p>
 */
@Service
public class WriteIngressService {

    private static final Logger log = LoggerFactory.getLogger(WriteIngressService.class);

    private final TagValueWriteService tagValueWriteService;
    private final List<TaskWriteLinkHook> taskWriteLinkHooks;

    public WriteIngressService(TagValueWriteService tagValueWriteService,
                               List<TaskWriteLinkHook> taskWriteLinkHooks) {
        this.tagValueWriteService = tagValueWriteService;
        this.taskWriteLinkHooks = taskWriteLinkHooks;
    }

    /**
     * @param rateLimit 是否执行频控：HTTP=true；Kafka 内部通道=false
     * @param channel   通道标识，仅用于日志（http / kafka）
     */
    public WriteOutcome submit(WriteRequest request, String tenantId, String requestId,
                               boolean rateLimit, String channel) {
        List<TaskWriteLinkHook.Item> links = null;
        if (request.taskId() != null) {
            links = request.items() == null ? List.of() : request.items().stream()
                    .map(item -> new TaskWriteLinkHook.Item(item.objectType(), item.objectId(), item.tagCode(),
                            item.tagValue(), item.remark(), item.confidence(), item.evidenceUrl()))
                    .toList();
            for (TaskWriteLinkHook hook : taskWriteLinkHooks) {
                hook.validate(tenantId, request.taskId(), links);
            }
        }
        WriteOutcome outcome = tagValueWriteService.writeWithOutcome(request, tenantId, requestId, rateLimit);
        if (request.taskId() != null) {
            for (TaskWriteLinkHook hook : taskWriteLinkHooks) {
                try {
                    hook.afterWrite(tenantId, request.taskId(), links);
                } catch (RuntimeException e) {
                    log.warn("任务联动推进失败 channel={} taskId={} requestId={}: {}",
                            channel, request.taskId(), requestId, e.getMessage());
                }
            }
        }
        return outcome;
    }
}
