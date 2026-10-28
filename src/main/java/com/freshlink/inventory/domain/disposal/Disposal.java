package com.freshlink.inventory.domain.disposal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDateTime;

@Entity
@Table(name = "disposal")
public class Disposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DisposalStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    protected Disposal() {
    }

    public static Disposal request(
            Long storeId,
            Long lotId,
            int quantity,
            String requestedBy,
            LocalDateTime now
    ) {
        Disposal disposal = new Disposal();
        disposal.storeId = storeId;
        disposal.lotId = lotId;
        disposal.quantity = quantity;
        disposal.requestedBy = requestedBy;
        disposal.status = DisposalStatus.REQUESTED;
        disposal.createdAt = now;
        return disposal;
    }

    public boolean isRequested() {
        return status == DisposalStatus.REQUESTED;
    }

    /** DISPOSE 쪽지 처리 완료 */
    public void done(LocalDateTime now) {
        changeFromRequested(DisposalStatus.DONE, now);
    }

    /** 처리 때 수량이 모자라 반영하지 않음 */
    public void skip(LocalDateTime now) {
        changeFromRequested(DisposalStatus.SKIPPED, now);
    }

    private void changeFromRequested(DisposalStatus next, LocalDateTime now) {
        if (status != DisposalStatus.REQUESTED) {
            throw new IllegalStateException(
                    "REQUESTED 폐기만 " + next + "로 바꿀 수 있다: disposalId=" + id + ", status=" + status
            );
        }
        this.status = next;
        this.processedAt = now;
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

    public Integer getQuantity() {
        return quantity;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public DisposalStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}