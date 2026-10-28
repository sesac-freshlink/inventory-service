package com.freshlink.inventory.domain.inbound;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundRepository extends JpaRepository<Inbound, Long> {

    boolean existsByShipmentId(String shipmentId);
}
