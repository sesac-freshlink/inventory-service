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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(
        name = "inventory_lot",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_inventory_lot_store_product_expiry",
                        columnNames = {"store_id", "product_id", "expiry_date"}
                )
        }
)
@EntityListeners(AuditingEntityListener.class)
public class InventoryLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InventoryLotStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected InventoryLot() {
    }

    public static InventoryLot create(
            Long storeId,
            Long productId,
            LocalDateTime expiryDate,
            int quantity
    ) {
        InventoryLot lot = new InventoryLot();
        lot.storeId = storeId;
        lot.productId = productId;
        lot.expiryDate = expiryDate;
        lot.quantity = quantity;
        lot.reservedQuantity = 0;
        lot.status = InventoryLotStatus.NORMAL;
        return lot;
    }

    public void addQuantity(int quantity) {
        this.quantity += quantity;
    }

    /** 구매 가능 수량(실물 − 예약). 경과 여부는 따지지 않는다 */
    public int availableQuantity() {
        return quantity - reservedQuantity;
    }

    /** 결제 대기 수량을 잡는다. chk_lot_reserved(reserved <= quantity)를 넘지 않게 먼저 확인한다 */
    public void reserve(int quantity) {
        if (quantity < 1 || quantity > availableQuantity()) {
            throw new IllegalStateException(
                    "예약 수량이 구매 가능 수량을 넘는다: lotId=" + id
                            + ", quantity=" + quantity
                            + ", available=" + availableQuantity()
            );
        }
        this.reservedQuantity += quantity;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getProductId() {
        return productId;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getReservedQuantity() {
        return reservedQuantity;
    }

    public InventoryLotStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
