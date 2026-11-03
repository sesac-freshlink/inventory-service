package com.freshlink.inventory.domain.memo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * 점주 상품 메모. 점포·상품마다 하나이고 게시글처럼 자유롭게 고친다(댓글처럼 쌓지 않는다).
 * 빈 메모를 미리 만들지 않고 처음 저장할 때 행이 생긴다. 지우면 행도 없앤다.
 */
@Entity
@Table(name = "product_memo")
public class ProductMemo {

    /** 화면 입력과 DB 컬럼 길이의 상한 */
    public static final int MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, length = MAX_LENGTH)
    private String content;

    /**
     * 낙관적 락. 수동 조정(S-14)의 lot version과 같은 방식이다. 화면은 읽은 version을 들고 저장하고,
     * 그 사이 다른 화면(포스기·휴대폰)이 먼저 고쳤으면 409로 알려 말없이 덮어쓰지 않게 한다
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductMemo() {
    }

    public static ProductMemo create(Long storeId, Long productId, String content, LocalDateTime now) {
        ProductMemo memo = new ProductMemo();
        memo.storeId = storeId;
        memo.productId = productId;
        memo.content = content;
        memo.createdAt = now;
        memo.updatedAt = now;
        return memo;
    }

    public void edit(String content, LocalDateTime now) {
        this.content = content;
        this.updatedAt = now;
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

    public String getContent() {
        return content;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
