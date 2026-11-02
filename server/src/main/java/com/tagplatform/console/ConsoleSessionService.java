package com.tagplatform.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.crypto.PasswordHasher;
import com.tagplatform.config.AppProperties;
import com.tagplatform.console.model.ConsoleSession;
import com.tagplatform.console.model.ConsoleUser;
import com.tagplatform.console.repo.ConsoleUserRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 控制台会话服务：登录签发 Token（KV 存储，TTL 8h）、校验、登出、密码渐进升级（plain: → pbkdf2）。
 */
@Service
public class ConsoleSessionService {

    private static final String TOKEN_KEY_PREFIX = "console:token:";
    private static final String VERSION_KEY_PREFIX = "console:user:ver:";
    private static final Duration VERSION_TTL = Duration.ofDays(3650);

    private final KeyValueStore kv;
    private final ObjectMapper objectMapper;
    private final ConsoleUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final AppProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConsoleSessionService(KeyValueStore kv, ObjectMapper objectMapper, ConsoleUserRepository userRepository,
                                 PasswordHasher passwordHasher, AppProperties props) {
        this.kv = kv;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.props = props;
    }

    public LoginResult login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "username/password 不能为空");
        }
        ConsoleUser user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BizException(ErrorCode.CONSOLE_BAD_CREDENTIALS));
        if (!user.enabled()) {
            throw new BizException(ErrorCode.CONSOLE_USER_DISABLED);
        }
        if (!passwordHasher.verify(rawPassword, user.passwordHash())) {
            throw new BizException(ErrorCode.CONSOLE_BAD_CREDENTIALS);
        }
        // 种子明文密码首次登录后渐进升级为 pbkdf2 存储
        String upgraded = passwordHasher.upgradeIfLegacy(rawPassword, user.passwordHash());
        if (upgraded != null) {
            userRepository.updatePasswordHash(user.username(), upgraded);
        }
        String token = randomToken();
        ConsoleSession session = new ConsoleSession(user.username(), user.displayName(), user.userRole(),
                user.tenantId(), user.operatorId(), user.operatorName(), user.operatorOrg(), user.team(),
                currentAuthVersion(user.username()));
        long ttlHours = props.console().tokenTtlHours();
        kv.set(TOKEN_KEY_PREFIX + token, toJson(session), Duration.ofHours(ttlHours));
        return new LoginResult(token, session.username(), session.displayName(), session.role(),
                session.tenantId(), session.operatorId(), session.operatorName(), session.operatorOrg(),
                session.team(), ttlHours * 3600);
    }

    public Optional<ConsoleSession> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return kv.get(TOKEN_KEY_PREFIX + token).flatMap(this::fromJson)
                .filter(session -> session.version() == currentAuthVersion(session.username()));
    }

    /** 使该用户已签发的全部会话失效（下次请求将被要求重新登录） */
    public void bumpAuthVersion(String username) {
        if (username != null && !username.isBlank()) {
            kv.increment(VERSION_KEY_PREFIX + username, VERSION_TTL);
        }
    }

    private long currentAuthVersion(String username) {
        return kv.get(VERSION_KEY_PREFIX + username).map(value -> {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            kv.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    private String toJson(ConsoleSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    private Optional<ConsoleSession> fromJson(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, ConsoleSession.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String randomToken() {
        byte[] buffer = new byte[32];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** 登录结果（token 仅返回一次，控制台存 sessionStorage） */
    public record LoginResult(String token, String username, String displayName, String role,
                              String tenantId, String operatorId, String operatorName,
                              String operatorOrg, String team, long expiresInSeconds) {
    }
}
