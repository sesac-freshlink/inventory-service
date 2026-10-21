package com.freshlink.inventory.domain.inbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "inbound")
public class Inbound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id", nullable = false, unique = true, length = 64)
    private String shipmentId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected Inbound() {
    }

    public static Inbound create(
            String shipmentId,
            Long storeId,
            LocalDateTime receivedAt
    ) {
        Inbound inbound = new Inbound();
        inbound.shipmentId = shipmentId;
        inbound.storeId = storeId;
        inbound.receivedAt = receivedAt;
        return inbound;
    }

    public Long getId() {
        return id;
    }

    public String getShipmentId() {
        return shipmentId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
