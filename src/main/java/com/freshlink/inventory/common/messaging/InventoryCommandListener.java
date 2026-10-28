package com.freshlink.inventory.common.messaging;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;

import com.freshlink.inventory.common.metrics.CommandMetrics;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import io.awspring.cloud.sqs.annotation.SqsListener;

import org.springframework.stereotype.Component;

@Component
public class InventoryCommandListener {

    private final InventoryCommandService inventoryCommandService;
    private final CommandMetrics commandMetrics;

    public InventoryCommandListener(
            InventoryCommandService inventoryCommandService,
            CommandMetrics commandMetrics
    ) {
        this.inventoryCommandService = inventoryCommandService;
        this.commandMetrics = commandMetrics;
    }

    /**
     * 트랜잭션(handle) 바깥에서 시간을 잰다. 커밋 실패까지 failed로 잡힌다.
     * 예외는 그대로 던져 SQS가 다시 보내게 한다 (3번 실패하면 DLQ)
     */
    @SqsListener("${app.queue.commands}")
    public void onCommand(String body) {
        BaseMessage message;
        try {
            message = MessageJson.fromJson(body);
        } catch (RuntimeException e) {
            commandMetrics.recordUnreadable();
            throw e;
        }

        commandMetrics.record(
                message.type().name(),
                () -> inventoryCommandService.handle(message)
        );
    }
}
