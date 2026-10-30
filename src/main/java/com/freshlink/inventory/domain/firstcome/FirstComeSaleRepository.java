package com.freshlink.inventory.domain.firstcome;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 선착순 상품과 판매 한도, 그리고 한도에 센 수량(시작 뒤 예약·확정). 값이 단순해 JPA 엔티티 없이 SQL로 다룬다.
 */
@Repository
public class FirstComeSaleRepository {

    private final JdbcTemplate jdbc;

    public FirstComeSaleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FirstComeSale(Long productId, int limit, LocalDateTime startedAt) {
    }

    public Optional<FirstComeSale> find(Long storeId, Long productId) {
        List<FirstComeSale> rows = jdbc.query("""
                SELECT product_id, sale_limit, started_at FROM first_come_sale
                WHERE store_id = ? AND product_id = ?
                """, (rs, i) -> new FirstComeSale(
                rs.getLong("product_id"), rs.getInt("sale_limit"),
                rs.getObject("started_at", LocalDateTime.class)), storeId, productId);
        return rows.stream().findFirst();
    }

    public Map<Long, FirstComeSale> findAll(Long storeId) {
        Map<Long, FirstComeSale> sales = new HashMap<>();
        jdbc.query("SELECT product_id, sale_limit, started_at FROM first_come_sale WHERE store_id = ?",
                rs -> {
                    long productId = rs.getLong("product_id");
                    sales.put(productId, new FirstComeSale(productId, rs.getInt("sale_limit"),
                            rs.getObject("started_at", LocalDateTime.class)));
                }, storeId);
        return sales;
    }

    public Set<Long> findProductIds(Long storeId) {
        return Set.copyOf(jdbc.queryForList(
                "SELECT product_id FROM first_come_sale WHERE store_id = ?", Long.class, storeId));
    }

    /**
     * 처음 켜면 started_at = now. 이미 선착순이면 한도만 바꾸고 started_at은 그대로 둔다(이미 판 수량을 계속 센다).
     * 안전재고(S-40)와 같은 이유로 한 문장으로 한다
     */
    public void upsert(Long storeId, Long productId, int limit, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO first_come_sale (store_id, product_id, sale_limit, started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE sale_limit = new.sale_limit, updated_at = new.updated_at
                """, storeId, productId, limit, now, now, now);
    }

    /** 선착순을 끈다. 점포·상품 조건이 있는 DELETE다 */
    public void remove(Long storeId, Long productId) {
        jdbc.update("DELETE FROM first_come_sale WHERE store_id = ? AND product_id = ?", storeId, productId);
    }

    /**
     * 한도에 센 수량: 선착순을 켠 뒤에 생긴 그 점포·상품의 예약 중 아직 잡혀 있거나(RESERVED) 팔린(CONFIRMED) 수량.
     * 해제(RELEASED)된 예약은 자리가 다시 난다. 환불(RETURN)은 자리를 다시 내지 않는다(한정 수량 판매는 한 번으로 본다)
     */
    public int countUsed(Long storeId, Long productId, LocalDateTime startedAt) {
        Integer used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0)
                FROM reservation r
                JOIN inventory_lot l ON l.id = r.lot_id
                WHERE l.store_id = ? AND l.product_id = ?
                  AND r.status IN ('RESERVED', 'CONFIRMED')
                  AND r.created_at >= ?
                """, Integer.class, storeId, productId, startedAt);
        return used == null ? 0 : used;
    }
}
