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

    /** 결제 확정. 예약했던 수량만큼 실물과 예약을 같이 줄인다. 예약 뒤 기한이 지났어도 그대로 확정한다 */
    public void confirm(int quantity) {
        checkReserved(quantity);
        this.quantity -= quantity;
        this.reservedQuantity -= quantity;
    }

    /** 예약 해제. 실물은 그대로 두고 예약만 되돌린다 */
    public void release(int quantity) {
        checkReserved(quantity);
        this.reservedQuantity -= quantity;
    }

    private void checkReserved(int quantity) {
        if (quantity < 1 || quantity > reservedQuantity) {
            throw new IllegalStateException(
                    "예약 수량보다 많이 확정·해제하려 한다: lotId=" + id
                            + ", quantity=" + quantity
                            + ", reserved=" + reservedQuantity
            );
        }
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
