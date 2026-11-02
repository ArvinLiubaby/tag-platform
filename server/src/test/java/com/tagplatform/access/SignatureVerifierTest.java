package com.tagplatform.access;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

    @Test
    void signMatchesOpenSslKnownVector() {
        // 与 openssl 计算的已知向量完全一致（契约冻结，SDK/脚本必须同实现）
        String signature = SignatureVerifier.sign("POST", "/api/v1/open/tag-values/write", "",
                "{\"a\":1}", "1700000000000", "nonce123", "test-secret");
        assertThat(signature).isEqualTo("3c759b39cbef7c2e7be01e25c5fce4fa0e0b0aebe1d26b71556f751f41c2d11b");
    }

    @Test
    void signIsDeterministic() {
        String first = SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "1", "n", "s");
        String second = SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "1", "n", "s");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64); // hex(32 bytes)
    }

    @Test
    void signChangesWithAnyComponent() {
        String base = SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "1", "n", "s");
        assertThat(SignatureVerifier.sign("POST", "/api/v1/open/tags", "", "", "1", "n", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags/2", "", "", "1", "n", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags", "a=1", "", "1", "n", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "{}", "1", "n", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "2", "n", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "1", "m", "s")).isNotEqualTo(base);
        assertThat(SignatureVerifier.sign("GET", "/api/v1/open/tags", "", "", "1", "n", "t")).isNotEqualTo(base);
    }

    @Test
    void canonicalQueryEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/open/tags");
        assertThat(SignatureVerifier.canonicalQuery(request)).isEmpty();
    }

    @Test
    void canonicalQuerySortsKeysAndValues() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/open/objects/x/profile");
        request.addParameter("object_type", "driver");
        request.addParameter("include_pending", "true");
        assertThat(SignatureVerifier.canonicalQuery(request))
                .isEqualTo("include_pending=true&object_type=driver");
    }

    @Test
    void canonicalQuerySortsSameKeyValuesAlphabetically() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/open/tags");
        request.addParameter("k", "b");
        request.addParameter("k", "a");
        assertThat(SignatureVerifier.canonicalQuery(request)).isEqualTo("k=a&k=b");
    }
}
