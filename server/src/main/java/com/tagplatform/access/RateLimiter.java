package com.tagplatform.access;

import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频控（秒级滑动近似：按秒切片计数 + 2 秒 TTL）：
 *   - IP 级：limits.ip-qps（默认 100/s），验签前拦截，防未鉴权爆破；
 *   - 操作员级：limits.operator-qps（默认 20/s），写入服务逐条拦截；
 *   - 同对象同标签：limits.object-tag-qps（默认 1/s），写入服务逐条拦截。
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(2);

    private final KeyValueStore kv;
    private final AppProperties props;

    public RateLimiter(KeyValueStore kv, AppProperties props) {
        this.kv = kv;
        this.props = props;
    }

    public void checkIp(String sourceIp) {
        int qps = props.limits().ipQps();
        if (qps <= 0) {
            return;
        }
        if (count("rl:ip:" + sourceIp) > qps) {
            throw new BizException(ErrorCode.IP_FREQUENCY);
        }
    }

    public void checkOperator(String tenantId, String operatorId) {
        int qps = props.limits().operatorQps();
        if (qps <= 0 || operatorId == null) {
            return;
        }
        if (count("rl:op:" + tenantId + ":" + operatorId) > qps) {
            throw new BizException(ErrorCode.OPERATOR_FREQUENCY);
        }
    }

    public void checkObjectTag(String tenantId, String objectType, String objectId, String tagCode) {
        int qps = props.limits().objectTagQps();
        if (qps <= 0) {
            return;
        }
        if (count("rl:ot:" + tenantId + ":" + objectType + ":" + objectId + ":" + tagCode) > qps) {
            throw new BizException(ErrorCode.OBJECT_FREQUENCY);
        }
    }

    private long count(String dimensionKey) {
        long second = System.currentTimeMillis() / 1000;
        return kv.increment(dimensionKey + ":" + second, WINDOW);
    }
}
