package com.freshlink.inventory.domain.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(
        name = "reservation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_order_lot",
                        columnNames = {"order_id", "lot_id"}
                )
        }
)
@EntityListeners(AuditingEntityListener.class)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Reservation() {
    }

    public static Reservation reserve(Long orderId, Long lotId, int quantity) {
        Reservation reservation = new Reservation();
        reservation.orderId = orderId;
        reservation.lotId = lotId;
        reservation.quantity = quantity;
        reservation.status = ReservationStatus.RESERVED;
        return reservation;
    }

    public boolean isReserved() {
        return status == ReservationStatus.RESERVED;
    }

    public void confirm() {
        changeFromReserved(ReservationStatus.CONFIRMED);
    }

    public void release() {
        changeFromReserved(ReservationStatus.RELEASED);
    }

    private void changeFromReserved(ReservationStatus next) {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "RESERVED 예약만 " + next + "로 바꿀 수 있다: reservationId=" + id + ", status=" + status
            );
        }
        this.status = next;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getLotId() {
        return lotId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}