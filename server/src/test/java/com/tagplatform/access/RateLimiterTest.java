package com.tagplatform.access;

import com.tagplatform.access.kv.InMemoryKeyValueStore;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private AppProperties props(int ipQps, int operatorQps, int objectTagQps) {
        return new AppProperties(
                new AppProperties.Crypto("test-key"),
                "memory", "h2",
                new AppProperties.StarRocks("jdbc:h2:mem:test", "sa", "", false),
                new AppProperties.Limits(ipQps, operatorQps, objectTagQps, 1000, 300, 60, 24),
                new AppProperties.Console(8));
    }

    private RateLimiter limiter(int ipQps, int operatorQps, int objectTagQps) {
        return new RateLimiter(new InMemoryKeyValueStore(), props(ipQps, operatorQps, objectTagQps));
    }

    /**
     * 频控按"当前秒"切片计数（RateLimiter.count() 用 System.currentTimeMillis()/1000 作 key），
     * 若断言序列正好跨过秒边界，计数会归零导致偶发失败。这里把测试对齐到秒起始处执行。
     */
    private void alignToSecondStart() {
        long now = System.currentTimeMillis();
        long nextSecond = (now / 1000 + 1) * 1000 + 2;
        try {
            Thread.sleep(Math.max(0, nextSecond - now));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ipLimitTriggersAtThresholdPlusOne() {
        alignToSecondStart();
        RateLimiter limiter = limiter(2, 20, 1);
        limiter.checkIp("1.2.3.4");
        limiter.checkIp("1.2.3.4");
        assertThatThrownBy(() -> limiter.checkIp("1.2.3.4"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.IP_FREQUENCY));
    }

    @Test
    void ipLimitIsolatedPerIp() {
        RateLimiter limiter = limiter(1, 20, 1);
        limiter.checkIp("1.1.1.1");
        assertThatCode(() -> limiter.checkIp("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void operatorLimitTriggers() {
        alignToSecondStart();
        RateLimiter limiter = limiter(0, 1, 1);
        limiter.checkOperator("risk_control", "OP1");
        assertThatThrownBy(() -> limiter.checkOperator("risk_control", "OP1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.OPERATOR_FREQUENCY));
    }

    @Test
    void objectTagLimitTriggers() {
        alignToSecondStart();
        RateLimiter limiter = limiter(0, 0, 1);
        limiter.checkObjectTag("risk_control", "driver", "DRV00001", "risk_level");
        assertThatThrownBy(() -> limiter.checkObjectTag("risk_control", "driver", "DRV00001", "risk_level"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.OBJECT_FREQUENCY));
    }

    @Test
    void zeroQpsDisablesLimit() {
        RateLimiter limiter = limiter(0, 0, 0);
        for (int i = 0; i < 10; i++) {
            limiter.checkIp("1.2.3.4");
            limiter.checkOperator("risk_control", "OP1");
            limiter.checkObjectTag("risk_control", "driver", "DRV00001", "risk_level");
        }
    }

    @Test
    void nullOperatorIgnored() {
        RateLimiter limiter = limiter(0, 1, 1);
        assertThatCode(() -> limiter.checkOperator("risk_control", null)).doesNotThrowAnyException();
    }
}
