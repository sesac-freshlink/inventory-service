package com.freshlink.inventory.common.messaging;

import com.freshlink.common.message.MessageJson;

import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import io.awspring.cloud.sqs.annotation.SqsListener;

import org.springframework.stereotype.Component;

@Component
public class InventoryCommandListener {

    private final InventoryCommandService inventoryCommandService;

    public InventoryCommandListener(
            InventoryCommandService inventoryCommandService
    ) {
        this.inventoryCommandService = inventoryCommandService;
    }

    @SqsListener("${app.queue.commands}")
    public void onCommand(String body) {
        inventoryCommandService.handle(
                MessageJson.fromJson(body)
        );
    }
}