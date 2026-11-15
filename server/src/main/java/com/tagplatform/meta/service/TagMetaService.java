package com.tagplatform.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.model.TagVersion;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.repo.TagVersionRepository;
import com.tagplatform.meta.repo.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 标签字典服务：开放查询 + 控制台注册与定义审核（register → reviewing → approved/online）。
 */
@Service
public class TagMetaService {

    private static final Pattern TAG_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern OBJECT_TYPE_FILTER_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Set<String> VALUE_TYPES = Set.of("boolean", "number", "enum", "string", "date");
    private static final Set<String> TAG_TYPES = Set.of("fact", "rule", "algo", "derived");
    private static final Set<String> SENSITIVITIES = Set.of("L1", "L2", "L3");
    /** 打标来源类型：客服手工 / 数据统计 / 手工+统计混合 / 算法模型 */
    private static final Set<String> SOURCE_MODES = Set.of("manual", "statistics", "hybrid", "algo", "derived");

    private final TagMetaRepository tagMetaRepository;
    private final TagVersionRepository tagVersionRepository;
    private final TagAuditRepository tagAuditRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    public TagMetaService(TagMetaRepository tagMetaRepository, TagVersionRepository tagVersionRepository,
                          TagAuditRepository tagAuditRepository, ObjectTypeRepository objectTypeRepository,
                          TenantRepository tenantRepository, ObjectMapper objectMapper) {
        this.tagMetaRepository = tagMetaRepository;
        this.tagVersionRepository = tagVersionRepository;
        this.tagAuditRepository = tagAuditRepository;
        this.objectTypeRepository = objectTypeRepository;
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
    }

    public List<TagMeta> listVisibleOnline(String tenantId) {
        return tagMetaRepository.listVisibleOnline(tenantId);
    }

    public List<TagMeta> list(String status, String keyword, String tenantId, String sensitivity,
                             String objectType, boolean includeArchived) {
        String filterObjectType = normalize(objectType);
        if (filterObjectType != null && !OBJECT_TYPE_FILTER_PATTERN.matcher(filterObjectType).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_type 格式不合法");
        }
        return tagMetaRepository.list(status, keyword, tenantId, sensitivity, filterObjectType, includeArchived);
    }

    public List<TagMetaRepository.CategoryInfo> listCategories() {
        return tagMetaRepository.listCategories();
    }

    // ------------------------------------------------------ 维度管理（tag_category）

    private static final Pattern CATEGORY_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    /** 维度新增请求：parentCode 为空 = 一级维度；sortNo 为空 = 同级最大排序号 +10 */
    public record CategoryCreateRequest(String categoryCode, String categoryName, String parentCode, Integer sortNo) {
    }

    /**
     * 维度编辑请求：categoryName / sortNo 为 null 表示不修改；parentChanged=true 时才处理父级变更
     * （此时 parentCode 为目标父级，传 null = 升级为一级维度），避免「改名/排序」请求误触发父级升级。
     */
    public record CategoryUpdateRequest(String categoryName, String parentCode, Boolean parentChanged,
                                        Integer sortNo) {

        /** 兼容简写：不调整父级，仅改名/排序 */
        public CategoryUpdateRequest(String categoryName, Integer sortNo) {
            this(categoryName, null, false, sortNo);
        }
    }

    /** 新增维度：二级维度必须挂在一维维度下 */
    @Transactional
    public TagMetaRepository.CategoryInfo createCategory(CategoryCreateRequest request) {
        String code = normalize(request.categoryCode());
        String name = normalize(request.categoryName());
        String parent = normalize(request.parentCode());
        if (code == null || !CATEGORY_CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "category_code 需为小写字母开头、2~64 位小写字母/数字/下划线");
        }
        if (name == null || name.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, "category_name 必填且 ≤128 字符");
        }
        if (tagMetaRepository.findCategory(code).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "category_code 已存在: " + code);
        }
        int sortNo = request.sortNo() != null ? request.sortNo()
                : tagMetaRepository.maxSortNoAmongSiblings(parent) + 10;
        if (parent != null) {
            requireTopCategory(parent, "父级维度");
        }
        tagMetaRepository.insertCategory(code, name, parent, sortNo);
        return tagMetaRepository.findCategory(code).orElseThrow();
    }

    /**
     * 编辑维度：改名/排序任意；parentCode 变更时校验——有下级维度的节点不可改父级（避免出现三级）、
     * 一级维度不可降级为二级、目标父级必须是一级维度。
     */
    @Transactional
    public TagMetaRepository.CategoryInfo updateCategory(String code, CategoryUpdateRequest request) {
        TagMetaRepository.CategoryInfo current = requireCategory(code);
        String name = normalize(request.categoryName());
        if (name != null && name.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, "category_name ≤128 字符");
        }
        if (name != null && !name.equals(current.categoryName())) {
            tagMetaRepository.updateCategoryName(code, name);
        }
        if (request.sortNo() != null) {
            tagMetaRepository.updateCategorySortNo(code, request.sortNo());
        }
        String curParent = normalize(current.parentCode());
        if (Boolean.TRUE.equals(request.parentChanged())) {
            String newParent = normalize(request.parentCode());
            if (java.util.Objects.equals(curParent, newParent)) {
                return tagMetaRepository.findCategory(code).orElseThrow();
            }
            if (tagMetaRepository.countSubCategories(code) > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "维度「" + current.categoryName() + "」存在下级维度，不能调整父级");
            }
            if (curParent == null && newParent != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "一级维度不能调整为二级维度");
            }
            if (newParent != null) {
                requireTopCategory(newParent, "目标父级维度");
            }
            tagMetaRepository.updateCategoryParent(code, newParent);
        }
        return tagMetaRepository.findCategory(code).orElseThrow();
    }

    /** 删除维度：存在下级维度或被标签引用（category/dim1/dim2 任一）时禁止 */
    @Transactional
    public void deleteCategory(String code) {
        TagMetaRepository.CategoryInfo current = requireCategory(code);
        long subs = tagMetaRepository.countSubCategories(code);
        if (subs > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "维度「" + current.categoryName() + "」存在 " + subs + " 个下级维度，请先删除或移走下级维度");
        }
        long refs = tagMetaRepository.countTagRefs(code);
        if (refs > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "维度「" + current.categoryName() + "」被 " + refs + " 个标签引用，请先调整这些标签的维度");
        }
        tagMetaRepository.deleteCategory(code);
    }

    // ------------------------------------------------------ 批量保存（控制台「保存变更」）

    /** 批量更新项：categoryCode 定位；name / sortNo 为 null 表示不修改；parentChanged=true 时才处理父级变更 */
    public record CategoryBatchUpdate(String categoryCode, String categoryName, Integer sortNo,
                                      String parentCode, Boolean parentChanged) {
    }

    /** 批量保存请求：删除 → 新增（一级优先）→ 更新，单事务执行，任一失败整体回滚 */
    public record CategoryBatchRequest(List<CategoryCreateRequest> creates,
                                       List<CategoryBatchUpdate> updates,
                                       List<String> deletes) {
    }

    /**
     * 批量保存维度变更：控制台维度管理「保存变更」的落库入口。
     * 顺序：先删（二级先于一级，避免「存在下级维度」误拒）→ 再新增（一级先于二级，支持同批新增父子）→ 最后更新。
     */
    @Transactional
    public void applyCategoryChanges(CategoryBatchRequest request) {
        List<String> deletes = request.deletes() == null ? List.of()
                : request.deletes().stream().filter(Objects::nonNull)
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        List<String> subDeletes = new ArrayList<>();
        List<String> topDeletes = new ArrayList<>();
        for (String code : deletes) {
            TagMetaRepository.CategoryInfo info = tagMetaRepository.findCategory(code)
                    .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID,
                            "待删除的维度不存在，请刷新后重试: " + code));
            if (normalize(info.parentCode()) == null) {
                topDeletes.add(code);
            } else {
                subDeletes.add(code);
            }
        }
        subDeletes.forEach(this::deleteCategory);
        topDeletes.forEach(this::deleteCategory);

        List<CategoryCreateRequest> creates = request.creates() == null ? List.of() : request.creates();
        creates.stream().filter(c -> normalize(c.parentCode()) == null).forEach(this::createCategory);
        creates.stream().filter(c -> normalize(c.parentCode()) != null).forEach(this::createCategory);

        if (request.updates() != null) {
            for (CategoryBatchUpdate update : request.updates()) {
                String code = normalize(update.categoryCode());
                if (code == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "待更新的维度编码不能为空");
                }
                updateCategory(code, new CategoryUpdateRequest(update.categoryName(),
                        update.parentCode(), update.parentChanged(), update.sortNo()));
            }
        }
    }

    // ------------------------------------------------------ 批量保存结束

    private TagMetaRepository.CategoryInfo requireCategory(String code) {
        return tagMetaRepository.findCategory(normalize(code))
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "维度不存在: " + code));
    }

    /** 校验维度存在且必须是一级维度（parent_code 为空） */
    private TagMetaRepository.CategoryInfo requireTopCategory(String code, String label) {
        TagMetaRepository.CategoryInfo info = requireCategory(code);
        if (normalize(info.parentCode()) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + "「" + info.categoryName() + "」不是一级维度");
        }
        return info;
    }

    // ------------------------------------------------------ 维度管理结束

    public List<TagAuditRepository.TagAuditInfo> listAudits(String status) {
        return tagAuditRepository.list(status);
    }

    /** 注册标签定义：落 tag_meta(reviewing) + tag_audit(reviewing)，同事务 */
    @Transactional
    public TagMeta register(RegisterRequest req, String submitBy) {
        boolean multiple = Boolean.TRUE.equals(req.valueMultiple());
        String actorRole = RequestContext.getConsoleRole();
        String canonicalTenant;
        if ("TENANT_ADMIN".equals(actorRole)) {
            String ownTenant = requireActorTenant();
            if (req.tenantId() != null && !normalizeTenants(req.tenantId()).equals(ownTenant)) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN,
                        "租户管理员只能为本租户注册标签，不能指定其他适用租户");
            }
            canonicalTenant = ownTenant;
        } else {
            canonicalTenant = normalizeTenants(req.tenantId());
        }
        if ("derived".equalsIgnoreCase(req.tagType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "派生标签请通过「标签组合」创建，不能直接注册");
        }
        String canonicalRange = validateDefinition(req.tagCode(), req.tagName(), req.valueType(),
                req.tagType(), req.sensitivity(), canonicalTenant, req.valueTtlDays(), req.sourceMode(),
                req.objectType(), req.valueRange(), multiple);
        if (tagMetaRepository.findByCode(req.tagCode()).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签编码已存在: " + req.tagCode());
        }
        LocalDateTime now = LocalDateTime.now();
        // 未显式指定一级维度时，按二级维度在类目树中的父节点推导
        String dim2Code = req.dim2Code() == null ? req.categoryCode() : req.dim2Code();
        String dim1Code = req.dim1Code();
        if (dim1Code == null && dim2Code != null) {
            dim1Code = tagMetaRepository.listCategories().stream()
                    .filter(c -> dim2Code.equals(c.categoryCode()))
                    .map(TagMetaRepository.CategoryInfo::parentCode)
                    .filter(p -> p != null && !p.isBlank())
                    .findFirst().orElse(null);
        }
        TagMeta meta = new TagMeta(null, req.tagCode(), req.tagName(), req.categoryCode(),
                dim1Code, dim2Code, req.priority(),
                req.valueType(),
                normalizeUnit(req.unit()),
                canonicalRange, multiple, req.tagType(), req.needValueAudit(), req.valueTtlDays(),
                canonicalTenant, req.sensitivity(), "reviewing", 1, normalizeBinding(req.objectType()),
                normalize(req.sourceMode()), req.writable() == null || req.writable(), now, now);
        tagMetaRepository.insert(meta);
        tagAuditRepository.insert(req.tagCode(), "register", submitBy, "reviewing");
        tagVersionRepository.insert(meta, "create", submitBy, "注册标签定义");
        return meta;
    }

    /**
     * 修改标签定义：立即生效，tag_code / tenant_id 不可变。
     * 使用 version 乐观锁防止并发覆盖；审核中的标签不允许修改。
     */
    @Transactional
    public TagMeta update(String tagCode, UpdateRequest req) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        if ("derived".equalsIgnoreCase(meta.tagType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合标签的定义与规则请在「标签组合」页维护");
        }
        ensureNotDeleted(meta);
        if ("archived".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已归档，请先恢复后再修改");
        }
        if ("reviewing".equals(meta.status()) || tagAuditRepository.countPendingByTag(tagCode) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签定义正在审核中，暂不能修改");
        }
        if (req.version() == null || !req.version().equals(meta.version())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已被其他人修改，请刷新后重试");
        }

        String tagName = normalize(req.tagName());
        String valueType = normalize(req.valueType());
        // 单位：不传保留原值；传空串表示清空
        String unit = req.unit() == null ? meta.unit() : normalizeUnit(req.unit());
        String tagType = normalize(req.tagType());
        String sensitivity = normalize(req.sensitivity());
        String sourceMode = normalize(req.sourceMode());
        String objectType = normalizeBinding(req.objectType());
        int valueTtlDays = req.valueTtlDays() == null ? meta.valueTtlDays() : req.valueTtlDays();
        boolean needValueAudit = req.needValueAudit() == null ? meta.needValueAudit() : req.needValueAudit();
        boolean writable = req.writable() == null ? meta.writable() : req.writable();
        boolean valueMultiple = req.valueMultiple() == null ? meta.valueMultiple() : req.valueMultiple();
        String actorRole = RequestContext.getConsoleRole();
        String tenantId;
        if ("TENANT_ADMIN".equals(actorRole)) {
            if (req.tenantId() != null && !normalizeTenants(req.tenantId()).equals(meta.tenantId())) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "租户管理员不能修改标签适用租户");
            }
            tenantId = meta.tenantId();
        } else {
            tenantId = req.tenantId() == null ? meta.tenantId() : normalizeTenants(req.tenantId());
        }

        String canonicalRange = validateDefinition(meta.tagCode(), tagName, valueType, tagType, sensitivity,
                tenantId, valueTtlDays, sourceMode, objectType, req.valueRange(), valueMultiple);

        // 维度：显式传值则覆盖；只改 categoryCode 时二级维度跟随；均未传则保留原值
        String categoryCode = normalize(req.categoryCode());
        String dim1Code = req.dim1Code() == null ? meta.dim1Code() : normalize(req.dim1Code());
        String dim2Code;
        if (req.dim2Code() != null) {
            dim2Code = normalize(req.dim2Code());
        } else if (req.categoryCode() != null) {
            dim2Code = categoryCode;
        } else {
            dim2Code = meta.dim2Code();
        }
        // 优先级：null 保留原值，-1 表示清空
        Integer priority = meta.priority();
        if (req.priority() != null) {
            priority = req.priority() < 0 ? null : req.priority();
        }

        int updated = tagMetaRepository.updateDefinition(meta.tagCode(), tagName,
                categoryCode, dim1Code, dim2Code, priority, tenantId, valueType, unit, canonicalRange,
                valueMultiple, tagType, needValueAudit, valueTtlDays, sensitivity, objectType, sourceMode,
                writable, meta.version());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已被其他人修改，请刷新后重试");
        }
        TagMeta updatedMeta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        tagVersionRepository.insert(updatedMeta, "update", currentOperator("system"),
                changeSummary("修改定义", meta, updatedMeta));
        return updatedMeta;
    }

    /** 定义审核决策：通过 → 标签上线；驳回 → 标签下线 */
    @Transactional
    public void decideAudit(long auditId, boolean approved, String auditBy, String auditRemark) {
        TagAuditRepository.TagAuditInfo audit = tagAuditRepository.findById(auditId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "审核单不存在: " + auditId));
        if (approved && (auditRemark == null || auditRemark.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核通过必须填写审核意见");
        }
        String status = approved ? "approved" : "rejected";
        int updated = tagAuditRepository.decide(auditId, status, auditBy, auditRemark);
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核单已处理，请刷新后重试");
        }
        tagMetaRepository.updateStatus(audit.tagCode(), approved ? "online" : "offline");
    }

    /** 上线/下线（已审核标签的运维操作） */
    @Transactional
    public void changeStatus(String tagCode, String status) {
        if (!"online".equals(status) && !"offline".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 online/offline");
        }
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        ensureNotDeleted(meta);
        if ("archived".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已归档，请先恢复后再操作");
        }
        if ("online".equals(status) && "reviewing".equals(meta.status())) {
            throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签定义尚在审核中，不能上线");
        }
        tagMetaRepository.updateStatus(tagCode, status);
    }

    /** 归档（软删除）：仅已下线且无待审核定义变更的标签，历史值与审计记录保留 */
    @Transactional
    public void archive(String tagCode) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        if (!"offline".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "仅已下线标签可以归档，当前状态: " + meta.status());
        }
        if (tagAuditRepository.countPendingByTag(tagCode) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签存在待审核的定义变更，暂不能归档");
        }
        if (tagMetaRepository.updateStatusIfCurrent(tagCode, "offline", "archived") == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签状态已变化，请刷新后重试");
        }
    }

    /** 恢复归档标签：恢复为已下线状态，需重新手动上线 */
    @Transactional
    public void restore(String tagCode) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        if (!"archived".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "仅已归档标签可以恢复，当前状态: " + meta.status());
        }
        if (tagMetaRepository.updateStatusIfCurrent(tagCode, "archived", "offline") == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签状态已变化，请刷新后重试");
        }
    }

    /**
     * 彻底删除已归档标签：仅 archived 可删。控制台全路径不再展示；
     * tag_meta 行与打标值/流水/审核历史全部保留（底层数据库可查），tag_code 不可复用。
     * 删除为终态，控制台不可恢复。
     */
    @Transactional
    public void deleteArchived(String tagCode) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        if (!"archived".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "仅已归档标签可以彻底删除，当前状态: " + meta.status());
        }
        if (tagMetaRepository.updateStatusIfCurrent(tagCode, "archived", "deleted") == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签状态已变化，请刷新后重试");
        }
    }

    /** 已彻底删除的标签为终态，拒绝一切控制台写操作（数据仍可在底层库查询） */
    private void ensureNotDeleted(TagMeta meta) {
        if ("deleted".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已彻底删除，不可再操作");
        }
    }

    /** 写入开关切换：false 后开放 API 推送/作废该标签值将整批拒绝（code=3012） */
    @Transactional
    public void changeWritable(String tagCode, boolean writable) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        ensureCanEdit(meta);
        if ("derived".equalsIgnoreCase(meta.tagType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "组合标签由查询期计算，不支持写入开关");
        }
        ensureNotDeleted(meta);
        if ("archived".equals(meta.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已归档，不能修改写入开关");
        }
        if (meta.writable() == writable) {
            return;
        }
        tagMetaRepository.updateWritable(tagCode, writable);
        TagMeta updatedMeta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        tagVersionRepository.insert(updatedMeta, "writable", currentOperator("system"),
                "写入开关：" + (meta.writable() ? "允许" : "禁止") + " → " + (writable ? "允许" : "禁止"));
    }

    // ------------------------------------------------------------------ version

    public List<TagVersion.Summary> listVersions(String tagCode, int limit) {
        findOrThrow(tagCode);
        return tagVersionRepository.list(tagCode, limit).stream()
                .map(TagVersion::summary)
                .toList();
    }

    public TagVersion.Detail versionDetail(String tagCode, int version) {
        findOrThrow(tagCode);
        TagVersion selected = tagVersionRepository.find(tagCode, version)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID,
                        "版本不存在: " + tagCode + " v" + version));
        TagVersion previous = tagVersionRepository.findPrevious(tagCode, version).orElse(null);
        return new TagVersion.Detail(selected.summary(), selected.definition(),
                previous == null ? null : previous.definition());
    }

    /**
     * 回滚到历史定义：不覆盖历史版本，也不改变标签上下线状态；
     * 在当前 version 基础上生成一个新版本（change_type=rollback）。
     */
    @Transactional
    public TagMeta rollback(String tagCode, int targetVersion) {
        TagMeta current = findOrThrow(tagCode);
        ensureCanEdit(current);
        ensureNotDeleted(current);
        if ("archived".equals(current.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已归档，请先恢复后再回滚");
        }
        if ("reviewing".equals(current.status()) || tagAuditRepository.countPendingByTag(tagCode) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签定义正在审核中，暂不能回滚");
        }
        if (targetVersion < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标版本不合法: " + targetVersion);
        }
        if (targetVersion >= current.version()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标版本需早于当前版本 v" + current.version());
        }
        TagVersion target = tagVersionRepository.find(tagCode, targetVersion)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID,
                        "历史版本不存在: v" + targetVersion));
        TagVersion.Definition definition = target.definition();
        String actorRole = RequestContext.getConsoleRole();
        // 租户管理员回滚定义但保留当前适用租户，避免通过历史版本改变归属/共享范围
        String tenantId = normalizeTenants("TENANT_ADMIN".equals(actorRole)
                ? current.tenantId() : definition.tenantId());
        String canonicalRange = validateDefinition(tagCode, definition.tagName(), definition.valueType(),
                definition.tagType(), definition.sensitivity(), tenantId,
                definition.valueTtlDays(), definition.sourceMode(), definition.objectType(),
                definition.valueRange(), definition.valueMultiple());

        int updated = tagMetaRepository.updateDefinition(tagCode, definition.tagName(),
                definition.categoryCode(), definition.dim1Code(), definition.dim2Code(), definition.priority(),
                tenantId, definition.valueType(), definition.unit(), canonicalRange,
                definition.valueMultiple(), definition.tagType(), definition.needValueAudit(),
                definition.valueTtlDays(), definition.sensitivity(), normalizeBinding(definition.objectType()),
                definition.sourceMode(), definition.writable(), current.version());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已被其他人修改，请刷新后重试");
        }
        TagMeta updatedMeta = findOrThrow(tagCode);
        tagVersionRepository.insert(updatedMeta, "rollback", currentOperator("system"),
                "回滚到 v" + targetVersion + "（生成 v" + updatedMeta.version() + "）");
        return updatedMeta;
    }

    /**
     * 标签市场审批通过后，将租户追加到标签适用租户列表。
     * 只扩展可见范围，不修改其他定义；会生成新的 market 版本快照。
     */
    @Transactional
    public TagMeta grantTenant(String tagCode, String tenantId, String changedBy) {
        TagMeta tag = findOrThrow(tagCode);
        if (!tag.online()) {
            throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签未上线，不能授权: " + tagCode);
        }
        ensureNotDeleted(tag);
        if ("archived".equals(tag.status()) || tagAuditRepository.countPendingByTag(tagCode) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签存在待处理定义变更，暂不能授权");
        }
        if (tag.visibleTo(tenantId)) {
            return tag;
        }
        String currentTenants = tag.tenantId();
        if (currentTenants != null && currentTenants.contains("PUBLIC")) {
            return tag;
        }
        String merged = Arrays.stream(currentTenants == null ? new String[0] : currentTenants.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .collect(Collectors.joining(","));
        merged = merged.isEmpty() ? tenantId : merged + "," + tenantId;
        String canonicalTenants = normalizeTenants(merged);
        int updated = tagMetaRepository.updateDefinition(tagCode, tag.tagName(), tag.categoryCode(),
                tag.dim1Code(), tag.dim2Code(), tag.priority(),
                canonicalTenants, tag.valueType(), tag.unit(), tag.valueRange(), tag.valueMultiple(), tag.tagType(),
                tag.needValueAudit(), tag.valueTtlDays(), tag.sensitivity(), tag.objectType(),
                tag.sourceMode(), tag.writable(), tag.version());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已被其他人修改，请刷新后重试");
        }
        TagMeta updatedMeta = findOrThrow(tagCode);
        tagVersionRepository.insert(updatedMeta, "market", changedBy,
                "标签市场授权租户: " + tenantId);
        return updatedMeta;
    }

    private TagMeta findOrThrow(String tagCode) {
        return tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
    }

    private String changeSummary(String prefix, TagMeta before, TagMeta after) {
        TagVersion.Definition a = TagVersion.Definition.from(before);
        TagVersion.Definition b = TagVersion.Definition.from(after);
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(a.tagName(), b.tagName())) changed.add("标签名称");
        if (!Objects.equals(a.categoryCode(), b.categoryCode())) changed.add("类目");
        if (!Objects.equals(a.valueType(), b.valueType())) changed.add("值类型");
        if (!Objects.equals(a.valueRange(), b.valueRange())) changed.add("值域");
        if (a.valueMultiple() != b.valueMultiple()) changed.add("选择方式");
        if (!Objects.equals(a.tagType(), b.tagType())) changed.add("标签类型");
        if (a.needValueAudit() != b.needValueAudit()) changed.add("值审核");
        if (a.valueTtlDays() != b.valueTtlDays()) changed.add("TTL");
        if (!Objects.equals(a.tenantId(), b.tenantId())) changed.add("适用租户");
        if (!Objects.equals(a.sensitivity(), b.sensitivity())) changed.add("敏感级");
        if (!Objects.equals(a.objectType(), b.objectType())) changed.add("适用对象");
        if (!Objects.equals(a.sourceMode(), b.sourceMode())) changed.add("来源类型");
        if (a.writable() != b.writable()) changed.add("允许写入");
        return changed.isEmpty() ? prefix : prefix + "：" + String.join("、", changed);
    }

    private void ensureCanEdit(TagMeta tag) {
        String role = RequestContext.getConsoleRole();
        if (role == null || "ADMIN".equals(role)) {
            return;
        }
        if (!"TENANT_ADMIN".equals(role)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
        String tenant = requireActorTenant();
        if (!tag.ownedByTenant(tenant)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN,
                    "仅标签归属租户管理员可编辑（归属: " + firstTenant(tag.tenantId()) + "）");
        }
    }

    private String requireActorTenant() {
        String tenant = RequestContext.getConsoleTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        return tenant;
    }

    private static String firstTenant(String tenantIds) {
        if (tenantIds == null || tenantIds.isBlank()) {
            return "--";
        }
        return tenantIds.split(",")[0].trim();
    }

    private String currentOperator(String fallback) {
        String operator = RequestContext.getConsoleDisplayName();
        return operator == null || operator.isBlank() ? fallback : operator;
    }

    /**
     * 适用租户归一化：逗号分隔、去空格、去重；PUBLIC 表示全部租户（优先返回 PUBLIC）；
     * 非 PUBLIC 租户必须已存在，最多 8 个，列表长度不超过列宽 512。
     */
    private String normalizeTenants(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenant_id 不能为空（公开标签填 PUBLIC）");
        }
        LinkedHashSet<String> tenants = new LinkedHashSet<>();
        boolean publicAll = false;
        for (String raw : tenantId.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            if ("PUBLIC".equals(id)) {
                publicAll = true;
                continue;
            }
            if (id.length() > 64) {
                throw new BizException(ErrorCode.PARAM_INVALID, "租户标识长度不能超过 64: " + id);
            }
            if (tenantRepository.findByTenantId(id).isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "租户不存在: " + id);
            }
            tenants.add(id);
        }
        if (publicAll) {
            return "PUBLIC";
        }
        if (tenants.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少选择一个适用租户");
        }
        if (tenants.size() > 8) {
            throw new BizException(ErrorCode.PARAM_INVALID, "最多选择 8 个适用租户");
        }
        String joined = String.join(",", tenants);
        if (joined.length() > 512) {
            throw new BizException(ErrorCode.PARAM_INVALID, "适用租户列表过长（上限 512 字符）");
        }
        return joined;
    }

    private String validateDefinition(String tagCode, String tagName, String valueType, String tagType,
                                      String sensitivity, String tenantId, int valueTtlDays,
                                      String sourceMode, String objectType, String valueRange,
                                      boolean valueMultiple) {
        if (tagCode == null || !TAG_CODE_PATTERN.matcher(tagCode).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag_code 需满足 ^[a-z][a-z0-9_]{2,63}$");
        }
        if (tagName == null || tagName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag_name 不能为空");
        }
        if (tagName.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag_name 长度不能超过 128 个字符");
        }
        if (!VALUE_TYPES.contains(valueType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "value_type 仅支持 " + VALUE_TYPES);
        }
        if (!TAG_TYPES.contains(tagType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tag_type 仅支持 " + TAG_TYPES);
        }
        if (!SENSITIVITIES.contains(sensitivity)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sensitivity 仅支持 " + SENSITIVITIES);
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenant_id 不能为空（公开标签填 PUBLIC）");
        }
        if (valueTtlDays < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "value_ttl_days 不能为负数");
        }
        if (valueMultiple && !"enum".equals(valueType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅 enum 类型支持多选");
        }
        if (sourceMode != null && !sourceMode.isBlank() && !SOURCE_MODES.contains(sourceMode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "source_mode 仅支持 " + SOURCE_MODES + "（留空 = 未声明）");
        }
        if (objectType != null && !objectType.isBlank()) {
            for (String type : objectType.split(",")) {
                String t = type.trim();
                if (t.isEmpty()) {
                    continue;
                }
                var objectTypeMeta = objectTypeRepository.findByType(t)
                        .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "object_type 未注册: " + t));
                if (!objectTypeMeta.enabled()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "object_type 已停用: " + t);
                }
            }
        }
        if ("enum".equals(valueType)) {
            return validateEnumRange(valueRange);
        }
        return normalize(valueRange);
    }

    /**
     * enum 值域校验与归一化：必须为 {"enum":[...]}，值非空、无重复、不含英文逗号（多选分隔符）。
     * 返回规范化 JSON（保留配置顺序），供写入校验与开放字典复用。
     */
    private String validateEnumRange(String valueRange) {
        if (valueRange == null || valueRange.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签必须配置 value_range.enum");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(valueRange);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签 value_range 必须是合法 JSON");
        }
        JsonNode enumNode = root == null ? null : root.get("enum");
        if (enumNode == null || !enumNode.isArray() || enumNode.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签 value_range.enum 必须是非空数组");
        }
        if (enumNode.size() > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签最多配置 100 个枚举值");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode node : enumNode) {
            if (!node.isTextual()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签的枚举值必须为字符串");
            }
            String value = node.asText().trim();
            if (value.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "enum 标签的枚举值不能为空");
            }
            if (value.length() > 128) {
                throw new BizException(ErrorCode.PARAM_INVALID, "单个枚举值长度不能超过 128 个字符: " + value);
            }
            if (value.contains(",")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "枚举值不能包含英文逗号（多选写入分隔符）: " + value);
            }
            if (!values.add(value)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "枚举值重复: " + value);
            }
        }
        try {
            return objectMapper.writeValueAsString(Map.of("enum", values));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "enum 值域序列化失败");
        }
    }

    /** 绑定归一化：去掉逗号分隔项的空格；空 → null（不限对象） */
    private static String normalizeBinding(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            return null;
        }
        return Arrays.stream(objectType.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 单位归一化：空白 → null；上限 16 字符（与 tag_meta.unit 列宽一致） */
    private static String normalizeUnit(String unit) {
        String value = normalize(unit);
        if (value != null && value.length() > 16) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单位超长（上限 16 字符）");
        }
        return value;
    }

    /**
     * 标签注册请求（writable 为 null 时默认允许写入，兼容既有注册调用）。
     */
    public record RegisterRequest(
            String tagCode,
            String tagName,
            String categoryCode,
            String dim1Code,
            String dim2Code,
            Integer priority,
            String valueType,
            String unit,
            String valueRange,
            String tagType,
            boolean needValueAudit,
            int valueTtlDays,
            String tenantId,
            String sensitivity,
            String objectType,
            String sourceMode,
            Boolean valueMultiple,
            Boolean writable) {

        /** 兼容旧调用：不指定一级/二级维度、优先级与单位 */
        public RegisterRequest(String tagCode, String tagName, String categoryCode, String valueType,
                               String valueRange, String tagType, boolean needValueAudit, int valueTtlDays,
                               String tenantId, String sensitivity, String objectType, String sourceMode,
                               Boolean valueMultiple, Boolean writable) {
            this(tagCode, tagName, categoryCode, null, null, null, valueType, null, valueRange, tagType,
                    needValueAudit, valueTtlDays, tenantId, sensitivity, objectType, sourceMode,
                    valueMultiple, writable);
        }
    }

    /**
     * 标签定义修改请求：tag_code / tenant_id 不可修改；version 为乐观锁版本号。
     */
    public record UpdateRequest(
            String tagName,
            String categoryCode,
            String dim1Code,
            String dim2Code,
            Integer priority,
            String tenantId,
            String valueType,
            String unit,
            String valueRange,
            String tagType,
            Boolean needValueAudit,
            Integer valueTtlDays,
            String sensitivity,
            String objectType,
            String sourceMode,
            Boolean valueMultiple,
            Boolean writable,
            Integer version) {

        /** 兼容旧调用：不指定一级/二级维度、优先级与单位 */
        public UpdateRequest(String tagName, String categoryCode, String tenantId, String valueType,
                             String valueRange, String tagType, Boolean needValueAudit, Integer valueTtlDays,
                             String sensitivity, String objectType, String sourceMode, Boolean valueMultiple,
                             Boolean writable, Integer version) {
            this(tagName, categoryCode, null, null, null, tenantId, valueType, null, valueRange, tagType,
                    needValueAudit, valueTtlDays, sensitivity, objectType, sourceMode, valueMultiple,
                    writable, version);
        }
    }
}
