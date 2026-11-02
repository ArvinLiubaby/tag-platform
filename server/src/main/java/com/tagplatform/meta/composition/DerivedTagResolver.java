package com.tagplatform.meta.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 派生标签查询期求值：按已发布组合的依赖顺序在内存中计算，不落 tag_value_latest。
 * 单对象场景，输入标签 ≤20、嵌套深度 ≤3，纯内存计算。
 */
@Service
public class DerivedTagResolver {

    private static final int MAX_ROUNDS = 4;

    private final TagCompositionRepository compositionRepository;
    private final TagMetaRepository tagMetaRepository;
    private final ObjectMapper objectMapper;

    public DerivedTagResolver(TagCompositionRepository compositionRepository,
                              TagMetaRepository tagMetaRepository, ObjectMapper objectMapper) {
        this.compositionRepository = compositionRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.objectMapper = objectMapper;
    }

    public List<TagComposition> publishedFor(String tenantId, String objectType) {
        return compositionRepository.listPublishedByObjectType(objectType).stream()
                .filter(c -> c.visibleTo(tenantId))
                .toList();
    }

    /** 返回 tagCode → 计算结果（未命中/未知不入表，保持"缺失"语义） */
    public Map<String, String> resolve(String tenantId, String objectType, Map<String, String> baseValues) {
        Map<String, CompositionEvaluator.Outcome> outcomes = resolveOutcomes(tenantId, objectType, baseValues);
        Map<String, String> values = new LinkedHashMap<>();
        outcomes.forEach((code, outcome) -> {
            if (outcome.value() != null) {
                values.put(code, outcome.value());
            }
        });
        return values;
    }

    public Map<String, CompositionEvaluator.Outcome> resolveOutcomes(String tenantId, String objectType,
                                                                     Map<String, String> baseValues) {
        List<TagComposition> comps = publishedFor(tenantId, objectType);
        Map<String, TagComposition> byCode = comps.stream()
                .collect(Collectors.toMap(TagComposition::tagCode, c -> c, (a, b) -> a));
        Map<String, String> values = new HashMap<>(baseValues);
        Map<String, CompositionEvaluator.Outcome> outcomes = new LinkedHashMap<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean progress = false;
            for (TagComposition comp : comps) {
                if (outcomes.containsKey(comp.tagCode()) || values.containsKey(comp.tagCode())) {
                    continue;
                }
                CompositionRule rule = parseRule(comp);
                if (rule == null) {
                    continue;
                }
                Set<String> deps = referencedCodes(rule);
                boolean ready = true;
                for (String dep : deps) {
                    if (byCode.containsKey(dep) && !outcomes.containsKey(dep)) {
                        if (values.containsKey(dep)) {
                            continue;
                        }
                        ready = false;
                        break;
                    }
                }
                if (!ready) {
                    continue;
                }
                Map<String, String> merged = new HashMap<>(values);
                for (String dep : deps) {
                    if (values.containsKey(dep)) {
                        merged.put(dep, values.get(dep));
                    }
                }
                CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule, merged, names(merged.keySet()));
                outcomes.put(comp.tagCode(), outcome);
                if (outcome.value() != null) {
                    values.put(comp.tagCode(), outcome.value());
                }
                progress = true;
            }
            if (!progress) {
                break;
            }
        }
        return outcomes;
    }

    public CompositionRule parseRule(TagComposition comp) {
        try {
            CompositionRule base = objectMapper.readValue(comp.conditionsJson(), CompositionRule.class);
            CompositionRule.Output output = objectMapper.readValue(comp.outputJson(), CompositionRule.Output.class);
            return new CompositionRule(comp.logic(), base.groups(), output, comp.missingPolicy());
        } catch (Exception e) {
            return null;
        }
    }

    public Set<String> referencedCodes(CompositionRule rule) {
        Set<String> codes = new java.util.LinkedHashSet<>();
        if (rule == null || rule.groups() == null) {
            return codes;
        }
        for (CompositionRule.ConditionGroup group : rule.groups()) {
            if (group == null || group.conditions() == null) {
                continue;
            }
            for (CompositionRule.Condition c : group.conditions()) {
                if (c != null && c.tagCode() != null && !c.tagCode().isBlank()) {
                    codes.add(c.tagCode());
                }
            }
        }
        return codes;
    }

    public Map<String, String> names(Set<String> codes) {
        List<TagMeta> metas = tagMetaRepository.findByCodes(new ArrayList<>(codes));
        return metas.stream().collect(Collectors.toMap(TagMeta::tagCode, TagMeta::tagName, (a, b) -> a));
    }
}
