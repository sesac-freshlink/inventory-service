package com.freshlink.inventory.domain.storestock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 고객용 재고 조회 표(CQRS)의 한 행. 점포·상품별 구매 가능 수량을 미리 계산해 둔다.
 * 쓰기는 {@link StoreStockProjector}만 한다(upsert 한 문장). 고객 상품 목록(U-10)은 이 표만 읽는다.
 */
@Entity
@Table(name = "store_product_stock")
public class StoreProductStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    /** 그중 24시간 안에 끝나는 lot의 몫(마감세일). 기본 몫 = available − clearance */
    @Column(name = "clearance_quantity", nullable = false)
    private Integer clearanceQuantity;

    /** 24시간 밖 lot 중 남은 재고가 있는 가장 빠른 기한(기본 주문으로 먼저 받는 기한) */
    @Column(name = "normal_expiry")
    private LocalDateTime normalExpiry;

    /** 24시간 안 lot 중 남은 재고가 있는 가장 빠른 기한(마감세일로 먼저 받는 기한) */
    @Column(name = "clearance_expiry")
    private LocalDateTime clearanceExpiry;

    /**
     * 이 값이 맞는 마지막 시각. 남은 재고가 있는 lot이 기한을 넘는 시각과 24시간 안으로 들어오는(마감세일로 넘어가는) 시각 중
     * 가장 빠른 것. 남은 재고가 없으면 null
     */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected StoreProductStock() {
    }

    /**
     * 지금 고객에게 보여 줄 구매 가능 수량. 값이 맞는 시각이 지났으면 lot 하나가 경과된 것이라
     * 정리 작업이 다시 계산할 때까지 품절(0)로 본다. 실제보다 적게 보일 수는 있어도 많게 보이지는 않는다
     */
    public int availableAt(LocalDateTime now) {
        return availableAt(availableQuantity, validUntil, now);
    }

    /** 행이 없으면(수량 null) 0. 값이 맞는 시각이 없거나 지났으면 0 */
    static int availableAt(Integer availableQuantity, LocalDateTime validUntil, LocalDateTime now) {
        if (availableQuantity == null || validUntil == null || !validUntil.isAfter(now)) {
            return 0;
        }
        return availableQuantity;
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

    public Integer getAvailableQuantity() {
        return availableQuantity;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public Integer getClearanceQuantity() {
        return clearanceQuantity;
    }

    public LocalDateTime getNormalExpiry() {
        return normalExpiry;
    }

    public LocalDateTime getClearanceExpiry() {
        return clearanceExpiry;
    }

    /** 그중 마감세일 몫. 값이 맞는 시각이 지났으면 0 */
    public int clearanceAt(LocalDateTime now) {
        return availableAt(clearanceQuantity, validUntil, now);
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
