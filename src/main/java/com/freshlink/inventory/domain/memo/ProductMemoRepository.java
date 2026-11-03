package com.freshlink.inventory.domain.memo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ProductMemoRepository extends JpaRepository<ProductMemo, Long> {

    /** 메모 탭. 최근에 고친 것부터 */
    List<ProductMemo> findByStoreIdOrderByUpdatedAtDescIdDesc(Long storeId);

    Optional<ProductMemo> findByStoreIdAndProductId(Long storeId, Long productId);

    /** S-10 재고 목록의 hasMemo */
    @Query("SELECT m.productId FROM ProductMemo m WHERE m.storeId = :storeId")
    Set<Long> findProductIdsByStoreId(@Param("storeId") Long storeId);
}
