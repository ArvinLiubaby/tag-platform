package com.tagplatform.query.service;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.composition.DerivedTagResolver;
import com.tagplatform.meta.composition.TagComposition;
import com.tagplatform.meta.composition.TagCompositionRepository;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.query.web.dto.CombinedProfileView;
import com.tagplatform.query.web.dto.HistoryPage;
import com.tagplatform.query.web.dto.OperatorRecordsView;
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
    private final TagValueStore tagValueStore;
    private final TagCompositionRepository compositionRepository;
    private final DerivedTagResolver derivedTagResolver;

    public ProfileQueryService(ObjectTypeRepository objectTypeRepository, TagMetaRepository tagMetaRepository,
                               TagValueStore tagValueStore, TagCompositionRepository compositionRepository,
                               DerivedTagResolver derivedTagResolver) {
        this.objectTypeRepository = objectTypeRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.tagValueStore = tagValueStore;
        this.compositionRepository = compositionRepository;
        this.derivedTagResolver = derivedTagResolver;
    }

    /** 对象画像：排除 revoked 与已过期；pending_audit 由 includePending 控制可见性 */
    public ProfileView profile(String tenantId, String objectType, String objectId, boolean includePending) {
        CombinedProfileView.ProfileSection section = section(tenantId, objectType, objectId, includePending, false, null);
        recordTagQueries(section.tags());
        return new ProfileView(tenantId, objectType, objectId, section.tags());
    }

    /**
     * 司机+车辆组合标签内容查询：一次返回两对象的标签值（driver_id / vehicle_plate 至少传一个）。
     * includeEmpty=true 时，可见在线标签中适用于该对象的标签全量返回，无值项以 status=none 占位；
     * tagCodes 非空时仅返回这些标签（可用于精确取「清单-1015」的 32 个特征值）。
     */
    public CombinedProfileView combinedProfile(String tenantId, String driverId, String vehiclePlate,
                                               boolean includePending, boolean includeEmpty, Set<String> tagCodes) {
        boolean hasDriver = driverId != null && !driverId.isBlank();
        boolean hasVehicle = vehiclePlate != null && !vehiclePlate.isBlank();
        if (!hasDriver && !hasVehicle) {
            throw new BizException(ErrorCode.PARAM_MISSING, "driver_id 与 vehicle_plate 至少传入一个");
        }
        CombinedProfileView.ProfileSection driver = hasDriver
                ? section(tenantId, "driver", driverId, includePending, includeEmpty, tagCodes) : null;
        CombinedProfileView.ProfileSection vehicle = hasVehicle
                ? section(tenantId, "vehicle", vehiclePlate, includePending, includeEmpty, tagCodes) : null;
        if (driver != null) {
            recordTagQueries(driver.tags());
        }
        if (vehicle != null) {
            recordTagQueries(vehicle.tags());
        }
        int total = (driver == null ? 0 : driver.tags().size()) + (vehicle == null ? 0 : vehicle.tags().size());
        return new CombinedProfileView(tenantId, driver, vehicle, total);
    }

    /** 单对象标签段：includeEmpty 时用可见在线标签（按对象适配与 tagCodes 过滤）补全无值占位 */
    private CombinedProfileView.ProfileSection section(String tenantId, String objectType, String objectId,
                                                       boolean includePending, boolean includeEmpty,
                                                       Set<String> tagCodes) {
        ObjectTypeMeta type = objectTypeRepository.findByType(objectType)
                .filter(ObjectTypeMeta::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                        "对象类型未注册或已停用: " + objectType));
        if (!type.idMatches(objectId)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "object_id 不符合 " + objectType + " 的格式要求: " + type.idPattern());
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

        // 派生标签：按已发布组合在查询期计算，不落 latest；tagCodes 精确取数时同样支持
        List<TagComposition> compositions = compositionRepository.listPublishedByObjectType(objectType).stream()
                .filter(c -> c.visibleTo(tenantId))
                .filter(c -> allTags || tagCodes.contains(c.tagCode()))
                .toList();
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
                    .map(v -> ProfileView.TagItem.from(v, metaMap.get(v.tagCode())))
                    .toList());
            appendDerived(items, compositions, derivedMetas, derivedValues, false);
            return new CombinedProfileView.ProfileSection(objectType, objectId, items);
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
                            ? ProfileView.TagItem.none(meta)
                            : ProfileView.TagItem.from(value, meta));
                });
        appendDerived(items, compositions, derivedMetas, derivedValues, true);
        remaining.values().forEach(v -> items.add(ProfileView.TagItem.from(v, metaMap.get(v.tagCode()))));
        return new CombinedProfileView.ProfileSection(objectType, objectId, items);
    }

    private void appendDerived(List<ProfileView.TagItem> items, List<TagComposition> compositions,
                               Map<String, TagMeta> metas, Map<String, String> values, boolean includeEmpty) {
        for (TagComposition comp : compositions) {
            TagMeta meta = metas.get(comp.tagCode());
            if (meta == null) {
                continue;
            }
            String value = values.get(comp.tagCode());
            if (value == null && !includeEmpty) {
                continue;
            }
            items.add(ProfileView.TagItem.fromDerived(meta, value));
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
        return new OperatorRecordsView(operatorId, params.page(), params.size(), total, records);
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
        return new HistoryPage(params.page(), params.size(), total, records);
    }

    private Map<String, TagMeta> tagMetaMap(Set<String> tagCodes) {
        return tagMetaRepository.findByCodes(tagCodes).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, meta -> meta));
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
}
