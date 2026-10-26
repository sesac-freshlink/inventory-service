package com.freshlink.inventory.domain.adjustment;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdjustmentRepository extends JpaRepository<Adjustment, Long> {

    /** 점포의 최근 조정. idx_adjustment_store_created 인덱스를 탄다 */
    @Query("""
            select adjustment
            from Adjustment adjustment
            where adjustment.storeId = :storeId
            order by adjustment.createdAt desc, adjustment.id desc
            """)
    List<Adjustment> findRecent(@Param("storeId") Long storeId, Pageable pageable);
}
