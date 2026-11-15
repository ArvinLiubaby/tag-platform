package com.tagplatform.meta.ext;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.repo.TagMetaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户级标签扩展信息：定义管理与取值/保存。
 *
 * <p>取值范围与默认值：未显式配置的标签读取属性定义上的 default_value，并标记 inherited=true，
 * 便于前端区分「已配置」与「继承默认」。
 */
@Service
public class TagExtService {

    private static final Set<String> VALUE_TYPES = Set.of("string", "text", "bool", "int", "enum");
    private static final Set<String> SUBJECT_TYPES = Set.of("TAG", "DIM1");

    private final TagExtRepository repository;
    private final TagMetaRepository tagMetaRepository;
    private final AuditLogService auditLogService;

    public TagExtService(TagExtRepository repository, TagMetaRepository tagMetaRepository,
                         AuditLogService auditLogService) {
        this.repository = repository;
        this.tagMetaRepository = tagMetaRepository;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------ 租户

    public String resolveTenant(String requestedTenantId) {
        String role = RequestContext.getConsoleRole();
        if (role == null) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
        String bound = trim(RequestContext.getConsoleTenantId());
        String requested = trim(requestedTenantId);
        if ("ADMIN".equals(role)) {
            String tenant = requested != null ? requested : bound;
            if (tenant == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "管理员需指定 tenantId");
            }
            return tenant;
        }
        if (bound == null) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        if (requested != null && !requested.equals(bound)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "不能跨租户访问: " + requested);
        }
        return bound;
    }

    // ------------------------------------------------------------ 属性定义

    public List<TagExtModels.AttrDef> listDefs(String tenantId) {
        return repository.listDefs(tenantId, null, false);
    }

    @Transactional
    public TagExtModels.AttrDef createDef(String tenantId, TagExtModels.DefRequest req) {
        String key = trim(req.attrKey());
        String name = trim(req.attrName());
        if (key == null || !key.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "attrKey 需为小写字母开头的字母/数字/下划线（2-64 位）");
        }
        if (name == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "attrName 不能为空");
        }
        String subjectType = req.subjectType() == null ? "TAG" : req.subjectType().toUpperCase();
        if (!SUBJECT_TYPES.contains(subjectType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "subjectType 仅支持 TAG / DIM1");
        }
        String valueType = req.valueType() == null ? "string" : req.valueType().toLowerCase();
        if (!VALUE_TYPES.contains(valueType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "valueType 仅支持 string / text / bool / int / enum");
        }
        if (repository.findDef(tenantId, key).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "属性已存在: " + key);
        }
        TagExtModels.AttrDef def = new TagExtModels.AttrDef(null, tenantId, key, name, subjectType, valueType,
                req.optionsJson(), req.defaultValue(), req.editable() == null || req.editable(),
                req.visible() == null || req.visible(), req.sortNo() == null ? 100 : req.sortNo(), "enabled");
        validateEnumOptions(def);
        repository.insertDef(def);
        audit(tenantId, "TAG_EXT_DEF_CREATE", "新增扩展属性 " + key);
        return repository.findDef(tenantId, key).orElse(def);
    }

    @Transactional
    public TagExtModels.AttrDef updateDef(String tenantId, String attrKey, TagExtModels.DefRequest req) {
        TagExtModels.AttrDef exist = repository.findDef(tenantId, attrKey)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "属性不存在: " + attrKey));
        String valueType = req.valueType() == null ? exist.valueType() : req.valueType().toLowerCase();
        if (!VALUE_TYPES.contains(valueType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "valueType 仅支持 string / text / bool / int / enum");
        }
        TagExtModels.AttrDef merged = new TagExtModels.AttrDef(exist.id(), tenantId, attrKey,
                req.attrName() == null ? exist.attrName() : trim(req.attrName()),
                exist.subjectType(), valueType,
                req.optionsJson() == null ? exist.optionsJson() : req.optionsJson(),
                req.defaultValue() == null ? exist.defaultValue() : req.defaultValue(),
                req.editable() == null ? exist.editable() : req.editable(),
                req.visible() == null ? exist.visible() : req.visible(),
                req.sortNo() == null ? exist.sortNo() : req.sortNo(),
                exist.status());
        validateEnumOptions(merged);
        repository.updateDef(tenantId, attrKey, merged.attrName(), merged.valueType(), merged.optionsJson(),
                merged.defaultValue(), merged.editable(), merged.visible(), merged.sortNo());
        audit(tenantId, "TAG_EXT_DEF_UPDATE", "修改扩展属性 " + attrKey);
        return repository.findDef(tenantId, attrKey).orElse(merged);
    }

    @Transactional
    public void changeDefStatus(String tenantId, String attrKey, String status) {
        if (!Set.of("enabled", "disabled").contains(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 enabled / disabled");
        }
        repository.findDef(tenantId, attrKey)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "属性不存在: " + attrKey));
        repository.updateDefStatus(tenantId, attrKey, status);
        audit(tenantId, "TAG_EXT_DEF_STATUS", "扩展属性 " + attrKey + " → " + status);
    }

    // ------------------------------------------------------------ 标签扩展值

    public TagExtModels.TagExtView tagExt(String tenantId, String tagCode) {
        tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        Map<String, String> values = repository.values(tenantId, "TAG", tagCode);
        List<TagExtModels.AttrView> attrs = new ArrayList<>();
        for (TagExtModels.AttrDef def : repository.listDefs(tenantId, "TAG", true)) {
            if (!def.visible()) {
                continue;
            }
            boolean has = values.containsKey(def.attrKey());
            attrs.add(new TagExtModels.AttrView(def.attrKey(), def.attrName(), def.subjectType(),
                    def.valueType(), def.optionsJson(), def.defaultValue(), def.editable(), def.visible(),
                    def.sortNo(), has ? values.get(def.attrKey()) : def.defaultValue(), !has));
        }
        return new TagExtModels.TagExtView(tenantId, tagCode, attrs);
    }

    @Transactional
    public TagExtModels.TagExtView saveTagExt(String tenantId, String tagCode, TagExtModels.SaveExtRequest req) {
        tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        Map<String, String> values = req == null ? null : req.values();
        if (values == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "values 不能为空");
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            TagExtModels.AttrDef def = repository.findDef(tenantId, e.getKey())
                    .filter(d -> "TAG".equals(d.subjectType()))
                    .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "未定义的标签扩展属性: " + e.getKey()));
            if (!def.editable()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "属性不可编辑: " + e.getKey());
            }
            String value = trim(e.getValue());
            if (value == null) {
                repository.deleteValue(tenantId, "TAG", tagCode, def.attrKey());
            } else {
                validateValue(def, value);
                repository.upsertValue(tenantId, "TAG", tagCode, def.attrKey(), value,
                        RequestContext.getConsoleUsername());
            }
        }
        audit(tenantId, "TAG_EXT_SAVE", "保存标签扩展信息 " + tagCode);
        return tagExt(tenantId, tagCode);
    }

    /** 列表页批量取值：tagCode → (attrKey → value) */
    public Map<String, Map<String, String>> extSummary(String tenantId) {
        return repository.allTagValues(tenantId);
    }

    // ------------------------------------------------------------ 一级维度优先级

    public List<TagExtModels.DimPriority> dimPriorities(String tenantId) {
        return repository.dimPriorities(tenantId);
    }

    @Transactional
    public List<TagExtModels.DimPriority> saveDimPriorities(String tenantId, TagExtModels.DimPriorityRequest req) {
        if (req == null || req.items() == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "items 不能为空");
        }
        for (TagExtModels.DimPriority item : req.items()) {
            String dimCode = trim(item.dimCode());
            if (dimCode == null) {
                continue;
            }
            if (item.priority() == null) {
                repository.deleteValue(tenantId, "DIM1", dimCode, "dim1_priority");
            } else {
                repository.upsertValue(tenantId, "DIM1", dimCode, "dim1_priority",
                        String.valueOf(item.priority()), RequestContext.getConsoleUsername());
            }
        }
        audit(tenantId, "TAG_DIM_PRIORITY_SAVE", "保存一级维度优先级");
        return repository.dimPriorities(tenantId);
    }

    // ------------------------------------------------------------ helpers

    private void validateValue(TagExtModels.AttrDef def, String value) {
        switch (def.valueType()) {
            case "bool" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, def.attrName() + " 仅支持 true / false");
                }
            }
            case "int" -> {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID, def.attrName() + " 需为整数");
                }
            }
            case "enum" -> {
                Set<String> options = enumOptions(def);
                if (!options.isEmpty() && !options.contains(value)) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            def.attrName() + " 取值需为: " + String.join(" / ", options));
                }
            }
            default -> {
                if (value.length() > 20000) {
                    throw new BizException(ErrorCode.PARAM_INVALID, def.attrName() + " 内容过长（上限 20000 字符）");
                }
            }
        }
    }

    private void validateEnumOptions(TagExtModels.AttrDef def) {
        if ("enum".equals(def.valueType())) {
            Set<String> options = enumOptions(def);
            if (options.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "enum 类型必须提供 optionsJson，如 [\"主\",\"副\"]");
            }
            String dv = trim(def.defaultValue());
            if (dv != null && !options.contains(dv)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "默认值不在选项中: " + dv);
            }
        }
    }

    /** 解析 ["主","副"] 形式的选项；容错逗号分隔 */
    private Set<String> enumOptions(TagExtModels.AttrDef def) {
        Set<String> out = new LinkedHashSet<>();
        String json = trim(def.optionsJson());
        if (json == null) {
            return out;
        }
        String body = json.startsWith("[") ? json.substring(1, json.endsWith("]") ? json.length() - 1 : json.length()) : json;
        for (String token : body.split(",")) {
            String v = token.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private void audit(String tenantId, String action, String detail) {
        try {
            auditLogService.record(action, tenantId, "TAG_EXT", null, null, detail, "success");
        } catch (RuntimeException ignored) {
            // 审计失败不影响主流程
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
