package com.freshlink.inventory.domain.inventory;

/** {@link InventoryCommandService#handle} 결과. 지표(inventory_command_seconds result 태그)에 쓴다 */
public enum CommandResult {

    /** 처음 받은 쪽지를 처리했다 (재고가 안 바뀐 RESERVE_FAILED·SKIPPED도 여기다) */
    PROCESSED,

    /** 같은 messageId를 이미 처리해서 건너뛰었다 */
    DUPLICATE
}
