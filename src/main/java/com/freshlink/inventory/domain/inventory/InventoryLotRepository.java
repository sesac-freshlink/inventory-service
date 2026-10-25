package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
