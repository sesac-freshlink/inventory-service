package com.freshlink.inventory.domain.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 주문의 예약을 잡은 순서(id)대로 읽는다. RESERVED 재전송 때 lots 순서가 처음과 같아야 한다 */
    List<Reservation> findByOrderIdOrderByIdAsc(Long orderId);
}
