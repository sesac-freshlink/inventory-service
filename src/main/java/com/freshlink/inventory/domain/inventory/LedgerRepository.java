package com.freshlink.inventory.domain.inventory;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerRepository extends JpaRepository<Ledger, Long> {

    @Query("""
            select ledger
            from Ledger ledger
            where ledger.storeId = :storeId
              and (:type is null or ledger.type = :type)
              and (:from is null or ledger.createdAt >= :from)
              and (:toExclusive is null or ledger.createdAt < :toExclusive)
              and (
                    :productId is null
                    or ledger.lotId in (
                        select lot.id
                        from InventoryLot lot
                        where lot.productId = :productId
                    )
              )
            order by ledger.createdAt desc, ledger.id desc
            """)
    List<Ledger> findLedger(
            @Param("storeId") Long storeId,
            @Param("productId") Long productId,
            @Param("type") LedgerType type,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive,
            Pageable pageable
    );
}