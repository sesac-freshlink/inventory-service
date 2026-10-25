package com.freshlink.inventory;

import com.freshlink.inventory.domain.scheduler.LotStatusScheduler;
import com.freshlink.inventory.domain.scheduler.LotStatusService;
import com.freshlink.inventory.domain.scheduler.LotStatusService.LotStatusRefreshResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유통기한 스케줄러(E-09). 스케줄 트리거는 테스트하지 않고 서비스를 고정 Clock(2026-10-25 12:00 KST)으로 직접 부른다.
 * DB를 다른 테스트와 같이 쓰므로 먼저 한 번 갱신해 남의 lot을 정리한 뒤 이 테스트가 넣은 lot의 건수만 비교한다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class LotStatusTest {

    @Autowired
    LotStatusService lotStatusService;

    @Autowired
    LotStatusScheduler lotStatusScheduler;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void refreshMarksExpiredAndImminentLotsWithQuantity() {
        lotStatusService.refresh();

        long expired = insertLot(1, LocalDateTime.parse("2026-10-24T23:59:59"), 5, "NORMAL");
        long imminent = insertLot(2, LocalDateTime.parse("2026-10-25T23:59:59"), 5, "NORMAL");
        long normal = insertLot(3, LocalDateTime.parse("2027-12-31T23:59:59"), 5, "NORMAL");
        long emptyExpired = insertLot(4, LocalDateTime.parse("2026-10-24T23:59:59"), 0, "NORMAL");
        long alreadyImminent = insertLot(5, LocalDateTime.parse("2026-10-26T01:00:00"), 5, "IMMINENT");
        long imminentToExpired = insertLot(6, LocalDateTime.parse("2026-10-25T11:59:59"), 5, "IMMINENT");
        long exactlyNow = insertLot(7, LocalDateTime.parse("2026-10-25T12:00:00"), 5, "NORMAL");

        LotStatusRefreshResult result = lotStatusService.refresh();

        assertThat(result.expired()).isEqualTo(3);
        assertThat(result.imminent()).isEqualTo(1);
        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(status(imminentToExpired)).isEqualTo("EXPIRED");
        assertThat(status(exactlyNow)).isEqualTo("EXPIRED");
        assertThat(status(imminent)).isEqualTo("IMMINENT");
        assertThat(status(alreadyImminent)).isEqualTo("IMMINENT");
        assertThat(status(normal)).isEqualTo("NORMAL");
        assertThat(status(emptyExpired)).isEqualTo("NORMAL");

        LotStatusRefreshResult again = lotStatusService.refresh();

        assertThat(again.expired()).isZero();
        assertThat(again.imminent()).isZero();
    }

    @Test
    void schedulerTakesLotStatusJobLock() {
        lotStatusScheduler.refreshLotStatus();

        Integer lockCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = 'lotStatusJob'", Integer.class);
        assertThat(lockCount).isEqualTo(1);
    }

    private long insertLot(long productId, LocalDateTime expiryDate, int quantity, String status) {
        jdbcTemplate.update("""
                INSERT INTO inventory_lot
                    (store_id, product_id, expiry_date, quantity, reserved_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, NOW(3), NOW(3))
                """, 1801L, productId, expiryDate, quantity, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_lot WHERE store_id = ? AND product_id = ?",
                Long.class, 1801L, productId);
    }

    private String status(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM inventory_lot WHERE id = ?", String.class, lotId);
    }
}
