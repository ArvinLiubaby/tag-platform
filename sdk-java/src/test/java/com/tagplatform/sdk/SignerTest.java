package com.tagplatform.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignerTest {

    @Test
    void signMatchesKnownVector() {
        // 与平台服务端（SignatureVerifierTest）及 openssl 完全一致的已知向量
        String signature = Signer.sign("POST", "/api/v1/open/tag-values/write", "",
                "{\"a\":1}", "1700000000000", "nonce123", "test-secret");
        assertThat(signature).isEqualTo("3c759b39cbef7c2e7be01e25c5fce4fa0e0b0aebe1d26b71556f751f41c2d11b");
    }

    @Test
    void canonicalQuerySortsKeysAndValues() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("object_type", List.of("driver"));
        query.put("include_pending", List.of("true"));
        query.put("k", List.of("b", "a"));
        assertThat(Signer.canonicalQuery(query)).isEqualTo("include_pending=true&k=a&k=b&object_type=driver");
    }

    @Test
    void canonicalQueryEmptyForNullAndEmpty() {
        assertThat(Signer.canonicalQuery(null)).isEmpty();
        assertThat(Signer.canonicalQuery(Map.of())).isEmpty();
    }

    @Test
    void encodeQueryUrlEncodesAndKeepsOrder() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("tag_code", List.of("risk level/1"));
        query.put("a", List.of("x y"));
        assertThat(Signer.encodeQuery(query)).isEqualTo("a=x%20y&tag_code=risk%20level%2F1");
    }

    @Test
    void newNonceIsRandomHex() {
        String first = Signer.newNonce();
        String second = Signer.newNonce();
        assertThat(first).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(first).isNotEqualTo(second);
    }
}
