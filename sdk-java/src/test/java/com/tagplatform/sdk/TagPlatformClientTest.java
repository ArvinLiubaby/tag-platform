package com.tagplatform.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK 回环测试：本地 HttpServer 扮演平台服务端（用与 SignatureVerifier 相同的算法重算签名），
 * 覆盖签名透传一致性、响应解析、自动重试（每次重试换 nonce）与不可重试错误直接抛出。
 */
class TagPlatformClientTest {

    private static final String APP_KEY = "AK_TEST_0001";
    private static final String SECRET = "test-secret-0001";

    private HttpServer server;
    private TagPlatformClient client;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> seenNonces = new ArrayList<>();
    private final List<String> seenSignatures = new ArrayList<>();
    private volatile int failFirstTimes = 0;
    private volatile String failBody = "{\"code\":5001,\"message\":\"系统内部错误\",\"request_id\":\"r-err\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new TagPlatformClient("http://127.0.0.1:" + server.getAddress().getPort(),
                APP_KEY, SECRET, 2, 10, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 模拟服务端：校验签名 → 按场景返回 */
    private void handle(HttpExchange exchange) throws IOException {
        int seq = requestCount.incrementAndGet();
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        // 贴近真实 Tomcat：getRequestURI() 返回编码后的原始路径（未解码）
        String path = exchange.getRequestURI().getRawPath();

        String appKey = exchange.getRequestHeaders().getFirst("X-App-Key");
        String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
        String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        seenNonces.add(nonce);
        seenSignatures.add(signature);

        String canonicalQuery = canonicalQueryFromRaw(exchange.getRequestURI().getRawQuery());
        String expected = Signer.sign(method, path, canonicalQuery, body, timestamp, nonce, SECRET);
        if (!APP_KEY.equals(appKey) || !expected.equals(signature)) {
            respond(exchange, 401, "{\"code\":1003,\"message\":\"签名校验失败\",\"request_id\":\"r-sig\"}");
            return;
        }

        if (seq <= failFirstTimes) {
            respond(exchange, 500, failBody);
            return;
        }
        route(exchange, path);
    }

    private void route(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/v1/open/tags")) {
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"request_id\":\"r1\",\"data\":{\"tags\":[" +
                    "{\"tag_code\":\"risk_level\",\"tag_name\":\"风险等级\",\"category_code\":\"risk_score\"," +
                    "\"value_type\":\"enum\",\"need_value_audit\":false,\"value_ttl_days\":0," +
                    "\"sensitivity\":\"L2\",\"status\":\"online\",\"version\":1}],\"total\":1}}");
        } else if (path.equals("/api/v1/open/tag-values/write")) {
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"request_id\":\"r2\",\"data\":{" +
                    "\"biz_request_id\":\"biz-1\",\"total\":2,\"accepted\":1,\"pending_audit\":1,\"results\":[" +
                    "{\"index\":0,\"object_type\":\"driver\",\"object_id\":\"DRV00001\",\"tag_code\":\"risk_level\"," +
                    "\"status\":\"accepted\",\"message\":null}," +
                    "{\"index\":1,\"object_type\":\"driver\",\"object_id\":\"DRV00001\",\"tag_code\":\"suspected_fraud\"," +
                    "\"status\":\"pending_audit\",\"message\":null}]}}");
        } else if (path.contains("/profile")) {
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"request_id\":\"r3\",\"data\":{" +
                    "\"tenant_id\":\"risk_control\",\"object_type\":\"driver\",\"object_id\":\"DRV00001\",\"tags\":[" +
                    "{\"tag_code\":\"risk_level\",\"tag_name\":\"风险等级\",\"tag_value\":\"high\",\"status\":\"effective\"," +
                    "\"biz_time\":\"2026-10-14 10:00:00\",\"operator_id\":\"OP1001\"}]}}");
        } else if (path.contains("/tag-records")) {
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"request_id\":\"r4\",\"data\":{" +
                    "\"operator_id\":\"OP1001\",\"page\":1,\"size\":20,\"total\":1,\"records\":[" +
                    "{\"object_type\":\"driver\",\"object_id\":\"DRV00001\",\"tag_code\":\"risk_level\"," +
                    "\"tag_value\":\"high\",\"status\":\"effective\",\"biz_time\":\"2026-10-14 10:00:00\"," +
                    "\"operator_id\":\"OP1001\",\"request_id\":\"r-w-1\",\"ver\":1}]}}");
        } else {
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"request_id\":\"r0\",\"data\":null}");
        }
    }

    /** 与服务端 canonicalQuery 相同的重建逻辑：从原始 query 解析（解码后）key=value 对 */
    private static String canonicalQueryFromRaw(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return Signer.canonicalQuery(params);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ------------------------------------------------------------ 用例

    @Test
    void getTagsSignsRequestAndParsesResponse() {
        List<TagPlatformClient.TagMetaDto> tags = client.getTags("risk_score");
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).tagCode()).isEqualTo("risk_level");
        assertThat(tags.get(0).tagName()).isEqualTo("风险等级");
        assertThat(tags.get(0).needValueAudit()).isFalse();
        assertThat(tags.get(0).version()).isEqualTo(1);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void writeSendsSnakeCaseBodyAndParsesResult() {
        TagPlatformClient.WriteResult result = client.write(TagPlatformClient.WriteRequest.of("biz-1", List.of(
                TagPlatformClient.WriteItem.of("driver", "DRV00001", "risk_level", "high"))));
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.pendingAudit()).isEqualTo(1);
        assertThat(result.item(1).status()).isEqualTo("pending_audit");
    }

    @Test
    void profileParsesLocalDateTime() {
        TagPlatformClient.ProfileView profile = client.getProfile("driver", "DRV00001", true);
        assertThat(profile.tags()).hasSize(1);
        assertThat(profile.tags().get(0).tagName()).isEqualTo("风险等级");
        assertThat(profile.tags().get(0).bizTime()).isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0, 0));
    }

    @Test
    void retriesOnRetryableErrorWithFreshNonce() {
        failFirstTimes = 1; // 第 1 次返回 5001，第 2 次成功
        List<TagPlatformClient.TagMetaDto> tags = client.getTags();
        assertThat(tags).hasSize(1);
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(seenNonces).hasSize(2);
        assertThat(seenNonces.get(0)).isNotEqualTo(seenNonces.get(1));
        assertThat(seenSignatures.get(0)).isNotEqualTo(seenSignatures.get(1));
    }

    @Test
    void givesUpAfterMaxRetries() {
        failFirstTimes = 10;
        assertThatThrownBy(() -> client.getTags())
                .isInstanceOf(TagPlatformException.class)
                .satisfies(e -> assertThat(((TagPlatformException) e).getCode()).isEqualTo(5001));
        assertThat(requestCount.get()).isEqualTo(3); // 1 次 + 2 次重试
    }

    @Test
    void nonRetryableErrorThrowsImmediately() {
        failFirstTimes = 1;
        failBody = "{\"code\":3001,\"message\":\"标签不存在\",\"request_id\":\"r-tag\"}";
        assertThatThrownBy(() -> client.getTags())
                .isInstanceOf(TagPlatformException.class)
                .satisfies(e -> {
                    TagPlatformException ex = (TagPlatformException) e;
                    assertThat(ex.getCode()).isEqualTo(3001);
                    assertThat(ex.getRequestId()).isEqualTo("r-tag");
                    assertThat(ex.retryable()).isFalse();
                });
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void operatorRecordsBuildsQueryWithPaging() {
        TagPlatformClient.OperatorRecordsView view = client.getOperatorRecords("OP1001",
                Map.of("object_type", "driver", "tag_code", "risk_level"), 1, 20);
        assertThat(view.operatorId()).isEqualTo("OP1001");
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.total()).isEqualTo(1);
        assertThat(view.records()).hasSize(1);
        assertThat(view.records().get(0).requestId()).isEqualTo("r-w-1");
        assertThat(view.records().get(0).ver()).isEqualTo(1L);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void canonicalQueryRoundTripWithSpecialCharacters() {
        // 带空格与斜杠的值：SDK 编码后可被服务端解码重算
        client.getProfile("driver", "DRV 00001", false);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(seenSignatures).allSatisfy(sig -> assertThat(sig).hasSize(64));
    }

    @Test
    void serverSignatureRejectsTamperedClient() {
        // 反证：错误 secret 的客户端必然 1003
        TagPlatformClient bad = new TagPlatformClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), APP_KEY, "wrong-secret");
        assertThatThrownBy(bad::getTags)
                .isInstanceOf(TagPlatformException.class)
                .satisfies(e -> assertThat(((TagPlatformException) e).getCode()).isEqualTo(1003));
    }

    @Test
    void allNoncesUniqueAcrossRequests() {
        for (int i = 0; i < 5; i++) {
            client.getTags();
        }
        assertThat(requestCount.get()).isEqualTo(5);
        assertThat(seenNonces).doesNotHaveDuplicates();
    }
}
