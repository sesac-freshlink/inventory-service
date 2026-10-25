package com.freshlink.inventory.domain.disposal;

public record DisposalResponse(
        Long disposalId,
        Long lotId,
        int quantity
) {
}
