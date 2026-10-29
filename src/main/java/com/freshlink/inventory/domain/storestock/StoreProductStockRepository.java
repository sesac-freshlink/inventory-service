package com.freshlink.inventory.domain.storestock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StoreProductStockRepository extends JpaRepository<StoreProductStock, Long> {

    List<StoreProductStock> findByStoreId(Long storeId);

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
