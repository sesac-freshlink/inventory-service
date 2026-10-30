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

    /** U-10 신상품. 점포에 상품별로 처음 입고된 시각(원장 INBOUND의 가장 이른 시각) */
    @Query("""
            select new com.freshlink.inventory.domain.inventory.ProductFirstInbound(lot.productId, min(ledger.createdAt))
            from Ledger ledger
            join InventoryLot lot on lot.id = ledger.lotId
            where ledger.storeId = :storeId
              and ledger.type = com.freshlink.inventory.domain.inventory.LedgerType.INBOUND
            group by lot.productId
            """)
    List<ProductFirstInbound> firstInboundByProduct(@Param("storeId") Long storeId);

    /** RETURN이 되돌릴 판매 기록, 그리고 이미 되돌렸는지 확인할 때 쓴다. ref_id = 주문 ID */
    List<Ledger> findByStoreIdAndTypeAndRefIdOrderByIdAsc(Long storeId, LedgerType type, String refId);

    boolean existsByStoreIdAndTypeAndRefId(Long storeId, LedgerType type, String refId);

    /**
     * S-42 기간 안의 원장을 상품·종류별로 더한다. 판매·폐기는 음수 그대로 더해진다.
     * 점포·기간 조건은 idx_ledger_store_created 인덱스를 탄다
     */
    @Query("""
            select new com.freshlink.inventory.domain.inventory.ProductLedgerSum(
                lot.productId, ledger.type, sum(ledger.quantity)
            )
            from Ledger ledger
            join InventoryLot lot on lot.id = ledger.lotId
            where ledger.storeId = :storeId
              and ledger.createdAt >= :from
              and ledger.createdAt < :toExclusive
            group by lot.productId, ledger.type
            """)
    List<ProductLedgerSum> sumByProductAndType(
            @Param("storeId") Long storeId,
            @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive
    );
}
