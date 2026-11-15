package com.tagplatform.write.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.config.AppProperties;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.write.inbound.KafkaStatusService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台 API · Kafka 接入健康状态（标签字典页「Kafka 接入」看板）。
 *
 * <p>只读聚合：topic 分区 / 未消费积压 / 消费组状态 / 死信条数 + 本次启动以来的消费计数。
 * Kafka 未启用时返回 enabled=false，不报错。权限点 page.monitor（所有内置角色可读）。</p>
 */
@RestController
@RequirePermission("page.monitor")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleKafkaController {

    private final AppProperties props;
    private final ObjectProvider<KafkaStatusService> statusService;

    public ConsoleKafkaController(AppProperties props, ObjectProvider<KafkaStatusService> statusService) {
        this.props = props;
        this.statusService = statusService;
    }

    @GetMapping("/kafka-status")
    public ApiResponse<Object> status() {
        ConsolePermission.requireAny();
        KafkaStatusService service = statusService.getIfAvailable();
        if (!props.kafka().enabled() || service == null) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("enabled", false);
            disabled.put("topic", props.kafka().topic());
            disabled.put("group", props.kafka().groupId());
            return ApiResponse.ok(disabled);
        }
        return ApiResponse.ok(service.status());
    }
}
