package com.tagplatform.console;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.common.crypto.PasswordHasher;
import com.tagplatform.console.model.ConsoleUser;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.meta.repo.TenantRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制台用户管理：列表、新建、启停、角色调整、管理员重置密码、本人修改密码。
 * 改密 / 停用 / 角色变更后会递增会话版本，使该用户已签发的 Token 立即失效。
 */
@Service
public class ConsoleUserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_.-]{2,63}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");
    private static final Set<String> ROLES = Set.of("ADMIN", "TENANT_ADMIN", "AUDITOR", "VIEWER",
            "TAGGER", "TASK_MANAGER");
    private static final Set<String> TENANT_MANAGED_ROLES = Set.of("AUDITOR", "VIEWER", "TAGGER", "TASK_MANAGER");

    private final ConsoleUserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final ConsoleSessionService sessions;
    private final TenantRepository tenantRepository;

    public ConsoleUserService(ConsoleUserRepository userRepository, PasswordHasher passwordHasher,
                              ConsoleSessionService sessions, TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
        this.tenantRepository = tenantRepository;
    }

    public List<UserView> list() {
        String actorRole = RequestContext.getConsoleRole();
        if ("ADMIN".equals(actorRole)) {
            return userRepository.findAll().stream().map(UserView::from).toList();
        }
        if ("TENANT_ADMIN".equals(actorRole)) {
            return userRepository.findByTenantId(requireActorTenant()).stream()
                    .map(UserView::from).toList();
        }
        throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
    }

    public UserView create(String username, String displayName, String rawPassword, String role,
                           String tenantId, String operatorId, String operatorName,
                           String operatorOrg, String team) {
        String actorRole = requireUserManager();
        String actorTenant = "TENANT_ADMIN".equals(actorRole) ? requireActorTenant() : null;
        String normalizedUsername = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "username 需满足 ^[a-z][a-z0-9_.-]{2,63}$（小写字母开头）");
        }
        String normalizedDisplayName = displayName == null ? "" : displayName.trim();
        if (normalizedDisplayName.isEmpty() || normalizedDisplayName.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, "display_name 不能为空且不超过 128 字符");
        }
        String normalizedRole = normalizeRole(role);
        String normalizedTenant = trimToNull(tenantId);
        String normalizedOperator = trimToNull(operatorId);
        String normalizedOperatorName = trimToNull(operatorName);
        String normalizedOperatorOrg = trimToNull(operatorOrg);
        String normalizedTeam = trimToNull(team);
        if (actorTenant != null) {
            if (!TENANT_MANAGED_ROLES.contains(normalizedRole)) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN,
                        "租户管理员只能创建/管理本租户的 " + TENANT_MANAGED_ROLES + " 角色");
            }
            if (normalizedTenant != null && !normalizedTenant.equals(actorTenant)) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "租户管理员只能管理本租户用户");
            }
            normalizedTenant = actorTenant;
        }
        if (normalizedTenant != null && tenantRepository.findByTenantId(normalizedTenant).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "租户不存在: " + normalizedTenant);
        }
        if ("TAGGER".equals(normalizedRole) || "TASK_MANAGER".equals(normalizedRole)) {
            if (normalizedTenant == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, normalizedRole + " 必须绑定 tenant_id");
            }
            if (normalizedOperator == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, normalizedRole + " 必须填写 operator_id");
            }
        }
        if ("TENANT_ADMIN".equals(normalizedRole)) {
            if (normalizedTenant == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "TENANT_ADMIN 必须绑定 tenant_id");
            }
            if (userRepository.countByTenantAndRole(normalizedTenant, "TENANT_ADMIN") > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "租户 " + normalizedTenant + " 已存在租户管理员，请先调整原管理员角色");
            }
        }
        if (normalizedOperator == null) {
            normalizedOperator = normalizedUsername;
        }
        if (normalizedOperatorName == null) {
            normalizedOperatorName = normalizedDisplayName;
        }
        validatePassword(rawPassword);
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw new BizException(ErrorCode.CONSOLE_USER_EXISTS, "控制台用户已存在: " + normalizedUsername);
        }
        userRepository.insert(normalizedUsername, passwordHasher.hash(rawPassword), normalizedDisplayName,
                normalizedRole, normalizedTenant, normalizedOperator, normalizedOperatorName,
                normalizedOperatorOrg, normalizedTeam);
        return userRepository.findByUsername(normalizedUsername).map(UserView::from)
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "用户创建后读取失败"));
    }

    public List<UserView> listAssignable(String tenantId) {
        return userRepository.listAssignable(tenantId).stream().map(UserView::from).toList();
    }

    public UserView changeStatus(String operator, String username, String status) {
        ConsoleUser target = requireUser(username);
        ensureManageable(target);
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 enabled/disabled");
        }
        if ("disabled".equals(status)) {
            if (target.username().equals(operator)) {
                throw new BizException(ErrorCode.CONSOLE_SELF_FORBIDDEN, "不能停用当前登录账号");
            }
            if ("ADMIN".equals(target.userRole()) && target.enabled()
                    && userRepository.countEnabledAdmins() <= 1) {
                throw new BizException(ErrorCode.CONSOLE_LAST_ADMIN);
            }
        }
        userRepository.updateStatus(target.username(), status);
        if ("disabled".equals(status)) {
            sessions.bumpAuthVersion(target.username());
        }
        return UserView.from(requireUser(target.username()));
    }

    public UserView changeRole(String operator, String username, String role) {
        ConsoleUser target = requireUser(username);
        String normalizedRole = normalizeRole(role);
        if (target.username().equals(operator)) {
            throw new BizException(ErrorCode.CONSOLE_SELF_FORBIDDEN, "不能修改当前登录账号的角色");
        }
        ensureManageable(target);
        String actorRole = RequestContext.getConsoleRole();
        if ("TENANT_ADMIN".equals(actorRole) && !TENANT_MANAGED_ROLES.contains(normalizedRole)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN,
                    "租户管理员只能分配 " + TENANT_MANAGED_ROLES + " 角色");
        }
        if ("ADMIN".equals(target.userRole()) && target.enabled() && !"ADMIN".equals(normalizedRole)
                && userRepository.countEnabledAdmins() <= 1) {
            throw new BizException(ErrorCode.CONSOLE_LAST_ADMIN);
        }
        if ("TENANT_ADMIN".equals(normalizedRole)) {
            String targetTenant = target.tenantId();
            if (targetTenant == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "租户管理员必须绑定 tenant_id");
            }
            if (!"TENANT_ADMIN".equals(target.userRole())
                    && userRepository.countByTenantAndRole(targetTenant, "TENANT_ADMIN") > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "租户 " + targetTenant + " 已存在租户管理员，请先调整原管理员角色");
            }
        }
        if (("TAGGER".equals(normalizedRole) || "TASK_MANAGER".equals(normalizedRole))
                && (target.tenantId() == null || target.operatorId() == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该用户未绑定租户/操作员，请新建账号并选择 " + normalizedRole + " 角色");
        }
        userRepository.updateRole(target.username(), normalizedRole);
        sessions.bumpAuthVersion(target.username());
        return UserView.from(requireUser(target.username()));
    }

    /** 管理员重置他人密码（无需旧密码）；重置后该用户全部会话失效 */
    public void resetPassword(String operator, String username, String newPassword) {
        ConsoleUser target = requireUser(username);
        if (target.username().equals(operator)) {
            throw new BizException(ErrorCode.CONSOLE_SELF_FORBIDDEN, "请通过右上角「修改密码」修改本人密码");
        }
        ensureManageable(target);
        validatePassword(newPassword);
        userRepository.updatePasswordHash(target.username(), passwordHasher.hash(newPassword));
        sessions.bumpAuthVersion(target.username());
    }

    /** 本人修改密码：校验旧密码，修改后当前会话同样失效，需要重新登录 */
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        ConsoleUser user = requireUser(username);
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "当前密码不能为空");
        }
        if (!passwordHasher.verify(oldPassword, user.passwordHash())) {
            throw new BizException(ErrorCode.CONSOLE_OLD_PASSWORD_MISMATCH);
        }
        validatePassword(newPassword);
        if (oldPassword.equals(newPassword)) {
            throw new BizException(ErrorCode.CONSOLE_PASSWORD_POLICY, "新密码不能与当前密码相同");
        }
        userRepository.updatePasswordHash(user.username(), passwordHasher.hash(newPassword));
        sessions.bumpAuthVersion(user.username());
    }

    private ConsoleUser requireUser(String username) {
        String normalized = username == null ? "" : username.trim();
        return userRepository.findByUsername(normalized)
                .orElseThrow(() -> new BizException(ErrorCode.CONSOLE_USER_NOT_FOUND, "控制台用户不存在: " + normalized));
    }

    private String requireUserManager() {
        String role = RequestContext.getConsoleRole();
        if (!"ADMIN".equals(role) && !"TENANT_ADMIN".equals(role)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
        return role;
    }

    private String requireActorTenant() {
        String tenant = RequestContext.getConsoleTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        return tenant;
    }

    /** 租户管理员只能操作本租户的普通角色账号；平台管理员不受限 */
    private void ensureManageable(ConsoleUser target) {
        String actorRole = RequestContext.getConsoleRole();
        if ("ADMIN".equals(actorRole)) {
            return;
        }
        if (!"TENANT_ADMIN".equals(actorRole)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
        String tenant = requireActorTenant();
        if (!Objects.equals(tenant, target.tenantId())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "租户管理员只能管理本租户用户");
        }
        if ("ADMIN".equals(target.userRole()) || "TENANT_ADMIN".equals(target.userRole())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "租户管理员不能操作管理员账号");
        }
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!ROLES.contains(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "role 仅支持 ADMIN / TENANT_ADMIN / AUDITOR / VIEWER / TAGGER / TASK_MANAGER");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BizException(ErrorCode.CONSOLE_PASSWORD_POLICY);
        }
    }

    /** 控制台用户视图（不含密码散列） */
    public record UserView(Long id, String username, String displayName, String role, String status,
                           String tenantId, String operatorId, String operatorName, String operatorOrg,
                           String team, LocalDateTime createdAt) {

        static UserView from(ConsoleUser user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.userRole(),
                    user.status(), user.tenantId(), user.operatorId(), user.operatorName(),
                    user.operatorOrg(), user.team(), user.createdAt());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
