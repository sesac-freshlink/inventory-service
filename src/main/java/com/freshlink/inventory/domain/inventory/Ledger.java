package com.freshlink.inventory.domain.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "ledger")
@EntityListeners(AuditingEntityListener.class)
public class Ledger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerType type;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "ref_id", length = 64)
    private String refId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Ledger() {
    }

    public static Ledger inbound(
            Long storeId,
            Long lotId,
            int quantity,
            String shipmentId
    ) {
        Ledger ledger = new Ledger();
        ledger.storeId = storeId;
        ledger.lotId = lotId;
        ledger.type = LedgerType.INBOUND;
        ledger.quantity = quantity;
        ledger.refId = shipmentId;
        return ledger;
    }

    /** 판매 확정. 수량은 음수로 저장한다(lot별 원장 합계 = lot 수량) */
    public static Ledger sale(
            Long storeId,
            Long lotId,
            int quantity,
            String orderId
    ) {
        Ledger ledger = new Ledger();
        ledger.storeId = storeId;
        ledger.lotId = lotId;
        ledger.type = LedgerType.SALE;
        ledger.quantity = -quantity;
        ledger.refId = orderId;
        return ledger;
    }

    /** 폐기. 수량은 음수로 저장한다 */
    public static Ledger dispose(
            Long storeId,
            Long lotId,
            int quantity,
            String disposalId
    ) {
        Ledger ledger = new Ledger();
        ledger.storeId = storeId;
        ledger.lotId = lotId;
        ledger.type = LedgerType.DISPOSE;
        ledger.quantity = -quantity;
        ledger.refId = disposalId;
        return ledger;
    }

    /** 환불 뒤 판매 복원. 판매를 되돌리므로 수량은 양수로 저장한다. refId는 판매와 같은 주문 ID */
    public static Ledger returned(
            Long storeId,
            Long lotId,
            int quantity,
            String orderId
    ) {
        Ledger ledger = new Ledger();
        ledger.storeId = storeId;
        ledger.lotId = lotId;
        ledger.type = LedgerType.RETURN;
        ledger.quantity = quantity;
        ledger.refId = orderId;
        return ledger;
    }

    /** 수동 조정. 수량은 증감 그대로 저장한다(늘리면 +, 줄이면 −) */
    public static Ledger adjust(
            Long storeId,
            Long lotId,
            int delta,
            String adjustmentId
    ) {
        Ledger ledger = new Ledger();
        ledger.storeId = storeId;
        ledger.lotId = lotId;
        ledger.type = LedgerType.ADJUST;
        ledger.quantity = delta;
        ledger.refId = adjustmentId;
        return ledger;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getLotId() {
        return lotId;
    }

    public LedgerType getType() {
        return type;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getRefId() {
        return refId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
