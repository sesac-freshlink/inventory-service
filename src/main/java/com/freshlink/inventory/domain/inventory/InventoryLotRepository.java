package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, Long> {

    List<InventoryLot> findByStoreId(Long storeId);

    List<InventoryLot> findByStoreIdAndProductIdOrderByExpiryDateAscIdAsc(
            Long storeId,
            Long productId
    );

    /** 예약(FEFO)용. 기한이 지나지 않은 lot만 유통기한 빠른 순으로 */
    List<InventoryLot> findByStoreIdAndProductIdAndExpiryDateAfterOrderByExpiryDateAscIdAsc(
            Long storeId,
            Long productId,
            LocalDateTime now
    );

    Optional<InventoryLot> findByStoreIdAndProductIdAndExpiryDate(
            Long storeId,
            Long productId,
            LocalDateTime expiryDate
    );

    /**
     * 유통기한 달력. 기한이 [from, toExclusive) 안에 끝나고 수량이 남은 lot을 상품과 묶어 기한 → 상품명 순으로.
     * 점포·기한 조건은 idx_lot_store_expiry 인덱스를 탄다
     */
    @Query("""
            SELECT new com.freshlink.inventory.domain.inventory.ExpiryLotRow(
                l.id, p.id, p.productCode, p.name, l.expiryDate, l.quantity, l.reservedQuantity
            )
            FROM InventoryLot l
            JOIN Product p ON p.id = l.productId
            WHERE l.storeId = :storeId
              AND l.quantity > 0
              AND l.expiryDate >= :from
              AND l.expiryDate < :toExclusive
            ORDER BY l.expiryDate, p.name, l.id
            """)
    List<ExpiryLotRow> findExpiringBetween(
            @Param("storeId") Long storeId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );

    /** 스케줄러(E-09). 수량이 남은 경과 lot을 EXPIRED로. 바뀐 건수를 돌려준다 */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InventoryLot l
            SET l.status = :status, l.updatedAt = :now
            WHERE l.quantity > 0
              AND l.expiryDate <= :now
              AND l.status <> :status
            """)
    int markExpired(
            @Param("now") LocalDateTime now,
            @Param("status") InventoryLotStatus status
    );

    /** 스케줄러(E-09). 수량이 남은 24시간 이내 lot을 IMMINENT로. 바뀐 건수를 돌려준다 */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InventoryLot l
            SET l.status = :status, l.updatedAt = :now
            WHERE l.quantity > 0
              AND l.expiryDate > :now
              AND l.expiryDate <= :imminentUntil
              AND l.status <> :status
            """)
    int markImminent(
            @Param("now") LocalDateTime now,
            @Param("imminentUntil") LocalDateTime imminentUntil,
            @Param("status") InventoryLotStatus status
    );
}
