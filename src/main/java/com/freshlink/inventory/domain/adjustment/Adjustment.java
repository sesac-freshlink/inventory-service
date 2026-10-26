package com.freshlink.inventory.domain.adjustment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 재고 수동 조정 한 건(S-14). 조정 전·뒤 수량을 같이 남겨 원장만 봐서는 모르는 "얼마에서 얼마로"를 보여 준다 */
@Entity
@Table(name = "adjustment")
public class Adjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(nullable = false)
    private Integer delta;

    @Column(name = "quantity_before", nullable = false)
    private Integer quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private Integer quantityAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdjustmentReason reason;

    @Column(length = 200)
    private String memo;

    @Column(name = "adjusted_by", nullable = false, length = 36)
    private String adjustedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Adjustment() {
    }

    public static Adjustment record(
            Long storeId,
            Long lotId,
            int quantityBefore,
            int quantityAfter,
            AdjustmentReason reason,
            String memo,
            String adjustedBy,
            LocalDateTime now
    ) {
        Adjustment adjustment = new Adjustment();
        adjustment.storeId = storeId;
        adjustment.lotId = lotId;
        adjustment.delta = quantityAfter - quantityBefore;
        adjustment.quantityBefore = quantityBefore;
        adjustment.quantityAfter = quantityAfter;
        adjustment.reason = reason;
        adjustment.memo = memo;
        adjustment.adjustedBy = adjustedBy;
        adjustment.createdAt = now;
        return adjustment;
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

    public Integer getDelta() {
        return delta;
    }

    public Integer getQuantityBefore() {
        return quantityBefore;
    }

    public Integer getQuantityAfter() {
        return quantityAfter;
    }

    public AdjustmentReason getReason() {
        return reason;
    }

    public String getMemo() {
        return memo;
    }

    public String getAdjustedBy() {
        return adjustedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
