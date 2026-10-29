package com.freshlink.inventory.domain.inventory;

import com.freshlink.inventory.common.search.LikePattern;
import com.freshlink.inventory.domain.safetystock.SafetyStock;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * S-10 점주 재고 목록을 SQL 한 번으로 집계·검색·자른다. 점포의 lot을 상품별로 GROUP BY 하면서
 * 상품명은 WHERE로, 집계해야 나오는 상태(임박·경과·품절·부족)는 HAVING으로 거르고 LIMIT/OFFSET으로 한 페이지만 돌려준다.
 * 상품이 많아져도 DB가 필요한 줄만 돌려주고, 3초 폴링 응답도 한 페이지로 줄어든다.
 *
 * <p>시간에 따라 바뀌는 값(구매 가능·임박·경과)은 저장해 두지 않고 요청 때마다 지금 시각(:now)과 비교해 계산한다.
 * 규칙은 InventoryCalculationService와 같다: 경과 = 기한 ≤ 지금, 임박 = 지금 &lt; 기한 ≤ 지금 + 24시간,
 * 구매 가능 = 기한 &gt; 지금인 lot의 (수량 − 예약) 합. 임박·경과 수와 가장 빠른 기한은 수량이 남은 lot만 센다.
 */
@Repository
public class InventorySearchRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 상품별 집계. 아래에서 WHERE·HAVING·ORDER·LIMIT을 붙인다 */
    private static final String SELECT = """
            SELECT p.id                AS product_id,
                   p.product_code,
                   p.name,
                   p.category,
                   p.price,
                   p.image_url,
                   SUM(l.quantity)          AS total_quantity,
                   SUM(l.reserved_quantity) AS reserved_quantity,
                   SUM(CASE WHEN l.expiry_date > :now THEN l.quantity - l.reserved_quantity ELSE 0 END)
                                            AS available_quantity,
                   MIN(CASE WHEN l.quantity > 0 THEN l.expiry_date END)
                                            AS nearest_expiry,
                   SUM(CASE WHEN l.quantity > 0 AND l.expiry_date > :now AND l.expiry_date <= :imminentUntil
                            THEN 1 ELSE 0 END) AS imminent_lot_count,
                   SUM(CASE WHEN l.quantity > 0 AND l.expiry_date <= :now THEN 1 ELSE 0 END)
                                            AS expired_lot_count,
                   COALESCE(s.quantity, :defaultSafetyStock) AS safety_stock,
                   m.id IS NOT NULL         AS has_memo
            FROM inventory_lot l
            JOIN product p ON p.id = l.product_id
            LEFT JOIN safety_stock s ON s.store_id = l.store_id AND s.product_id = l.product_id
            LEFT JOIN product_memo m ON m.store_id = l.store_id AND m.product_id = l.product_id
            WHERE l.store_id = :storeId
            """;

    private static final String GROUP_BY = """
            GROUP BY p.id, p.product_code, p.name, p.category, p.price, p.image_url, s.quantity, m.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InventorySearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 조건에 맞는 상품을 상품명 순으로 offset부터 size개 */
    public List<InventorySummaryResponse> search(
            Long storeId, InventorySearchCondition condition, LocalDateTime now, int offset, int size) {
        MapSqlParameterSource params = params(storeId, condition, now)
                .addValue("offset", offset)
                .addValue("size", size);
        String sql = filtered(condition) + " ORDER BY p.name, p.id LIMIT :size OFFSET :offset";
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    /** 조건에 맞는 상품 수 (페이지와 상관없이) */
    public long count(Long storeId, InventorySearchCondition condition, LocalDateTime now) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + filtered(condition) + ") matched",
                params(storeId, condition, now),
                Long.class);
        return count == null ? 0 : count;
    }

    /** 집계 + 검색어(WHERE) + 상태(HAVING). 조건이 있을 때만 붙인다 */
    private static String filtered(InventorySearchCondition condition) {
        StringBuilder sql = new StringBuilder(SELECT);
        if (condition.q() != null) {
            sql.append("  AND (p.name LIKE :q OR p.product_code LIKE :q)\n");
        }
        if (condition.memoOnly()) {
            sql.append("  AND m.id IS NOT NULL\n");
        }
        sql.append(GROUP_BY);

        List<String> having = new ArrayList<>();
        for (InventoryStatusFilter status : condition.statuses()) {
            having.add(switch (status) {
                case IMMINENT -> "imminent_lot_count > 0";
                case EXPIRED -> "expired_lot_count > 0";
                case SOLD_OUT -> "available_quantity = 0";
                case LOW_STOCK -> "available_quantity < safety_stock";
            });
        }
        if (!having.isEmpty()) {
            sql.append(" HAVING ").append(String.join(" AND ", having));
        }
        return sql.toString();
    }

    private static MapSqlParameterSource params(Long storeId, InventorySearchCondition condition, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("storeId", storeId)
                .addValue("now", now)
                .addValue("imminentUntil", now.plusHours(24))
                .addValue("defaultSafetyStock", SafetyStock.DEFAULT_QUANTITY);
        if (condition.q() != null) {
            params.addValue("q", LikePattern.contains(condition.q()));
        }
        return params;
    }

    private static final RowMapper<InventorySummaryResponse> ROW_MAPPER = (rs, rowNum) -> {
        LocalDateTime nearestExpiry = rs.getObject("nearest_expiry", LocalDateTime.class);
        int available = rs.getInt("available_quantity");
        int safetyStock = rs.getInt("safety_stock");
        return new InventorySummaryResponse(
                rs.getLong("product_id"),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getLong("price"),
                rs.getString("image_url"),
                rs.getInt("total_quantity"),
                rs.getInt("reserved_quantity"),
                available,
                nearestExpiry == null ? null : nearestExpiry.atZone(SEOUL).toOffsetDateTime(),
                rs.getInt("imminent_lot_count"),
                rs.getInt("expired_lot_count"),
                safetyStock,
                available < safetyStock,
                rs.getBoolean("has_memo")
        );
    };
}
