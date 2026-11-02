package com.tagplatform.write.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 写入幂等登记（biz_request_id 表）：
 *   首次请求 tryClaim 成功并登记 processing；处理完成后 complete 写入响应快照；
 *   重复提交时命中 done 直接回放首次响应；仍在 processing 的并发请求返回 3004；
 *   处理异常时 release 删除登记（允许客户端重试）。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 返回 false 表示该 biz_request_id 已被登记（重复提交） */
    public boolean tryClaim(String bizRequestId, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO biz_request_id (biz_request_id, tenant_id, status, created_at, updated_at)
                    VALUES (?, ?, 'processing', ?, ?)
                    """, bizRequestId, tenantId, now, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 已完成的响应快照（done 状态） */
    public Optional<String> findCompletedResponse(String bizRequestId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT response_json FROM biz_request_id WHERE biz_request_id = ? AND status = 'done'",
                (rs, rowNum) -> rs.getString("response_json"), bizRequestId);
        return rows.stream().findFirst();
    }

    public boolean isProcessing(String bizRequestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_request_id WHERE biz_request_id = ? AND status = 'processing'",
                Integer.class, bizRequestId);
        return count != null && count > 0;
    }

    public void complete(String bizRequestId, String responseJson) {
        jdbcTemplate.update(
                "UPDATE biz_request_id SET status = 'done', response_json = ?, updated_at = ? WHERE biz_request_id = ?",
                responseJson, LocalDateTime.now(), bizRequestId);
    }

    /** 处理失败回滚登记（仅删除 processing 状态的行，防止误删已完成记录） */
    public void release(String bizRequestId) {
        jdbcTemplate.update("DELETE FROM biz_request_id WHERE biz_request_id = ? AND status = 'processing'",
                bizRequestId);
    }
}
