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

    /**
     * 스케줄러(E-09)가 EXPIRED로 바꿀 lot: 수량이 남았고 기한이 지났는데 아직 EXPIRED가 아닌 것.
     * 바뀌는 순간에 한 번만 알림을 보내려고 UPDATE 전에 먼저 읽는다. 상품이 없어도 상태는 바꾸므로 LEFT JOIN(상품명 null)
     */
    @Query("""
            SELECT new com.freshlink.inventory.domain.inventory.LotTransitionRow(
                l.id, l.storeId, l.productId, p.name, l.expiryDate, l.quantity
            )
            FROM InventoryLot l
            LEFT JOIN Product p ON p.id = l.productId
            WHERE l.quantity > 0
              AND l.expiryDate <= :now
              AND l.status <> com.freshlink.inventory.domain.inventory.InventoryLotStatus.EXPIRED
            ORDER BY l.id
            """)
    List<LotTransitionRow> findBecomingExpired(@Param("now") LocalDateTime now);

    /** 스케줄러(E-09)가 IMMINENT로 바꿀 lot: 수량이 남았고 24시간 안에 기한이 끝나는데 아직 IMMINENT가 아닌 것 */
    @Query("""
            SELECT new com.freshlink.inventory.domain.inventory.LotTransitionRow(
                l.id, l.storeId, l.productId, p.name, l.expiryDate, l.quantity
            )
            FROM InventoryLot l
            LEFT JOIN Product p ON p.id = l.productId
            WHERE l.quantity > 0
              AND l.expiryDate > :now
              AND l.expiryDate <= :imminentUntil
              AND l.status <> com.freshlink.inventory.domain.inventory.InventoryLotStatus.IMMINENT
            ORDER BY l.id
            """)
    List<LotTransitionRow> findBecomingImminent(
            @Param("now") LocalDateTime now,
            @Param("imminentUntil") LocalDateTime imminentUntil
    );

    /** 미리 읽은 lot들만 상태를 바꾼다. 읽은 뒤에 생긴 lot이 알림 없이 바뀌지 않게 id로 좁힌다 */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE InventoryLot l
            SET l.status = :status, l.updatedAt = :now
            WHERE l.id IN :ids
            """)
    int markStatus(
            @Param("ids") List<Long> ids,
            @Param("status") InventoryLotStatus status,
            @Param("now") LocalDateTime now
    );
}
