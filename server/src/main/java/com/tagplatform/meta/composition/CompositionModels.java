package com.tagplatform.meta.composition;

import java.time.LocalDateTime;
import java.util.List;

/** 组合标签的控制台请求/响应模型（JSON 字段随全局命名策略为 snake_case）。 */
public final class CompositionModels {

    private CompositionModels() {
    }

    public record CompositionView(
            String tagCode,
            String tagName,
            String categoryCode,
            String objectType,
            String tenantId,
            String status,
            int ruleVersion,
            int metaVersion,
            String outputType,
            String sensitivity,
            String outputMode,
            CompositionRule rule,
            String description,
            LocalDateTime updatedAt,
            String updatedBy) {
    }

    public record SaveRequest(
            String tagCode,
            String tagName,
            String categoryCode,
            String objectType,
            String tenantId,
            String outputType,
            String description,
            CompositionRule rule) {
    }

    public record PreviewRequest(
            String tenantId,
            String objectType,
            String objectId,
            String outputType,
            CompositionRule rule) {
    }

    public record PreviewResponse(
            String objectType,
            String objectId,
            String value,
            boolean matched,
            String status,
            List<CompositionEvaluator.Trace> trace,
            List<String> missingTags,
            List<InputValue> inputs,
            List<String> warnings) {

        public record InputValue(String tagCode, String tagName, String tagValue, String valueType, boolean derived) {
        }
    }

    public record UsageView(String tagCode, boolean derived, String compositionStatus, long currentTotal,
                            int evaluated, List<CurrentValue> currentValues, List<Reference> referencedBy,
                            List<RecentValue> recentValues) {

        public record CurrentValue(String objectType, String objectId, String tagValue, String status,
                                   LocalDateTime bizTime, LocalDateTime operateTime, LocalDateTime expireTime,
                                   String operatorName, String source, String detail) {
        }

        public record Reference(String tagCode, String tagName, String status) {
        }

        public record RecentValue(String objectType, String objectId, String tagValue, String status,
                                  LocalDateTime operateTime, String operatorName, String source) {
        }
    }
}
