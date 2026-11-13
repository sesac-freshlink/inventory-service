package com.freshlink.inventory.domain.assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * AI 비서 요약에 필요한 날짜별·시간별 순판매(판매 − 환불 복원). 원장은 판매가 음수, 환불 복원이 양수라 부호를 뒤집는다.
 * 점포·기간 조건은 idx_ledger_store_created를 탄다. 날짜·시간은 원장 시각(처리 시각, KST) 기준이다
 */
@Repository
public class AssistantSalesRepository {

    private final JdbcTemplate jdbcTemplate;

    public AssistantSalesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DailySale(Long productId, LocalDate date, int quantity) {
    }

    public record HourlySale(Long productId, int hour, int quantity) {
    }

    public List<DailySale> dailySales(Long storeId, LocalDateTime from, LocalDateTime toExclusive) {
        return jdbcTemplate.query("""
                        SELECT lot.product_id, DATE(ledger.created_at) AS sale_date, -SUM(ledger.quantity) AS sold
                        FROM ledger
                        JOIN inventory_lot lot ON lot.id = ledger.lot_id
                        WHERE ledger.store_id = ? AND ledger.type IN ('SALE', 'RETURN')
                          AND ledger.created_at >= ? AND ledger.created_at < ?
                        GROUP BY lot.product_id, DATE(ledger.created_at)
                        """,
                (rs, i) -> new DailySale(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3)),
                storeId, from, toExclusive);
    }

    public List<HourlySale> hourlySales(Long storeId, LocalDateTime from, LocalDateTime toExclusive) {
        return jdbcTemplate.query("""
                        SELECT lot.product_id, HOUR(ledger.created_at) AS sale_hour, -SUM(ledger.quantity) AS sold
                        FROM ledger
                        JOIN inventory_lot lot ON lot.id = ledger.lot_id
                        WHERE ledger.store_id = ? AND ledger.type IN ('SALE', 'RETURN')
                          AND ledger.created_at >= ? AND ledger.created_at < ?
                        GROUP BY lot.product_id, HOUR(ledger.created_at)
                        """,
                (rs, i) -> new HourlySale(rs.getLong(1), rs.getInt(2), rs.getInt(3)),
                storeId, from, toExclusive);
    }
}
