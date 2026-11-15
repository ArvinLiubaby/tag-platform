package com.tagplatform.query.service;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.composition.DerivedTagResolver;
import com.tagplatform.meta.composition.TagComposition;
import com.tagplatform.meta.composition.TagCompositionRepository;
import com.tagplatform.meta.ext.TagExtRepository;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.query.repo.ObjectOptionRepository;
import com.tagplatform.query.web.dto.HistoryPage;
import com.tagplatform.query.web.dto.OperatorRecordsView;
import com.tagplatform.query.web.dto.ProfileBatchView;
import com.tagplatform.query.web.dto.ProfileView;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 画像与流水查询服务（query 模块）：
 *   画像点查直接读 tag_value_latest；打标记录读 tag_value_history。
 *   MVP 不引入画像缓存（V2 引入 Redis 缓存）。
 */
@Service
public class ProfileQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectTypeRepository objectTypeRepository;
    private final TagMetaRepository tagMetaRepository;
    private final TagExtRepository tagExtRepository;
    private final TagValueStore tagValueStore;
    private final TagCompositionRepository compositionRepository;
    private final DerivedTagResolver derivedTagResolver;
    private final ObjectOptionRepository objectOptionRepository;

    public ProfileQueryService(ObjectTypeRepository objectTypeRepository, TagMetaRepository tagMetaRepository,
                               TagExtRepository tagExtRepository, TagValueStore tagValueStore,
                               TagCompositionRepository compositionRepository, DerivedTagResolver derivedTagResolver,
                               ObjectOptionRepository objectOptionRepository) {
        this.objectTypeRepository = objectTypeRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.tagExtRepository = tagExtRepository;
        this.tagValueStore = tagValueStore;
        this.compositionRepository = compositionRepository;
        this.derivedTagResolver = derivedTagResolver;
        this.objectOptionRepository = objectOptionRepository;
    }

    /** 对象画像：排除 revoked 与已过期；pending_audit 由 includePending 控制可见性 */
    public ProfileView profile(String tenantId, String objectType, String objectId, boolean includePending) {
        ProfileSection section = section(tenantId, objectType, objectId, includePending, false, null,
                null, null, loadDimIndex(tenantId));
        recordTagQueries(section.tags());
        String objectName = section.objectName() != null
                ? section.objectName()
                : objectOptionRepository.findObjectName(tenantId, objectType, objectId);
        return new ProfileView(tenantId, objectType, objectId, objectName, section.tags());
    }

    /** 批量画像的参数上限，与任务待打标清单保持一致 */
    private static final int MAX_PROFILE_TYPES = 20;
    private static final int MAX_PROFILE_OBJECTS = 200;

    /**
     * 批量对象画像（GET /api/v1/open/objects/profiles）。
     * object_type / object_id 均支持逗号分隔多值，按位置配对、不做笛卡尔积：
     *   ① 单类型多对象：object_type=driver & object_id=DRV85001,DRV85002
     *   ② 多类型混合：object_type=driver,vehicle & object_id=DRV85001,苏A88801
     * 每个对象的返回口径与单对象画像完全一致（只返回有值标签，排除 revoked / 已过期）。
     */
    public ProfileBatchView profiles(String tenantId, String objectType, String objectId, boolean includePending) {
        return profiles(tenantId, objectType, objectId, includePending, false, Set.of());
    }

    /**
     * 批量对象画像（完整口径）。
     * includeEmpty=true 时按「可见在线标签中适用于该对象的标签」补全 status=none 占位（原组合画像口径）；
     * tagCodes 非空时精确取指定标签，占位范围、组合标签与当前值都受同一份过滤约束。
     */
    public ProfileBatchView profiles(String tenantId, String objectType, String objectId,
                                     boolean includePending, boolean includeEmpty, Set<String> tagCodes) {
        Set<String> codes = tagCodes == null ? Set.of() : tagCodes;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_id 不能为空");
        }
        List<String> rawTypes = splitCsv(objectType);
        List<String> rawIds = splitCsv(objectId);
        if (rawTypes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING,
                    "object_type 不能为空，支持逗号分隔多值，如 driver,vehicle");
        }
        if (rawIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING,
                    "object_id 不能为空，支持逗号分隔多值；批量画像必须指定对象");
        }
        if (rawTypes.size() > MAX_PROFILE_TYPES) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_type 最多 " + MAX_PROFILE_TYPES + " 个");
        }
        if (rawIds.size() > MAX_PROFILE_OBJECTS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 最多 " + MAX_PROFILE_OBJECTS + " 个");
        }

        List<String[]> pairs = new ArrayList<>();
        if (rawTypes.size() == 1) {
            // 单类型 + 多对象
            for (String id : rawIds) {
                pairs.add(new String[]{rawTypes.get(0), id});
            }
        } else if (rawTypes.size() == rawIds.size()) {
            // 多类型按位置一一配对
            for (int i = 0; i < rawTypes.size(); i++) {
                pairs.add(new String[]{rawTypes.get(i), rawIds.get(i)});
            }
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "object_type 与 object_id 需按位置一一对应（个数相同）；只查一种类型时可只传 1 个 object_type 配多个 object_id");
        }

        // 去重（保持顺序）：重复的 (type,id) 只查一次
        Map<String, String[]> unique = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            unique.putIfAbsent(pair[0] + "\u0000" + pair[1], pair);
        }
        Map<String, ObjectTypeMeta> typeCache = new LinkedHashMap<>();
        Map<String, List<TagComposition>> compCache = new LinkedHashMap<>();
        ProfileView.DimIndex dimIndex = loadDimIndex(tenantId);
        // 索引名称兜底：一次 IN 查询取回本批全部对象的名称（避免逐条查库）
        Map<String, String> fallbackNames = objectOptionRepository.findObjectNames(tenantId,
                unique.values().stream().map(pair -> pair[1]).toList());
        List<ProfileView> records = unique.values().stream()
                .map(pair -> {
                    ProfileSection section = section(tenantId, pair[0], pair[1],
                            includePending, includeEmpty, codes, typeCache, compCache, dimIndex);
                    recordTagQueries(section.tags());
                    String objectName = section.objectName() != null
                            ? section.objectName()
                            : fallbackNames.get(pair[1]);
                    return new ProfileView(tenantId, pair[0], pair[1], objectName, section.tags());
                })
                .toList();
        return new ProfileBatchView(tenantId, records.size(), records);
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 维度索引（维度编码 → 中文名称 + 一级维度展示优先级）：每次画像请求加载一次，
     * 批量对象间共享，避免 200 个对象重复读维度字典与 DIM1 扩展属性。
     */
    private ProfileView.DimIndex loadDimIndex(String tenantId) {
        Map<String, String> categoryNames = new LinkedHashMap<>();
        tagMetaRepository.listCategories()
                .forEach(c -> categoryNames.put(c.categoryCode(), c.categoryName()));
        Map<String, Integer> dimPriorities = new LinkedHashMap<>();
        tagExtRepository.dimPriorities(tenantId)
                .forEach(d -> {
                    if (d.priority() != null) {
                        dimPriorities.put(d.dimCode(), d.priority());
                    }
                });
        return new ProfileView.DimIndex(categoryNames, dimPriorities);
    }

    /**
     * 批量画像传入 typeCache / compCache：同一对象类型的注册信息与已发布组合定义只查一次，
     * 避免 N 个对象重复读元数据；标签当前值仍按对象逐个查询（本身与对象绑定，无法合并）。
     * dimIndex 提供维度名称与优先级回填（每次请求构建一次）。
     */
    private ProfileSection section(String tenantId, String objectType, String objectId,
                                                       boolean includePending, boolean includeEmpty,
                                                       Set<String> tagCodes,
                                                       Map<String, ObjectTypeMeta> typeCache,
                                                       Map<String, List<TagComposition>> compCache,
                                                       ProfileView.DimIndex dimIndex) {
        ObjectTypeMeta type = typeCache == null
                ? requireObjectType(objectType)
                : typeCache.computeIfAbsent(objectType, this::requireObjectType);
        if (!type.idPresent(objectId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 不能为空");
        }

        boolean allTags = tagCodes == null || tagCodes.isEmpty();
        LocalDateTime now = LocalDateTime.now();
        List<TagValue> allValues = tagValueStore.listLatestByObject(tenantId, objectType, objectId).stream()
                .filter(v -> !TagValue.STATUS_REVOKED.equals(v.status()))
                .filter(v -> includePending || !TagValue.STATUS_PENDING_AUDIT.equals(v.status()))
                .filter(v -> v.expireTime() == null || v.expireTime().isAfter(now))
                .toList();
        List<TagValue> values = allValues.stream()
                .filter(v -> allTags || tagCodes.contains(v.tagCode()))
                .toList();
        // 对象名称：优先取 latest 行（外部同步程序冗余写入），空则回落到对象索引
        String latestObjectName = allValues.stream()
                .map(TagValue::objectName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(null);

        // 派生标签：按已发布组合在查询期计算，不落 latest；tagCodes 精确取数时同样支持
        List<TagComposition> compositions = compCache == null
                ? publishedCompositions(tenantId, objectType, allTags, tagCodes)
                : compCache.computeIfAbsent(objectType,
                        key -> publishedCompositions(tenantId, key, allTags, tagCodes));
        // 派生标签只使用"已生效"的当前值：待审核值即使对控制台可见，也不参与组合计算
        Map<String, String> baseValues = new LinkedHashMap<>();
        allValues.stream()
                .filter(v -> !TagValue.STATUS_PENDING_AUDIT.equals(v.status()))
                .forEach(v -> baseValues.put(v.tagCode(), v.tagValue()));
        Map<String, String> derivedValues = compositions.isEmpty()
                ? Map.of() : derivedTagResolver.resolve(tenantId, objectType, baseValues);
        Map<String, TagMeta> derivedMetas = compositions.isEmpty()
                ? Map.of() : tagMetaMap(compositions.stream().map(TagComposition::tagCode).collect(Collectors.toSet()));

        Map<String, TagMeta> metaMap = tagMetaMap(values.stream().map(TagValue::tagCode).collect(Collectors.toSet()));
        if (!includeEmpty) {
            List<ProfileView.TagItem> items = new ArrayList<>(values.stream()
                    .map(v -> ProfileView.TagItem.from(v, metaMap.get(v.tagCode()), dimIndex))
                    .toList());
            appendDerived(items, compositions, derivedMetas, derivedValues, false, dimIndex);
            return new ProfileSection(objectType, objectId, latestObjectName, items);
        }

        // 可见在线标签中适用于该对象的标签在前（无值 → 占位）；
        // 有值但不可见/不适配的标签（如已下线）追加在后，保证画像数据不丢
        Map<String, TagValue> remaining = values.stream()
                .collect(Collectors.toMap(TagValue::tagCode, v -> v, (a, b) -> a, LinkedHashMap::new));
        List<ProfileView.TagItem> items = new ArrayList<>();
        tagMetaRepository.listVisibleOnline(tenantId).stream()
                .filter(meta -> meta.supportsObjectType(objectType))
                .filter(meta -> !"derived".equalsIgnoreCase(meta.tagType()))
                .filter(meta -> allTags || tagCodes.contains(meta.tagCode()))
                .forEach(meta -> {
                    TagValue value = remaining.remove(meta.tagCode());
                    items.add(value == null
                            ? ProfileView.TagItem.none(meta, dimIndex)
                            : ProfileView.TagItem.from(value, meta, dimIndex));
                });
        appendDerived(items, compositions, derivedMetas, derivedValues, true, dimIndex);
        remaining.values().forEach(v -> items.add(ProfileView.TagItem.from(v, metaMap.get(v.tagCode()), dimIndex)));
        return new ProfileSection(objectType, objectId, latestObjectName, items);
    }

    private ObjectTypeMeta requireObjectType(String objectType) {
        return objectTypeRepository.findByType(objectType)
                .filter(ObjectTypeMeta::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                        "对象类型未注册或已停用: " + objectType));
    }

    private List<TagComposition> publishedCompositions(String tenantId, String objectType,
                                                       boolean allTags, Set<String> tagCodes) {
        return compositionRepository.listPublishedByObjectType(objectType).stream()
                .filter(c -> c.visibleTo(tenantId))
                .filter(c -> allTags || tagCodes.contains(c.tagCode()))
                .toList();
    }

    private void appendDerived(List<ProfileView.TagItem> items, List<TagComposition> compositions,
                               Map<String, TagMeta> metas, Map<String, String> values, boolean includeEmpty,
                               ProfileView.DimIndex dimIndex) {
        for (TagComposition comp : compositions) {
            TagMeta meta = metas.get(comp.tagCode());
            if (meta == null) {
                continue;
            }
            String value = values.get(comp.tagCode());
            if (value == null && !includeEmpty) {
                continue;
            }
            items.add(ProfileView.TagItem.fromDerived(meta, value, dimIndex));
        }
    }

    /** 操作员打标记录（开放 API） */
    public OperatorRecordsView operatorRecords(String tenantId, String operatorId, String objectType, String objectId,
                                               String tagCode, LocalDateTime from, LocalDateTime to,
                                               int page, int size) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "operator_id 不能为空");
        }
        PageParams params = normalize(page, size);
        TagValueStore.HistoryQuery query = new TagValueStore.HistoryQuery(tenantId, operatorId, objectType,
                objectId, tagCode, from, to, params.offset(), params.size());
        List<TagValue> records = tagValueStore.queryHistory(query);
        long total = tagValueStore.countHistory(query);
        return new OperatorRecordsView(operatorId, params.page(), params.size(), total, records, unitMap(records));
    }

    /** 打标流水（控制台，租户内全条件检索） */
    public HistoryPage history(String tenantId, String operatorId, String objectType, String objectId,
                               String tagCode, LocalDateTime from, LocalDateTime to, int page, int size) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_id 不能为空");
        }
        PageParams params = normalize(page, size);
        TagValueStore.HistoryQuery query = new TagValueStore.HistoryQuery(tenantId, operatorId, objectType,
                objectId, tagCode, from, to, params.offset(), params.size());
        List<TagValue> records = tagValueStore.queryHistory(query);
        long total = tagValueStore.countHistory(query);
        return new HistoryPage(params.page(), params.size(), total, records, unitMap(records));
    }

    private Map<String, TagMeta> tagMetaMap(Set<String> tagCodes) {
        return tagMetaRepository.findByCodes(tagCodes).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, meta -> meta));
    }

    /**
     * 流水类响应在页级下发「标签码 → 计量单位」映射：单位属于标签定义，不为每条流水冗余一份。
     * 只列出本页出现且配置了单位的标签；其余标签查不到即视为无单位。
     */
    private Map<String, String> unitMap(List<TagValue> records) {
        Set<String> codes = records.stream().map(TagValue::tagCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> units = new LinkedHashMap<>();
        tagMetaRepository.findByCodes(codes).forEach(meta -> {
            if (meta.unit() != null && !meta.unit().isBlank()) {
                units.put(meta.tagCode(), meta.unit());
            }
        });
        return units;
    }

    /** 只统计有值标签（status=none 的占位不计入查询次数） */
    private void recordTagQueries(List<ProfileView.TagItem> tags) {
        if (tags == null) {
            return;
        }
        for (ProfileView.TagItem tag : tags) {
            if (tag != null && tag.tagCode() != null && tag.tagValue() != null
                    && !ProfileView.STATUS_NONE.equals(tag.status())) {
                RequestContext.recordTagQuery(tag.tagCode());
            }
        }
    }

    private PageParams normalize(int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new PageParams(normalizedPage, normalizedSize, (normalizedPage - 1) * normalizedSize);
    }

    private record PageParams(int page, int size, int offset) {
    }

    /** 单个对象的标签段（服务内部中间结果；对外统一由 ProfileView / ProfileBatchView 承载） */
    private record ProfileSection(String objectType, String objectId, String objectName,
                                  List<ProfileView.TagItem> tags) {
    }
}
