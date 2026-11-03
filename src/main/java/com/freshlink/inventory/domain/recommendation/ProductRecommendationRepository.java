package com.freshlink.inventory.domain.recommendation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 점주가 고른 추천상품. 행 하나가 "이 점포에서 이 상품을 추천한다"이고 값은 없어서 JPA 엔티티 없이 SQL로 다룬다.
 */
@Repository
public class ProductRecommendationRepository {

    private final JdbcTemplate jdbc;

    public ProductRecommendationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Long> findProductIds(Long storeId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT product_id FROM product_recommendation WHERE store_id = ?", Long.class, storeId);
        return Set.copyOf(ids);
    }

    /** 이미 추천이면 아무것도 바꾸지 않는다. 같은 요청을 다시 보내도 결과가 같다 */
    public void add(Long storeId, Long productId, LocalDateTime now) {
        jdbc.update("""
                INSERT IGNORE INTO product_recommendation (store_id, product_id, created_at)
                VALUES (?, ?, ?)
                """, storeId, productId, now);
    }

    /** 추천이 아니면 아무것도 지우지 않는다. 점포·상품 조건이 있는 DELETE다 */
    public void remove(Long storeId, Long productId) {
        jdbc.update("DELETE FROM product_recommendation WHERE store_id = ? AND product_id = ?", storeId, productId);
    }
}
