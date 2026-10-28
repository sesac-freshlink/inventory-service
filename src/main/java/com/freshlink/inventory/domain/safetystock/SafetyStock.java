package com.freshlink.inventory.domain.safetystock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 점포·상품별 안전재고(S-40). 구매 가능 수량이 이 값보다 적으면 S-10 재고 목록에서 부족으로 표시한다.
 * 행이 없는 상품은 {@link #DEFAULT_QUANTITY}를 쓴다. 저장은 {@link SafetyStockRepository#upsert} 한 문장으로 한다.
 */
@Entity
@Table(name = "safety_stock")
public class SafetyStock {

    /** 점주가 아직 정하지 않은 상품의 안전재고 */
    public static final int DEFAULT_QUANTITY = 5;

    /** 화면 입력과 DB CHECK 제약의 상한 */
    public static final int MAX_QUANTITY = 999;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SafetyStock() {
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

    public Integer getQuantity() {
        return quantity;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
