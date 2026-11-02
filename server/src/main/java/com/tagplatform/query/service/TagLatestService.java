package com.tagplatform.query.service;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.meta.composition.TagCompositionService;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.query.web.dto.TagLatestPage;
import com.tagplatform.write.store.TagValueStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签当前值查询（控制台标签查看页）。
 * 组合标签没有 latest 物理行，按输入标签最近有值的对象抽样计算，口径与画像查询期求值一致。
 */
@Service
public class TagLatestService {

    private final TagMetaRepository tagMetaRepository;
    private final TagValueStore tagValueStore;
    private final TagCompositionService tagCompositionService;

    public TagLatestService(TagMetaRepository tagMetaRepository, TagValueStore tagValueStore,
                            TagCompositionService tagCompositionService) {
        this.tagMetaRepository = tagMetaRepository;
        this.tagValueStore = tagValueStore;
        this.tagCompositionService = tagCompositionService;
    }

    public TagLatestPage latest(String tenantId, String tagCode, int limit) {
        TagMeta meta = tagMetaRepository.findByCode(tagCode)
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + tagCode));
        if (!meta.visibleTo(tenantId)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "标签对该租户不可见: " + tenantId);
        }
        int size = Math.max(1, Math.min(limit, 100));
        if ("derived".equalsIgnoreCase(meta.tagType())) {
            List<TagLatestPage.Row> rows = tagCompositionService.computedValues(tagCode, tenantId, size).stream()
                    .map(v -> new TagLatestPage.Row(v.objectType(), v.objectId(), v.tagValue(), v.status(),
                            v.detail(), v.bizTime(), v.operateTime(), v.expireTime(), v.operatorName(), v.source()))
                    .toList();
            long matched = rows.stream().filter(r -> "effective".equals(r.status())).count();
            return new TagLatestPage(tagCode, true, matched, rows.size(), rows);
        }
        List<TagLatestPage.Row> rows = tagValueStore.listLatestByTag(tenantId, tagCode, size).stream()
                .map(v -> new TagLatestPage.Row(v.objectType(), v.objectId(), v.tagValue(), v.status(), null,
                        v.bizTime(), v.operateTime(), v.expireTime(), v.operatorName(), v.source()))
                .toList();
        return new TagLatestPage(tagCode, false, tagValueStore.countLatestByTag(tenantId, tagCode), rows.size(), rows);
    }

    /** 供其它模块复用：标签是否组合标签 */
    public boolean isDerived(String tagCode) {
        return tagMetaRepository.findByCode(tagCode)
                .map(m -> "derived".equalsIgnoreCase(m.tagType()))
                .orElse(false);
    }
}
