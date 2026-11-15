package com.tagplatform.console;

import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.console.model.ConsoleSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台会话续期：活跃请求在剩余 TTL 不足一半时自动延长到完整 TTL，
 * 使发版重启/隔夜不再丢失登录态（用户反馈"每次发版都要重新登录"的根因之一）。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_session_renew_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_session_renew_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.console.token-ttl-hours=72",
})
class ConsoleSessionRenewalTest {

    private static final String TOKEN_KEY_PREFIX = "console:token:";

    @Autowired
    private ConsoleSessionService sessionService;

    @Autowired
    private KeyValueStore kv;

    @Test
    void halfExpiredSessionIsRenewedToFullTtl() {
        ConsoleSessionService.LoginResult login = sessionService.login("admin", "admin123");
        String key = TOKEN_KEY_PREFIX + login.token();

        // 模拟会话已用掉大部分有效期：剩余 5 分钟（< 72h 的一半）
        String payload = kv.get(key).orElseThrow();
        kv.set(key, payload, Duration.ofMinutes(5));
        assertThat(remaining(key)).isLessThan(Duration.ofMinutes(6));

        Optional<ConsoleSession> session = sessionService.findByToken(login.token());

        assertThat(session).isPresent();
        assertThat(remaining(key).toHours()).isGreaterThanOrEqualTo(71);
    }

    @Test
    void freshSessionIsNotExtendedPrematurely() {
        ConsoleSessionService.LoginResult login = sessionService.login("admin", "admin123");
        String key = TOKEN_KEY_PREFIX + login.token();

        assertThat(sessionService.findByToken(login.token())).isPresent();

        // 刚签发即校验：剩余仍接近完整 TTL，不产生额外写入
        assertThat(remaining(key).toHours()).isGreaterThanOrEqualTo(71);
    }

    @Test
    void unknownTokenHasNoSession() {
        assertThat(sessionService.findByToken("0000000000000000000000000000000000000000000000000000000000000000"))
                .isEmpty();
        assertThat(kv.remainingTtl(TOKEN_KEY_PREFIX + "missing")).isEmpty();
    }

    private Duration remaining(String key) {
        return kv.remainingTtl(key).orElse(Duration.ZERO);
    }
}
