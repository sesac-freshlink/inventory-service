package com.freshlink.inventory.domain.storestock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreProductStockRepository extends JpaRepository<StoreProductStock, Long> {

    /**
     * U-10 고객 상품 목록. 상품 전체에 그 점포의 조회 표 행을 LEFT JOIN 하고, q가 있으면 상품명으로 거른다.
     * 상품명 순. q는 LikePattern.contains로 만든 값(null이면 조건 없음)
     */
    @Query("""
            SELECT new com.freshlink.inventory.domain.storestock.StoreProductRow(
                p.id, p.name, p.category, p.price, p.imageUrl, s.availableQuantity, s.validUntil
            )
            FROM Product p
            LEFT JOIN StoreProductStock s ON s.productId = p.id AND s.storeId = :storeId
            WHERE :q IS NULL OR p.name LIKE :q ESCAPE '\\'
            ORDER BY p.name, p.id
            """)
    List<StoreProductRow> findStoreProducts(@Param("storeId") Long storeId, @Param("q") String q);

    /** U-10 고객 상품 상세. 목록과 같은 JOIN에서 상품 하나. 상품이 없으면 빈 값 */
    @Query("""
            SELECT new com.freshlink.inventory.domain.storestock.StoreProductRow(
                p.id, p.name, p.category, p.price, p.imageUrl, s.availableQuantity, s.validUntil
            )
            FROM Product p
            LEFT JOIN StoreProductStock s ON s.productId = p.id AND s.storeId = :storeId
            WHERE p.id = :productId
            """)
    Optional<StoreProductRow> findStoreProduct(@Param("storeId") Long storeId, @Param("productId") Long productId);

    /** 정리 작업용. 값이 맞는 시각이 지난 행 */
    List<StoreProductStock> findByValidUntilLessThanEqual(LocalDateTime now);

    /** 있으면 바꾸고 없으면 넣는다. 안전재고(S-40)와 같은 이유로 한 문장으로 한다 */
    @Modifying
    @Query(value = """
            INSERT INTO store_product_stock (store_id, product_id, available_quantity, valid_until, updated_at)
            VALUES (:storeId, :productId, :availableQuantity, :validUntil, :now) AS new
            ON DUPLICATE KEY UPDATE available_quantity = new.available_quantity,
                                    valid_until = new.valid_until,
                                    updated_at = new.updated_at
            """, nativeQuery = true)
    void upsert(
            @Param("storeId") Long storeId,
            @Param("productId") Long productId,
            @Param("availableQuantity") int availableQuantity,
            @Param("validUntil") LocalDateTime validUntil,
            @Param("now") LocalDateTime now
    );
}
