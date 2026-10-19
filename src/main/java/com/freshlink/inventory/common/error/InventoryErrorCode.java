package com.freshlink.inventory.common.error;

import com.freshlink.common.error.ErrorCode;

/**
 * inventory-service 오류 목록. 응답 code는 enum 이름이다.
 * 추가할 땐 여기에 한 줄만 넣는다: {@code XXX(404, "메시지")}. 핸들러는 안 건드린다.
 */
public enum InventoryErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND(404, "상품이 없습니다"),
    LOT_NOT_FOUND(404, "lot이 없습니다"),
    LOT_NOT_IN_STORE(403, "다른 점포의 lot입니다"),
    INVALID_QUANTITY(400, "수량이 올바르지 않습니다"),
    OUT_OF_STOCK(409, "재고가 부족합니다"),
    DISPOSAL_NOT_FOUND(404, "폐기 요청이 없습니다"),
    STORE_NOT_LINKED(403, "연결된 점포가 없습니다"),
    NOT_DISPOSABLE(409, "폐기할 수 없는 상태입니다"),
    SERVICE_UNAVAILABLE(503, "잠시 후 다시 시도해 주세요");

    private final int status;
    private final String message;

    InventoryErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
