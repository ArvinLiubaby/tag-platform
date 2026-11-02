package com.tagplatform.market;

import com.tagplatform.market.TagMarketModels.ListingRow;
import com.tagplatform.market.TagMarketModels.RequestRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class TagMarketRepository {

    private static final RowMapper<ListingRow> LISTING_MAPPER = (rs, rowNum) -> new ListingRow(
            rs.getLong("id"), rs.getString("tag_code"), rs.getString("owner_tenant_id"),
            rs.getString("description"), rs.getString("status"), rs.getString("listed_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, rowNum) -> new RequestRow(
            rs.getLong("id"), rs.getLong("listing_id"), rs.getString("tag_code"),
            rs.getString("applicant_tenant_id"), rs.getString("applicant_user"),
            rs.getString("remark"), rs.getString("status"), rs.getString("reviewed_by"),
            rs.getString("review_remark"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TagMarketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertListing(String tagCode, String ownerTenantId, String description, String listedBy) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tag_market_listing (tag_code, owner_tenant_id, description, status,
                                                    listed_by, created_at, updated_at)
                    VALUES (?, ?, ?, 'listed', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tagCode);
            ps.setString(2, ownerTenantId);
            ps.setString(3, description);
            ps.setString(4, listedBy);
            ps.setObject(5, now);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("标签市场上架失败：未返回主键");
        }
        return key.longValue();
    }

    public Optional<ListingRow> findListing(long id) {
        return jdbcTemplate.query("SELECT * FROM tag_market_listing WHERE id = ?", LISTING_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ListingRow> findListingByTag(String tagCode) {
        return jdbcTemplate.query("SELECT * FROM tag_market_listing WHERE tag_code = ?", LISTING_MAPPER, tagCode)
                .stream().findFirst();
    }

    public List<ListingRow> listListings() {
        return jdbcTemplate.query("""
                SELECT * FROM tag_market_listing WHERE status = 'listed'
                 ORDER BY created_at DESC, id DESC
                """, LISTING_MAPPER);
    }

    public long insertRequest(long listingId, String tagCode, String applicantTenantId,
                              String applicantUser, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tag_market_request (listing_id, tag_code, applicant_tenant_id,
                                                    applicant_user, remark, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'pending', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, listingId);
            ps.setString(2, tagCode);
            ps.setString(3, applicantTenantId);
            ps.setString(4, applicantUser);
            ps.setString(5, remark);
            ps.setObject(6, LocalDateTime.now());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("标签市场申请失败：未返回主键");
        }
        return key.longValue();
    }

    public Optional<RequestRow> findRequest(long id) {
        return jdbcTemplate.query("SELECT * FROM tag_market_request WHERE id = ?", REQUEST_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RequestRow> findLatestRequest(long listingId, String applicantTenantId) {
        return jdbcTemplate.query("""
                SELECT * FROM tag_market_request
                 WHERE listing_id = ? AND applicant_tenant_id = ?
                 ORDER BY id DESC LIMIT 1
                """, REQUEST_MAPPER, listingId, applicantTenantId).stream().findFirst();
    }

    public List<RequestRow> listRequests(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("SELECT * FROM tag_market_request ORDER BY id DESC", REQUEST_MAPPER);
        }
        return jdbcTemplate.query("SELECT * FROM tag_market_request WHERE status = ? ORDER BY id DESC",
                REQUEST_MAPPER, status);
    }

    public List<RequestRow> listRequestsForTenant(String tenantId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT * FROM tag_market_request WHERE applicant_tenant_id = ? ORDER BY id DESC",
                    REQUEST_MAPPER, tenantId);
        }
        return jdbcTemplate.query("""
                SELECT * FROM tag_market_request
                 WHERE applicant_tenant_id = ? AND status = ? ORDER BY id DESC
                """, REQUEST_MAPPER, tenantId, status);
    }

    public int decideRequest(long id, String status, String reviewedBy, String reviewRemark) {
        return jdbcTemplate.update("""
                UPDATE tag_market_request
                   SET status = ?, reviewed_by = ?, review_remark = ?, reviewed_at = ?
                 WHERE id = ? AND status = 'pending'
                """, status, reviewedBy, reviewRemark, LocalDateTime.now(), id);
    }

    public List<String> listedTagCodes() {
        return jdbcTemplate.queryForList(
                "SELECT tag_code FROM tag_market_listing WHERE status = 'listed'", String.class);
    }
}
