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

    Optional<InventoryLot> findByStoreIdAndProductIdAndExpiryDate(
            Long storeId,
            Long productId,
            LocalDateTime expiryDate
    );
}
