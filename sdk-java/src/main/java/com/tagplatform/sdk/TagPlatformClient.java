package com.tagplatform.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签画像采集平台 Java SDK（精简版）：
 *   - 自动 HMAC-SHA256 签名（X-App-Key / X-Timestamp / X-Nonce / X-Signature）
 *   - 网络异常 / 5xx / 频控 429 自动重试（指数退避；每次重试重新生成时间戳与 nonce）
 *   - 统一响应解析；非 0 code 抛 {@link TagPlatformException}
 *
 * 用法：
 * <pre>
 *   TagPlatformClient client = new TagPlatformClient("http://localhost:8080", "AK_RC_8f2a91", "rc-secret-8f2a91-demo");
 *   client.write(WriteRequest.of("biz-1001", List.of(WriteItem.of("driver", "DRV00001", "risk_level", "high")
 *       .operator(OperatorDto.human("OP1001", "张三", "某交警支队"))
 *       .bizTime(LocalDateTime.now()))));
 * </pre>
 */
public class TagPlatformClient {

    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String baseUrl;
    private final String appKey;
    private final String secret;
    private final int maxRetries;
    private final long retryBaseMillis;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public TagPlatformClient(String baseUrl, String appKey, String secret) {
        this(baseUrl, appKey, secret, 2, 200, Duration.ofSeconds(10));
    }

    public TagPlatformClient(String baseUrl, String appKey, String secret,
                             int maxRetries, long retryBaseMillis, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.appKey = appKey;
        this.secret = secret;
        this.maxRetries = Math.max(maxRetries, 0);
        this.retryBaseMillis = Math.max(retryBaseMillis, 0);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.mapper = buildMapper();
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JavaTimeModule timeModule = new JavaTimeModule();
        // 与服务端一致的 "yyyy-MM-dd HH:mm:ss" 格式
        timeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(TIME_FORMATTER));
        objectMapper.registerModule(timeModule);
        return objectMapper;
    }

    // ------------------------------------------------------------ 开放 API

    /** GET /api/v1/open/tags 全量可见在线标签 */
    public List<TagMetaDto> getTags() {
        return getTags(null);
    }

    /** GET /api/v1/open/tags?category_code= 按类目查询标签字典 */
    public List<TagMetaDto> getTags(String categoryCode) {
        return getTags(categoryCode, null, null);
    }

    /**
     * GET /api/v1/open/tags 按类目 / 适用对象 / 打标来源类型查询标签字典。
     * 典型用法：客服工作台查询「需要我打的标签」→ getTags(null, "driver", "manual")。
     */
    public List<TagMetaDto> getTags(String categoryCode, String objectType, String sourceMode) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        putIfPresent(query, "category_code", categoryCode);
        putIfPresent(query, "object_type", objectType);
        putIfPresent(query, "source_mode", sourceMode);
        // 服务端 data = {tags, total}；此处对外只暴露 tags 列表
        TagListView view = call("GET", "/api/v1/open/tags", query, null, TagListView.class);
        return view == null || view.tags() == null ? List.of() : view.tags();
    }

    /** POST /api/v1/open/tag-values/write 批量写入（整批受理，≤1000 条） */
    public WriteResult write(WriteRequest request) {
        return call("POST", "/api/v1/open/tag-values/write", Map.of(), request, WriteResult.class);
    }

    /** POST /api/v1/open/tag-values/delete 批量作废 */
    public WriteResult delete(DeleteRequest request) {
        return call("POST", "/api/v1/open/tag-values/delete", Map.of(), request, WriteResult.class);
    }

    /** GET /api/v1/open/objects/{objectId}/profile 对象画像 */
    public ProfileView getProfile(String objectType, String objectId, boolean includePending) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        putIfPresent(query, "object_type", objectType);
        query.put("include_pending", List.of(String.valueOf(includePending)));
        return call("GET", "/api/v1/open/objects/" + pathSegment(objectId) + "/profile", query, null, ProfileView.class);
    }

    /** GET /api/v1/open/objects/profile/combined 司机+车辆组合标签内容（默认含无值占位、不含待审值） */
    public CombinedProfileView getCombinedProfile(String driverId, String vehiclePlate) {
        return getCombinedProfile(driverId, vehiclePlate, false, true);
    }

    /** 组合标签内容查询（driverId / vehiclePlate 至少传一个）；includeEmpty=true 时无值标签以 status=none 占位 */
    public CombinedProfileView getCombinedProfile(String driverId, String vehiclePlate,
                                                  boolean includePending, boolean includeEmpty) {
        return getCombinedProfile(driverId, vehiclePlate, includePending, includeEmpty, null);
    }

    /** 组合标签内容查询（tagCodes 非空时仅返回这些标签，可精确取「清单-1015」的 32 个特征值） */
    public CombinedProfileView getCombinedProfile(String driverId, String vehiclePlate,
                                                  boolean includePending, boolean includeEmpty,
                                                  Collection<String> tagCodes) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        putIfPresent(query, "driver_id", driverId);
        putIfPresent(query, "vehicle_plate", vehiclePlate);
        query.put("include_pending", List.of(String.valueOf(includePending)));
        query.put("include_empty", List.of(String.valueOf(includeEmpty)));
        if (tagCodes != null && !tagCodes.isEmpty()) {
            query.put("tag_codes", List.of(String.join(",", tagCodes)));
        }
        return call("GET", "/api/v1/open/objects/profile/combined", query, null, CombinedProfileView.class);
    }

    /** GET /api/v1/open/operators/{operatorId}/tag-records 操作员打标记录 */
    public OperatorRecordsView getOperatorRecords(String operatorId, Map<String, String> filters,
                                                  int page, int size) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (filters != null) {
            filters.forEach((k, v) -> putIfPresent(query, k, v));
        }
        query.put("page", List.of(String.valueOf(page)));
        query.put("size", List.of(String.valueOf(size)));
        return call("GET", "/api/v1/open/operators/" + pathSegment(operatorId) + "/tag-records", query, null,
                OperatorRecordsView.class);
    }

    // ------------------------------------------------------------ 核心调用

    private <T> T call(String method, String path, Map<String, List<String>> query, Object body,
                       Class<T> type) {
        return call(method, path, query, body, mapper.getTypeFactory().constructType(type));
    }

    private <T> T call(String method, String path, Map<String, List<String>> query, Object body,
                       com.fasterxml.jackson.databind.JavaType type) {
        TagPlatformException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryBaseMillis * (1L << (attempt - 1)));
            }
            try {
                return doCall(method, path, query, body, type);
            } catch (TagPlatformException e) {
                lastError = e;
                if (!e.retryable() || attempt == maxRetries) {
                    throw e;
                }
            }
        }
        throw lastError == null ? new TagPlatformException(-1, "请求失败", null) : lastError;
    }

    private <T> T doCall(String method, String path, Map<String, List<String>> query, Object body,
                         com.fasterxml.jackson.databind.JavaType type) {
        String rawBody = "";
        try {
            if (body != null) {
                rawBody = mapper.writeValueAsString(body);
            }
        } catch (Exception e) {
            throw new TagPlatformException(-1, "请求序列化失败: " + e.getMessage(), null, e);
        }
        String canonicalQuery = Signer.canonicalQuery(query);
        String urlQuery = Signer.encodeQuery(query);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = Signer.newNonce();
        String signature = Signer.sign(method, path, canonicalQuery, rawBody, timestamp, nonce, secret);

        String url = baseUrl + path + (urlQuery.isEmpty() ? "" : "?" + urlQuery);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header(HEADER_APP_KEY, appKey)
                .header(HEADER_TIMESTAMP, timestamp)
                .header(HEADER_NONCE, nonce)
                .header(HEADER_SIGNATURE, signature)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8));
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TagPlatformException(-1, "网络异常: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TagPlatformException(-1, "请求被中断", null, e);
        }

        String responseBody = response.body() == null ? "" : response.body();
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            throw new TagPlatformException(-1,
                    "响应解析失败（HTTP " + response.statusCode() + "）: " + brief(responseBody), null, e);
        }
        if (root == null || !root.hasNonNull("code")) {
            throw new TagPlatformException(-1,
                    "响应缺少统一结构（HTTP " + response.statusCode() + "）: " + brief(responseBody), null);
        }
        int code = root.path("code").asInt();
        if (code != 0) {
            throw new TagPlatformException(code, root.path("message").asText("未知错误"),
                    root.path("request_id").asText(null));
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(data, type);
        } catch (Exception e) {
            throw new TagPlatformException(-1, "响应反序列化失败: " + e.getMessage(), null, e);
        }
    }

    private static void putIfPresent(Map<String, List<String>> query, String key, String value) {
        if (value != null && !value.isBlank()) {
            query.put(key, List.of(value));
        }
    }

    /** path 动态段编码（服务端 getRequestURI() 为编码后原样值，签名需与之一致） */
    private static String pathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ DTO

    /** 标签元数据（GET /api/v1/open/tags 的 data.tags 元素） */
    public record TagMetaDto(
            String tagCode,
            String tagName,
            String categoryCode,
            String valueType,
            String valueRange,
            boolean valueMultiple,
            List<String> options,
            String tagType,
            String objectType,
            String sourceMode,
            boolean needValueAudit,
            boolean writable,
            int valueTtlDays,
            String sensitivity,
            String status,
            int version) {
    }

    /** 标签字典响应（data = {tags, total}） */
    public record TagListView(List<TagMetaDto> tags, long total) {
    }

    /** 操作员四元组 */
    public record OperatorDto(
            String operatorId,
            String operatorName,
            String operatorType,
            String operatorOrg) {

        public static OperatorDto human(String operatorId, String operatorName, String operatorOrg) {
            return new OperatorDto(operatorId, operatorName, "human", operatorOrg);
        }

        public static OperatorDto system(String operatorId, String operatorName, String operatorOrg) {
            return new OperatorDto(operatorId, operatorName, "system", operatorOrg);
        }

        public static OperatorDto algo(String operatorId, String operatorName, String operatorOrg) {
            return new OperatorDto(operatorId, operatorName, "algo", operatorOrg);
        }
    }

    /** 写入请求（biz_request_id 为幂等键，重复提交回放首次响应） */
    public record WriteRequest(String bizRequestId, List<WriteItem> items) {

        public static WriteRequest of(String bizRequestId, List<WriteItem> items) {
            return new WriteRequest(bizRequestId, items);
        }
    }

    /** 单条写入项 */
    public record WriteItem(
            String objectType,
            String objectId,
            String tagCode,
            String tagValue,
            LocalDateTime bizTime,
            LocalDateTime operateTime,
            OperatorDto operator,
            String source,
            String confidence,
            String remark,
            String evidenceUrl,
            String extJson) {

        public static WriteItem of(String objectType, String objectId, String tagCode, String tagValue) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, null, null, null,
                    null, null, null, null, null);
        }

        public WriteItem bizTime(LocalDateTime value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, value, operateTime, operator,
                    source, confidence, remark, evidenceUrl, extJson);
        }

        public WriteItem operateTime(LocalDateTime value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, value, operator,
                    source, confidence, remark, evidenceUrl, extJson);
        }

        public WriteItem operator(OperatorDto value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, value,
                    source, confidence, remark, evidenceUrl, extJson);
        }

        public WriteItem source(String value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, operator,
                    value, confidence, remark, evidenceUrl, extJson);
        }

        /** 置信度：可选，仅支持「高 / 中 / 低」 */
        public WriteItem confidence(String value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, operator,
                    source, value, remark, evidenceUrl, extJson);
        }

        /** 敏感标签（need_value_audit）必填打标理由 */
        public WriteItem remark(String value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, operator,
                    source, confidence, value, evidenceUrl, extJson);
        }

        public WriteItem evidenceUrl(String value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, operator,
                    source, confidence, remark, value, extJson);
        }

        public WriteItem extJson(String value) {
            return new WriteItem(objectType, objectId, tagCode, tagValue, bizTime, operateTime, operator,
                    source, confidence, remark, evidenceUrl, value);
        }
    }

    /** 作废请求 */
    public record DeleteRequest(String bizRequestId, List<DeleteItem> items) {

        public static DeleteRequest of(String bizRequestId, List<DeleteItem> items) {
            return new DeleteRequest(bizRequestId, items);
        }
    }

    /** 单条作废项 */
    public record DeleteItem(
            String objectType,
            String objectId,
            String tagCode,
            OperatorDto operator,
            String remark) {

        public static DeleteItem of(String objectType, String objectId, String tagCode) {
            return new DeleteItem(objectType, objectId, tagCode, null, null);
        }

        public DeleteItem operator(OperatorDto value) {
            return new DeleteItem(objectType, objectId, tagCode, value, remark);
        }

        public DeleteItem remark(String value) {
            return new DeleteItem(objectType, objectId, tagCode, operator, value);
        }
    }

    /** 写入/作废响应 */
    public record WriteResult(
            String bizRequestId,
            int total,
            int accepted,
            int pendingAudit,
            List<ItemResult> results) {

        public ItemResult item(int index) {
            return results == null ? null : results.get(index);
        }
    }

    /** 单条结果 */
    public record ItemResult(
            int index,
            String objectType,
            String objectId,
            String tagCode,
            String status,
            String message) {
    }

    /**
     * 标签值（画像 tags 元素与操作员记录 records 元素的并集投影：
     * 画像标签缺 audit 系字段与 request_id、op_time，操作员记录缺 tag_name；缺失字段为 null）。
     */
    public record TagValueDto(
            String tenantId,
            String objectType,
            String objectId,
            String tagCode,
            String tagName,
            String tagValue,
            String valueType,
            Boolean valueMultiple,
            String status,
            String sourceMode,
            Boolean needValueAudit,
            LocalDateTime bizTime,
            LocalDateTime operateTime,
            LocalDateTime expireTime,
            String operatorId,
            String operatorName,
            String operatorType,
            String operatorOrg,
            String source,
            String confidence,
            String remark,
            String evidenceUrl,
            String auditBy,
            LocalDateTime auditTime,
            String auditRemark,
            String requestId,
            LocalDateTime opTime,
            Long ver,
            String extJson) {
    }

    /** 对象画像 */
    public record ProfileView(
            String tenantId,
            String objectType,
            String objectId,
            List<TagValueDto> tags) {
    }

    /** 司机+车辆组合标签内容（GET /api/v1/open/objects/profile/combined） */
    public record CombinedProfileView(
            String tenantId,
            ProfileSection driver,
            ProfileSection vehicle,
            int total) {

        public record ProfileSection(String objectType, String objectId, List<TagValueDto> tags) {
        }
    }

    /** 操作员打标记录页 */
    public record OperatorRecordsView(
            String operatorId,
            int page,
            int size,
            long total,
            List<TagValueDto> records) {
    }
}
