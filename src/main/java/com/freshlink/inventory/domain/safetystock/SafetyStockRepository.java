package com.freshlink.inventory.domain.safetystock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyStockRepository extends JpaRepository<SafetyStock, Long> {

    List<SafetyStock> findByStoreId(Long storeId);

    /**
     * 있으면 수량을 바꾸고 없으면 넣는다. 찾고 나서 저장하면 같은 상품을 동시에 처음 정할 때
     * 둘 다 INSERT 하다 유니크 키(store_id, product_id) 위반으로 500이 나므로 한 문장으로 한다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO safety_stock (store_id, product_id, quantity, created_at, updated_at)
            VALUES (:storeId, :productId, :quantity, :now, :now) AS new
            ON DUPLICATE KEY UPDATE quantity = new.quantity, updated_at = new.updated_at
            """, nativeQuery = true)
    void upsert(
            @Param("storeId") Long storeId,
            @Param("productId") Long productId,
            @Param("quantity") int quantity,
            @Param("now") LocalDateTime now
    );

    /** 점포의 상품별 안전재고. 정하지 않은 상품은 맵에 없다 */
    default Map<Long, Integer> quantitiesByProduct(Long storeId) {
        return findByStoreId(storeId).stream()
                .collect(Collectors.toMap(SafetyStock::getProductId, SafetyStock::getQuantity));
    }
}
