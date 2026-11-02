package com.tagplatform.meta.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.model.TagVersion;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.repo.TagVersionRepository;
import com.tagplatform.meta.service.TagMetaService;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 组合标签服务：派生标签定义的编排、校验、预览、发布与回滚。
 * 派生标签只做"定义"，值在查询期由 {@link DerivedTagResolver} 实时计算，不落 tag_value_latest。
 */
@Service
public class TagCompositionService {

    private static final Pattern TAG_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final Set<String> OUTPUT_TYPES = Set.of("boolean", "enum", "number", "string");
    private static final Map<String, Set<String>> OPS_BY_TYPE = Map.of(
            "boolean", Set.of("is_true", "is_false", "is_empty", "is_not_empty"),
            "enum", Set.of("eq", "ne", "in", "not_in", "is_empty", "is_not_empty"),
            "number", Set.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "is_empty", "is_not_empty"),
            "string", Set.of("eq", "ne", "contains", "not_contains", "is_empty", "is_not_empty"),
            "date", Set.of("before", "after", "is_empty", "is_not_empty"));
    private static final Map<String, Integer> SENSITIVITY_RANK = Map.of("L1", 1, "L2", 2, "L3", 3);
    private static final int MAX_GROUPS = 5;
    private static final int MAX_CONDITIONS = 20;
    private static final int MAX_DEPTH = 3;

    private final TagCompositionRepository compositionRepository;
    private final TagMetaRepository tagMetaRepository;
    private final TagVersionRepository tagVersionRepository;
    private final TagMetaService tagMetaService;
    private final ObjectTypeRepository objectTypeRepository;
    private final TagValueStore tagValueStore;
    private final DerivedTagResolver derivedTagResolver;
    private final ObjectMapper objectMapper;

    public TagCompositionService(TagCompositionRepository compositionRepository,
                                 TagMetaRepository tagMetaRepository,
                                 TagVersionRepository tagVersionRepository,
                                 TagMetaService tagMetaService,
                                 ObjectTypeRepository objectTypeRepository,
                                 TagValueStore tagValueStore,
                                 DerivedTagResolver derivedTagResolver,
                                 ObjectMapper objectMapper) {
        this.compositionRepository = compositionRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.tagVersionRepository = tagVersionRepository;
        this.tagMetaService = tagMetaService;
        this.objectTypeRepository = objectTypeRepository;
        this.tagValueStore = tagValueStore;
        this.derivedTagResolver = derivedTagResolver;
        this.objectMapper = objectMapper;
    }

    /* ============================================================
     * 查询
     * ============================================================ */
    public List<CompositionModels.CompositionView> list(String keyword, String status) {
        String keywordLower = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<TagComposition> comps = compositionRepository.listAll(normalizeStatus(status));
        Map<String, TagMeta> metas = metaMap(comps.stream().map(TagComposition::tagCode).toList());
        String own = RequestContext.getConsoleTenantId();
        String role = RequestContext.getConsoleRole();
        return comps.stream()
                .filter(c -> "ADMIN".equals(role) || c.visibleTo(own))
                .filter(c -> keywordLower == null || keywordLower.isBlank()
                        || c.tagCode().toLowerCase(Locale.ROOT).contains(keywordLower)
                        || (metas.containsKey(c.tagCode()) && metas.get(c.tagCode()).tagName() != null
                        && metas.get(c.tagCode()).tagName().toLowerCase(Locale.ROOT).contains(keywordLower)))
                .map(c -> view(c, metas.get(c.tagCode())))
                .toList();
    }

    public CompositionModels.CompositionView detail(String tagCode) {
        TagComposition comp = requireComposition(tagCode);
        ensureVisible(comp);
        return view(comp, tagMetaRepository.findByCode(tagCode).orElse(null));
    }

    /* ============================================================
     * 预检
     * ============================================================ */
    public CompositionModels.PreviewResponse preview(CompositionModels.PreviewRequest req) {
        String tenantId = requireTenant(req.tenantId());
        String objectType = requireObjectType(req.objectType());
        if (req.objectId() == null || req.objectId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "object_id 不能为空");
        }
        CompositionRule rule = validateRule(null, req.rule(), req.outputType(), objectType, List.of(tenantId));
        Map<String, String> base = latestValues(tenantId, objectType, req.objectId().trim());
        Map<String, CompositionEvaluator.Outcome> derived = derivedTagResolver.resolveOutcomes(tenantId, objectType, base);
        Map<String, String> combined = new HashMap<>(base);
        derived.forEach((code, outcome) -> {
            if (outcome.value() != null) {
                combined.put(code, outcome.value());
            }
        });
        Map<String, String> names = derivedTagResolver.names(combined.keySet());
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule, combined, names);

        Set<String> directCodes = derivedTagResolver.referencedCodes(rule);
        Map<String, TagMeta> metas = metaMap(new ArrayList<>(directCodes));
        List<CompositionModels.PreviewResponse.InputValue> inputs = directCodes.stream()
                .map(code -> new CompositionModels.PreviewResponse.InputValue(
                        code,
                        metas.containsKey(code) ? metas.get(code).tagName() : null,
                        combined.get(code),
                        metas.containsKey(code) ? metas.get(code).valueType() : null,
                        compositionRepository.find(code).isPresent()))
                .toList();
        List<String> warnings = new ArrayList<>();
        if (!outcome.missingTags().isEmpty()) {
            warnings.add("以下标签无当前值：" + String.join("、", outcome.missingTags()));
        }
        return new CompositionModels.PreviewResponse(objectType, req.objectId().trim(), outcome.value(),
                outcome.matched(), outcome.status(), outcome.trace(), outcome.missingTags(), inputs, warnings);
    }

    /* ============================================================
     * 创建 / 更新
     * ============================================================ */
    @Transactional
    public CompositionModels.CompositionView create(CompositionModels.SaveRequest req) {
        String actor = actor();
        String tagCode = normalize(req.tagCode());
        if (tagCode == null || !TAG_CODE_PATTERN.matcher(tagCode).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag_code 需匹配 ^[a-z][a-z0-9_]{2,63}$");
        }
        if (tagMetaRepository.findByCode(tagCode).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签编码已存在: " + tagCode);
        }
        if (compositionRepository.find(tagCode).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合标签已存在: " + tagCode);
        }
        String tagName = requireText(req.tagName(), "标签名称不能为空", 128);
        String objectType = requireObjectType(req.objectType());
        String tenantId = canonicalTenant(req.tenantId());
        List<String> tenants = splitTenants(tenantId);
        CompositionRule rule = validateRule(null, req.rule(), req.outputType(), objectType, tenants);
        RuleMeta ruleMeta = ruleMeta(rule);

        LocalDateTime now = LocalDateTime.now();
        String ruleJson = toJson(rule);
        String outputJson = toJson(rule.output());
        String valueRange = valueRange(rule, req.outputType());
        TagMeta meta = new TagMeta(null, tagCode, tagName, normalize(req.categoryCode()), requireOutputType(req.outputType()),
                valueRange, false, "derived", false, 0, tenantId, ruleMeta.sensitivity(), "offline", 1,
                objectType, "derived", false, now, now);
        tagMetaRepository.insert(meta);
        tagVersionRepository.insert(meta, "create", actor, "创建组合标签: " + tagName);
        tagVersionRepository.updateCompositionJson(tagCode, meta.version(), ruleJson);

        TagComposition comp = new TagComposition(null, tagCode, tenantId, objectType, rule.logic(),
                ruleJson, meta.valueType(), outputJson, rule.missingPolicy(), normalize(req.description()),
                TagComposition.STATUS_DRAFT, 1, actor, actor, now, now);
        compositionRepository.insert(comp);
        return view(comp, meta);
    }

    @Transactional
    public CompositionModels.CompositionView update(String tagCode, CompositionModels.SaveRequest req) {
        String actor = actor();
        TagComposition comp = requireComposition(tagCode);
        TagMeta meta = requireDerivedMeta(tagCode);
        ensureManage(comp);
        String tagName = requireText(req.tagName(), "标签名称不能为空", 128);
        String objectType = requireObjectType(req.objectType());
        List<String> tenants = splitTenants(meta.tenantId());
        CompositionRule rule = validateRule(tagCode, req.rule(), req.outputType(), objectType, tenants);
        RuleMeta ruleMeta = ruleMeta(rule);
        String valueRange = valueRange(rule, req.outputType());

        int updated = tagMetaRepository.updateDefinition(tagCode, tagName, normalize(req.categoryCode()),
                meta.tenantId(), requireOutputType(req.outputType()), valueRange, false, "derived", false, 0,
                ruleMeta.sensitivity(), objectType, "derived", false, meta.version());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已被其他人修改，请刷新后重试");
        }
        TagMeta fresh = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        String ruleJson = toJson(rule);
        tagVersionRepository.insert(fresh, "update", actor, "更新组合规则");
        tagVersionRepository.updateCompositionJson(tagCode, fresh.version(), ruleJson);
        compositionRepository.updateRule(tagCode, objectType, rule.logic(), ruleJson, fresh.valueType(),
                toJson(rule.output()), rule.missingPolicy(), normalize(req.description()), meta.tenantId(), actor);
        return view(requireComposition(tagCode), fresh);
    }

    /* ============================================================
     * 发布 / 停用 / 回滚
     * ============================================================ */
    @Transactional
    public CompositionModels.CompositionView publish(String tagCode) {
        TagComposition comp = requireComposition(tagCode);
        TagMeta meta = requireDerivedMeta(tagCode);
        ensureManage(comp);
        CompositionRule rule = derivedTagResolver.parseRule(comp);
        if (rule == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合规则解析失败，请检查后重新保存");
        }
        validateRule(tagCode, rule, comp.outputType(), comp.objectType(), splitTenants(comp.tenantId()));
        compositionRepository.updateStatus(tagCode, TagComposition.STATUS_PUBLISHED, actor());
        tagMetaRepository.updateStatus(tagCode, "online");
        return view(requireComposition(tagCode), tagMetaRepository.findByCode(tagCode).orElse(meta));
    }

    @Transactional
    public CompositionModels.CompositionView disable(String tagCode) {
        TagComposition comp = requireComposition(tagCode);
        ensureManage(comp);
        compositionRepository.updateStatus(tagCode, TagComposition.STATUS_DISABLED, actor());
        tagMetaRepository.updateStatus(tagCode, "offline");
        return view(requireComposition(tagCode), tagMetaRepository.findByCode(tagCode).orElse(null));
    }

    @Transactional
    public CompositionModels.CompositionView rollback(String tagCode, int targetVersion) {
        TagComposition comp = requireComposition(tagCode);
        requireDerivedMeta(tagCode);
        ensureManage(comp);
        TagVersion target = tagVersionRepository.find(tagCode, targetVersion)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "版本不存在: v" + targetVersion));
        if (target.compositionJson() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该版本不是组合标签版本，无法回滚规则");
        }
        TagMeta updated = tagMetaService.rollback(tagCode, targetVersion);
        tagVersionRepository.updateCompositionJson(tagCode, updated.version(), target.compositionJson());
        CompositionRule rule = parseJson(target.compositionJson(), CompositionRule.class);
        if (rule == null || rule.output() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标版本规则解析失败");
        }
        compositionRepository.updateRule(tagCode, updated.objectType(), rule.logic(), target.compositionJson(),
                updated.valueType(), toJson(rule.output()), rule.missingPolicy(),
                comp.description(), updated.tenantId(), actor());
        return view(requireComposition(tagCode), updated);
    }

    /* ============================================================
     * 使用情况
     * ============================================================ */
    public CompositionModels.UsageView usage(String tagCode, String requestedTenant) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        String role = RequestContext.getConsoleRole();
        if (!"ADMIN".equals(role) && !meta.visibleTo(RequestContext.getConsoleTenantId())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
        boolean derived = "derived".equalsIgnoreCase(meta.tagType())
                || compositionRepository.find(tagCode).isPresent();
        String tenantId = resolveQueryTenant(meta, requestedTenant);

        long currentTotal = 0;
        int evaluated = 0;
        String compositionStatus = null;
        List<CompositionModels.UsageView.CurrentValue> currentValues = List.of();
        if (tenantId != null) {
            if (derived) {
                TagComposition comp = compositionRepository.find(tagCode).orElse(null);
                if (comp != null) {
                    compositionStatus = comp.status();
                    List<String> candidates = derivedCandidateIds(tenantId, comp);
                    evaluated = candidates.size();
                    currentValues = computeDerivedValues(tenantId, comp, candidates, 20);
                    currentTotal = currentValues.stream()
                            .filter(v -> CompositionEvaluator.STATUS_EFFECTIVE.equals(v.status()))
                            .count();
                }
            } else {
                currentTotal = tagValueStore.countLatestByTag(tenantId, tagCode);
                currentValues = tagValueStore.listLatestByTag(tenantId, tagCode, 20).stream()
                        .map(v -> new CompositionModels.UsageView.CurrentValue(v.objectType(), v.objectId(), v.tagValue(),
                                v.status(), v.bizTime(), v.operateTime(), v.expireTime(), v.operatorName(), v.source(), null))
                        .toList();
            }
        }

        List<CompositionModels.UsageView.Reference> refs = compositionRepository.listAll(null).stream()
                .filter(c -> !c.tagCode().equals(tagCode))
                .filter(c -> derivedTagResolver.referencedCodes(derivedTagResolver.parseRule(c)).contains(tagCode))
                .map(c -> new CompositionModels.UsageView.Reference(c.tagCode(),
                        tagMetaRepository.findByCode(c.tagCode()).map(TagMeta::tagName).orElse(c.tagCode()),
                        c.status()))
                .toList();
        List<TagValue> samples = tenantId == null ? List.of()
                : tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                tenantId, null, null, null, tagCode, null, null, 0, 8));
        List<CompositionModels.UsageView.RecentValue> recent = samples.stream()
                .map(v -> new CompositionModels.UsageView.RecentValue(v.objectType(), v.objectId(), v.tagValue(),
                        v.status(), v.operateTime(), v.operatorName(), v.source()))
                .toList();
        return new CompositionModels.UsageView(tagCode, derived, compositionStatus, currentTotal, evaluated,
                currentValues, refs, recent);
    }

    /** 供标签查看页使用：按输入标签 latest 抽样计算派生标签当前值 */
    public List<CompositionModels.UsageView.CurrentValue> computedValues(String tagCode, String tenantId, int limit) {
        TagComposition comp = compositionRepository.find(tagCode).orElse(null);
        if (comp == null) {
            return List.of();
        }
        return computeDerivedValues(tenantId, comp, derivedCandidateIds(tenantId, comp),
                Math.max(1, Math.min(limit, 100)));
    }

    /** 删除组合：解除引用与市场校验后删除规则，派生标签归档（编码与版本保留，可审计） */
    @Transactional
    public void delete(String tagCode) {
        TagComposition comp = requireComposition(tagCode);
        requireDerivedMeta(tagCode);
        ensureManage(comp);
        List<String> referrers = compositionRepository.listAll(null).stream()
                .filter(c -> !c.tagCode().equals(tagCode))
                .filter(c -> derivedTagResolver.referencedCodes(derivedTagResolver.parseRule(c)).contains(tagCode))
                .map(TagComposition::tagCode)
                .toList();
        if (!referrers.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该组合被其他组合引用，请先调整规则：" + String.join("、", referrers));
        }
        if (compositionRepository.hasActiveMarketListing(tagCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该标签已上架标签市场，请先下架后再删除");
        }
        compositionRepository.delete(tagCode);
        tagMetaRepository.updateStatus(tagCode, "archived");
    }

    /** 派生标签抽样：输入标签最近有值的对象（最多 60 个），按评估结果返回前 limit 条 */
    private List<CompositionModels.UsageView.CurrentValue> computeDerivedValues(String tenantId, TagComposition comp,
                                                                                List<String> candidates, int limit) {
        CompositionRule rule = derivedTagResolver.parseRule(comp);
        if (rule == null) {
            return List.of();
        }
        List<CompositionModels.UsageView.CurrentValue> rows = new ArrayList<>();
        for (String objectId : candidates) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, String> base = latestValues(tenantId, comp.objectType(), objectId);
            Map<String, CompositionEvaluator.Outcome> outcomes =
                    derivedTagResolver.resolveOutcomes(tenantId, comp.objectType(), base);
            CompositionEvaluator.Outcome outcome = outcomes.get(comp.tagCode());
            if (outcome == null) {
                // 草稿/已停用的组合不在发布集合内，这里按规则直接试算（嵌套派生使用已发布结果）
                Map<String, String> merged = new java.util.HashMap<>(base);
                outcomes.forEach((key, value) -> {
                    if (value.value() != null) {
                        merged.put(key, value.value());
                    }
                });
                outcome = CompositionEvaluator.evaluate(rule, merged,
                        derivedTagResolver.names(merged.keySet()));
            }
            String status = outcome == null ? CompositionEvaluator.STATUS_UNKNOWN : outcome.status();
            Set<String> inputs = derivedTagResolver.referencedCodes(rule);
            Map<String, String> inputNames = derivedTagResolver.names(inputs);
            String detail = inputs.stream()
                    .map(code -> (inputNames.getOrDefault(code, code)) + "="
                            + (base.containsKey(code) ? base.get(code) : "∅"))
                    .collect(java.util.stream.Collectors.joining("；"));
            if (detail.length() > 100) {
                detail = detail.substring(0, 99) + "…";
            }
            rows.add(new CompositionModels.UsageView.CurrentValue(comp.objectType(), objectId,
                    outcome == null ? null : outcome.value(), status, null, null, null, null, "derived", detail));
        }
        return rows;
    }

    /** 候选对象：组合条件引用的标签在当前租户 latest 中出现过的对象，按最近更新时间优先 */
    private List<String> derivedCandidateIds(String tenantId, TagComposition comp) {
        CompositionRule rule = derivedTagResolver.parseRule(comp);
        if (rule == null) {
            return List.of();
        }
        LinkedHashSet<String> objectIds = new LinkedHashSet<>();
        for (String code : derivedTagResolver.referencedCodes(rule)) {
            for (TagValue v : tagValueStore.listLatestByTag(tenantId, code, 30)) {
                if (comp.objectType().equals(v.objectType())) {
                    objectIds.add(v.objectId());
                }
                if (objectIds.size() >= 60) {
                    return new ArrayList<>(objectIds);
                }
            }
        }
        return new ArrayList<>(objectIds);
    }

    private String resolveQueryTenant(TagMeta meta, String requested) {
        String role = RequestContext.getConsoleRole();
        String own = RequestContext.getConsoleTenantId();
        if ("TENANT_ADMIN".equals(role)) {
            if (own == null || own.isBlank()) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
            }
            return own;
        }
        if (requested != null && !requested.isBlank()) {
            if (!meta.visibleTo(requested.trim())) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "标签对该租户不可见: " + requested);
            }
            return requested.trim();
        }
        if (meta.tenantId() != null) {
            for (String token : meta.tenantId().split(",")) {
                String id = token.trim();
                if (!id.isEmpty() && !"PUBLIC".equals(id)) {
                    return id;
                }
            }
        }
        return own == null || own.isBlank() ? null : own;
    }

    /* ============================================================
     * 规则校验
     * ============================================================ */
    private CompositionRule validateRule(String selfCode, CompositionRule raw, String outputType,
                                        String objectType, List<String> tenants) {
        requireOutputType(outputType);
        if (raw == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合规则不能为空");
        }
        String logic = normalizeLogic(raw.logic());
        List<CompositionRule.ConditionGroup> groups = raw.groups() == null ? List.of() : raw.groups();
        if (groups.isEmpty() || groups.size() > MAX_GROUPS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "条件组数量需在 1~" + MAX_GROUPS + " 之间");
        }
        int total = 0;
        List<CompositionRule.ConditionGroup> normalizedGroups = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (CompositionRule.ConditionGroup group : groups) {
            if (group == null || group.conditions() == null || group.conditions().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "条件组内至少需要一条条件");
            }
            total += group.conditions().size();
            List<CompositionRule.Condition> conditions = new ArrayList<>();
            for (CompositionRule.Condition c : group.conditions()) {
                conditions.add(validateCondition(selfCode, c, objectType, tenants, seenCodes));
            }
            normalizedGroups.add(new CompositionRule.ConditionGroup(normalizeLogic(group.logic()), conditions));
        }
        if (total > MAX_CONDITIONS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "条件总数不能超过 " + MAX_CONDITIONS);
        }
        CompositionRule.Output output = validateOutput(outputType, raw.output(), seenCodes, objectType, tenants, selfCode);
        String missingPolicy = raw.missingPolicy() == null ? CompositionRule.MISSING_HIT_FALSE
                : raw.missingPolicy().trim().toLowerCase(Locale.ROOT);
        if (!CompositionRule.MISSING_HIT_FALSE.equals(missingPolicy)
                && !CompositionRule.MISSING_UNKNOWN.equals(missingPolicy)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "missing_policy 仅支持 hit_false / unknown");
        }
        validateDepthAndCycle(selfCode, normalizedGroups);
        return new CompositionRule(logic, normalizedGroups, output, missingPolicy);
    }

    private CompositionRule.Condition validateCondition(String selfCode, CompositionRule.Condition c,
                                                        String objectType, List<String> tenants,
                                                        Set<String> seenCodes) {
        if (c == null || c.tagCode() == null || c.tagCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "条件缺少标签编码");
        }
        String code = c.tagCode().trim();
        if (code.equals(selfCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合标签不能引用自身");
        }
        TagMeta meta = tagMetaRepository.findByCode(code)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + code));
        if (!meta.online()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "引用的标签不在线: " + code);
        }
        if (!meta.supportsObjectType(objectType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签 " + code + " 不适用于对象类型 " + objectType);
        }
        for (String tenant : tenants) {
            if (!meta.visibleTo(tenant)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "标签 " + code + " 对租户 " + tenant + " 不可见");
            }
        }
        Optional<TagComposition> nested = compositionRepository.find(code);
        if (nested.isPresent() && !TagComposition.STATUS_PUBLISHED.equals(nested.get().status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "引用的组合标签未发布: " + code);
        }
        String op = normalizeOp(c.op());
        Set<String> allowed = OPS_BY_TYPE.getOrDefault(meta.valueType(), Set.of());
        if (!allowed.contains(op)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "标签 " + code + "（" + meta.valueType() + "）不支持运算符 " + op);
        }
        String value = normalize(c.value());
        String valueTo = normalize(c.valueTo());
        if (!"is_empty".equals(op) && !"is_not_empty".equals(op) && !"is_true".equals(op) && !"is_false".equals(op)) {
            if (value == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "条件缺少比较值: " + code);
            }
        }
        if ("between".equals(op) && valueTo == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "between 需要同时提供 value 与 value_to");
        }
        if (("gt".equals(op) || "gte".equals(op) || "lt".equals(op) || "lte".equals(op)
                || "between".equals(op)) && !isNumber(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数值比较需要数字值: " + code);
        }
        seenCodes.add(code);
        return new CompositionRule.Condition(code, op, value, valueTo);
    }

    private CompositionRule.Output validateOutput(String outputType, CompositionRule.Output output,
                                                  Set<String> conditionCodes, String objectType,
                                                  List<String> tenants, String selfCode) {
        if (output == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "输出规则不能为空");
        }
        String mode = output.mode() == null ? CompositionRule.Output.MODE_FIXED
                : output.mode().trim().toLowerCase(Locale.ROOT);
        if (CompositionRule.Output.MODE_FIXED.equals(mode)) {
            String value = requireText(output.value(), "输出值不能为空", 128);
            validateOutputValue(outputType, value);
            return new CompositionRule.Output(mode, value, null, null, null);
        }
        if (CompositionRule.Output.MODE_MAPPING.equals(mode)) {
            if (!"enum".equals(outputType) && !"string".equals(outputType)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "映射输出仅支持 enum / string 类型");
            }
            String source = normalize(output.sourceTagCode());
            if (source == null || !conditionCodes.contains(source)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "映射来源标签必须是组合条件中引用的标签");
            }
            if (output.entries() == null || output.entries().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "映射表至少需要一行");
            }
            List<CompositionRule.Output.Mapping> entries = new ArrayList<>();
            for (CompositionRule.Output.Mapping m : output.entries()) {
                String from = requireText(m == null ? null : m.from(), "映射来源值不能为空", 128);
                String to = requireText(m.to(), "映射目标值不能为空", 128);
                entries.add(new CompositionRule.Output.Mapping(from, to));
            }
            return new CompositionRule.Output(mode, null, source, normalize(output.defaultValue()), entries);
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "output.mode 仅支持 fixed / mapping");
    }

    private void validateOutputValue(String outputType, String value) {
        switch (outputType) {
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "布尔输出只能为 true / false");
                }
            }
            case "number" -> {
                if (!isNumber(value)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "数值输出必须是数字");
                }
            }
            default -> {
            }
        }
    }

    private void validateDepthAndCycle(String selfCode, List<CompositionRule.ConditionGroup> groups) {
        int maxDepth = 0;
        for (CompositionRule.ConditionGroup group : groups) {
            for (CompositionRule.Condition c : group.conditions()) {
                if (compositionRepository.find(c.tagCode()).isEmpty()) {
                    continue;
                }
                int depth = 1 + depthOf(c.tagCode(), new LinkedHashSet<>());
                maxDepth = Math.max(maxDepth, depth);
            }
        }
        if (maxDepth > MAX_DEPTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合嵌套层级不能超过 " + MAX_DEPTH);
        }
        if (selfCode != null) {
            ensureNoCycle(selfCode, groups);
        }
    }

    private void ensureNoCycle(String selfCode, List<CompositionRule.ConditionGroup> groups) {
        for (CompositionRule.ConditionGroup group : groups) {
            for (CompositionRule.Condition c : group.conditions()) {
                if (compositionRepository.find(c.tagCode()).isPresent()
                        && reaches(c.tagCode(), selfCode, new LinkedHashSet<>())) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "组合标签存在循环引用: " + c.tagCode() + " → " + selfCode);
                }
            }
        }
    }

    private boolean reaches(String from, String target, Set<String> visiting) {
        if (from.equals(target)) {
            return true;
        }
        if (!visiting.add(from)) {
            return false;
        }
        TagComposition comp = compositionRepository.find(from).orElse(null);
        if (comp != null) {
            CompositionRule rule = derivedTagResolver.parseRule(comp);
            if (rule != null) {
                for (String ref : derivedTagResolver.referencedCodes(rule)) {
                    if (reaches(ref, target, visiting)) {
                        return true;
                    }
                }
            }
        }
        visiting.remove(from);
        return false;
    }

    private int depthOf(String tagCode, Set<String> visiting) {
        if (!visiting.add(tagCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合标签存在循环引用: " + tagCode);
        }
        TagComposition comp = compositionRepository.find(tagCode).orElse(null);
        int depth = 0;
        if (comp != null) {
            CompositionRule rule = derivedTagResolver.parseRule(comp);
            if (rule != null) {
                for (String ref : derivedTagResolver.referencedCodes(rule)) {
                    if (compositionRepository.find(ref).isPresent()) {
                        depth = Math.max(depth, 1 + depthOf(ref, visiting));
                    }
                }
            }
        }
        visiting.remove(tagCode);
        return depth;
    }

    private RuleMeta ruleMeta(CompositionRule rule) {
        Map<String, TagMeta> metas = metaMap(new ArrayList<>(derivedTagResolver.referencedCodes(rule)));
        String sensitivity = metas.values().stream()
                .map(TagMeta::sensitivity)
                .max(Comparator.comparingInt(s -> SENSITIVITY_RANK.getOrDefault(s, 1)))
                .orElse("L1");
        return new RuleMeta(sensitivity);
    }

    private String valueRange(CompositionRule rule, String outputType) {
        if (!"enum".equalsIgnoreCase(outputType)) {
            return null;
        }
        try {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            if (CompositionRule.Output.MODE_MAPPING.equalsIgnoreCase(rule.output().mode())) {
                if (rule.output().entries() != null) {
                    rule.output().entries().forEach(m -> values.add(m.to()));
                }
                if (rule.output().defaultValue() != null && !rule.output().defaultValue().isBlank()) {
                    values.add(rule.output().defaultValue());
                }
            } else if (rule.output().value() != null) {
                values.add(rule.output().value());
            }
            return objectMapper.writeValueAsString(Map.of("enum", new ArrayList<>(values)));
        } catch (Exception ignored) {
            // 值域仅用于展示，序列化失败不影响规则本身
            return null;
        }
    }

    /* ============================================================
     * 权限与基础校验
     * ============================================================ */
    private void ensureManage(TagComposition comp) {
        String role = RequestContext.getConsoleRole();
        if ("ADMIN".equals(role)) {
            return;
        }
        if (!"TENANT_ADMIN".equals(role)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "仅平台管理员或租户管理员可维护组合标签");
        }
        String own = RequestContext.getConsoleTenantId();
        if (!comp.ownedByTenant(own)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "只能维护归属本租户的组合标签");
        }
    }

    private void ensureVisible(TagComposition comp) {
        String role = RequestContext.getConsoleRole();
        if ("ADMIN".equals(role)) {
            return;
        }
        if (!comp.visibleTo(RequestContext.getConsoleTenantId())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
    }

    private String canonicalTenant(String requested) {
        String role = RequestContext.getConsoleRole();
        if ("TENANT_ADMIN".equals(role)) {
            String own = RequestContext.getConsoleTenantId();
            if (own == null || own.isBlank()) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
            }
            if (requested != null && !requested.isBlank() && !normalizeTenants(requested).equals(own)) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "租户管理员只能为本租户创建组合标签");
            }
            return own;
        }
        String normalized = normalizeTenants(requested);
        if (normalized == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_id 不能为空");
        }
        return normalized;
    }

    private String normalizeTenants(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String id = token.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!"PUBLIC".equalsIgnoreCase(id) && !TENANT_PATTERN.matcher(id).matches()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "租户编码不合法: " + id);
            }
            values.add("PUBLIC".equalsIgnoreCase(id) ? "PUBLIC" : id);
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private List<String> splitTenants(String tenantId) {
        List<String> tenants = new ArrayList<>();
        for (String token : tenantId.split(",")) {
            String id = token.trim();
            if (!id.isEmpty()) {
                tenants.add(id);
            }
        }
        if (tenants.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_id 不能为空");
        }
        return tenants;
    }

    private String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_id 不能为空");
        }
        return tenantId.trim();
    }

    private String requireObjectType(String objectType) {
        String type = normalize(objectType);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "object_type 不能为空");
        }
        ObjectTypeMeta meta = objectTypeRepository.findByType(type)
                .filter(ObjectTypeMeta::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                        "对象类型未注册或已停用: " + type));
        return meta.objectType();
    }

    private String requireOutputType(String outputType) {
        String type = normalize(outputType);
        if (type == null || !OUTPUT_TYPES.contains(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "output_type 仅支持 boolean / enum / number / string");
        }
        return type;
    }

    private TagComposition requireComposition(String tagCode) {
        return compositionRepository.find(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "组合标签不存在: " + tagCode));
    }

    private TagMeta requireDerivedMeta(String tagCode) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        if (!"derived".equalsIgnoreCase(meta.tagType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该标签不是组合标签: " + tagCode);
        }
        return meta;
    }

    private Map<String, String> latestValues(String tenantId, String objectType, String objectId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> values = new LinkedHashMap<>();
        for (TagValue v : tagValueStore.listLatestByObject(tenantId, objectType, objectId)) {
            if (TagValue.STATUS_REVOKED.equals(v.status())
                    || TagValue.STATUS_PENDING_AUDIT.equals(v.status())
                    || (v.expireTime() != null && !v.expireTime().isAfter(now))) {
                continue;
            }
            values.put(v.tagCode(), v.tagValue());
        }
        return values;
    }

    private Map<String, TagMeta> metaMap(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        return tagMetaRepository.findByCodes(codes).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, m -> m, (a, b) -> a));
    }

    private CompositionModels.CompositionView view(TagComposition comp, TagMeta meta) {
        CompositionRule rule;
        try {
            rule = derivedTagResolver.parseRule(comp);
        } catch (Exception ignored) {
            rule = null;
        }
        return new CompositionModels.CompositionView(comp.tagCode(),
                meta == null ? comp.tagCode() : meta.tagName(),
                meta == null ? null : meta.categoryCode(),
                comp.objectType(), comp.tenantId(), comp.status(), comp.ruleVersion(),
                meta == null ? 0 : meta.version(), comp.outputType(),
                meta == null ? null : meta.sensitivity(),
                rule == null || rule.output() == null ? null : rule.output().mode(),
                rule, comp.description(), comp.updatedAt(), comp.updatedBy());
    }

    private String normalizeStatus(String status) {
        String s = normalize(status);
        if (s == null) {
            return null;
        }
        if (!TagComposition.STATUS_DRAFT.equals(s) && !TagComposition.STATUS_PUBLISHED.equals(s)
                && !TagComposition.STATUS_DISABLED.equals(s)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 draft / published / disabled");
        }
        return s;
    }

    private String normalizeLogic(String logic) {
        String value = logic == null ? CompositionRule.LOGIC_AND : logic.trim().toUpperCase(Locale.ROOT);
        if (!CompositionRule.LOGIC_AND.equals(value) && !CompositionRule.LOGIC_OR.equals(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "logic 仅支持 AND / OR");
        }
        return value;
    }

    private String normalizeOp(String op) {
        String value = op == null ? "" : op.trim().toLowerCase(Locale.ROOT);
        if (!CompositionEvaluator.OPS.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的运算符: " + op);
        }
        return value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "规则序列化失败");
        }
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private String actor() {
        String name = RequestContext.getConsoleDisplayName();
        return name == null || name.isBlank() ? RequestContext.getConsoleUsername() : name;
    }

    private String requireText(String value, String message, int maxLength) {
        String text = normalize(value);
        if (text == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
        if (text.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_INVALID, message + "（长度不能超过 " + maxLength + "）");
        }
        return text;
    }

    private boolean isNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(raw.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RuleMeta(String sensitivity) {
    }
}
