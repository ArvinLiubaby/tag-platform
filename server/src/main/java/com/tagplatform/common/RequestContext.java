package com.tagplatform.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求级上下文（ThreadLocal）：request_id、租户、操作员、控制台会话、接口埋点采集。
 * 由 RequestIdFilter / 鉴权过滤器写入，请求结束时统一清理。
 */
public final class RequestContext {

    private static final ThreadLocal<Ctx> HOLDER = ThreadLocal.withInitial(Ctx::new);

    private RequestContext() {
    }

    private static final class Ctx {
        String requestId;
        String sourceIp;
        String appKey;
        String tenantId;
        String consoleUsername;
        String consoleRole;
        String consoleDisplayName;
        String consoleTenantId;
        String consoleOperatorId;
        String consoleOperatorName;
        String consoleOperatorOrg;
        String consoleTeam;
        Integer bizCode;
        RequestAnalytics analytics;
    }

    public static String getRequestId() {
        return HOLDER.get().requestId;
    }

    public static void setRequestId(String requestId) {
        HOLDER.get().requestId = requestId;
    }

    public static String getSourceIp() {
        return HOLDER.get().sourceIp;
    }

    public static void setSourceIp(String sourceIp) {
        HOLDER.get().sourceIp = sourceIp;
    }

    public static String getAppKey() {
        return HOLDER.get().appKey;
    }

    public static void setAppKey(String appKey) {
        HOLDER.get().appKey = appKey;
    }

    public static String getTenantId() {
        return HOLDER.get().tenantId;
    }

    public static void setTenantId(String tenantId) {
        HOLDER.get().tenantId = tenantId;
    }

    public static String getConsoleUsername() {
        return HOLDER.get().consoleUsername;
    }

    public static String getConsoleRole() {
        return HOLDER.get().consoleRole;
    }

    public static String getConsoleDisplayName() {
        return HOLDER.get().consoleDisplayName;
    }

    public static void setConsoleUser(String username, String role, String displayName) {
        setConsoleUser(username, role, displayName, null, null, null, null, null);
    }

    public static void setConsoleUser(String username, String role, String displayName, String tenantId,
                                      String operatorId, String operatorName, String operatorOrg, String team) {
        Ctx ctx = HOLDER.get();
        ctx.consoleUsername = username;
        ctx.consoleRole = role;
        ctx.consoleDisplayName = displayName;
        ctx.consoleTenantId = tenantId;
        ctx.consoleOperatorId = operatorId;
        ctx.consoleOperatorName = operatorName;
        ctx.consoleOperatorOrg = operatorOrg;
        ctx.consoleTeam = team;
    }

    public static String getConsoleTenantId() {
        return HOLDER.get().consoleTenantId;
    }

    public static String getConsoleOperatorId() {
        return HOLDER.get().consoleOperatorId;
    }

    public static String getConsoleOperatorName() {
        return HOLDER.get().consoleOperatorName;
    }

    public static String getConsoleOperatorOrg() {
        return HOLDER.get().consoleOperatorOrg;
    }

    public static String getConsoleTeam() {
        return HOLDER.get().consoleTeam;
    }

    // ---------------- 开放 API 埋点 ----------------

    public static void setBizCode(Integer bizCode) {
        HOLDER.get().bizCode = bizCode;
    }

    public static Integer getBizCode() {
        return HOLDER.get().bizCode;
    }

    public static void startAnalytics() {
        HOLDER.get().analytics = new RequestAnalytics();
    }

    public static RequestAnalytics getAnalytics() {
        return HOLDER.get().analytics;
    }

    public static void clearAnalytics() {
        HOLDER.get().analytics = null;
    }

    public static void recordTagQuery(String tagCode) {
        RequestAnalytics a = getAnalytics();
        if (a != null) a.addQuery(tagCode);
    }

    public static void recordTagWrite(String tagCode) {
        RequestAnalytics a = getAnalytics();
        if (a != null) a.addWrite(tagCode);
    }

    public static void recordTagDelete(String tagCode) {
        RequestAnalytics a = getAnalytics();
        if (a != null) a.addDelete(tagCode);
    }

    /** 取首个操作员/对象，避免批量写时被后续条目覆盖 */
    public static void setOperatorId(String operatorId) {
        RequestAnalytics a = getAnalytics();
        if (a != null) a.setOperatorId(operatorId);
    }

    public static void setObject(String objectType, String objectId) {
        RequestAnalytics a = getAnalytics();
        if (a != null) a.setObject(objectType, objectId);
    }

    public static final class RequestAnalytics {
        private final Map<String, Integer> queries = new LinkedHashMap<>();
        private final Map<String, Integer> writes = new LinkedHashMap<>();
        private final Map<String, Integer> deletes = new LinkedHashMap<>();
        private String operatorId;
        private String objectType;
        private String objectId;

        private void addQuery(String tagCode) {
            if (tagCode != null && !tagCode.isBlank()) queries.merge(tagCode, 1, Integer::sum);
        }

        private void addWrite(String tagCode) {
            if (tagCode != null && !tagCode.isBlank()) writes.merge(tagCode, 1, Integer::sum);
        }

        private void addDelete(String tagCode) {
            if (tagCode != null && !tagCode.isBlank()) deletes.merge(tagCode, 1, Integer::sum);
        }

        private void setOperatorId(String value) {
            if (operatorId == null && value != null && !value.isBlank()) operatorId = value;
        }

        private void setObject(String type, String id) {
            if (objectType == null && type != null && !type.isBlank()) {
                objectType = type;
                objectId = id;
            }
        }

        public Map<String, Integer> queries() {
            return queries;
        }

        public Map<String, Integer> writes() {
            return writes;
        }

        public Map<String, Integer> deletes() {
            return deletes;
        }

        public String operatorId() {
            return operatorId;
        }

        public String objectType() {
            return objectType;
        }

        public String objectId() {
            return objectId;
        }

        public int totalQueries() {
            return queries.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int totalWrites() {
            return writes.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int totalDeletes() {
            return deletes.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
