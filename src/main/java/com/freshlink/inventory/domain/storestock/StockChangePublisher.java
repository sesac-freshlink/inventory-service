package com.freshlink.inventory.domain.storestock;

import com.freshlink.inventory.domain.clearance.ClearancePolicy;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.TreeSet;

/**
 * 재고를 바꾼 처리(입고·예약·확정·해제·폐기·수동 조정)가 끝에서 한 번 부른다.
 * 상품마다 lot을 다시 합산해 {@link StockChanged}를 낸다. 받는 곳은 {@link StoreStockProjector} 하나다.
 * 부른 쪽 트랜잭션에 그대로 들어가므로 재고 변경과 조회 표가 같이 커밋되거나 같이 되돌려진다.
 */
@Component
public class StockChangePublisher {

    /**
     * 구매 가능 수량은 InventoryCalculationService와 같은 규칙(미경과 lot의 수량 − 예약 수량 합)이다.
     * FOR SHARE(잠금 읽기)로 최신 커밋 값을 읽는다. 일반 SELECT는 트랜잭션이 처음 본 시점의 값을 읽어서,
     * 수동 조정과 쪽지 처리가 같은 상품의 다른 lot을 동시에 바꾸면 서로의 변경을 못 보고 낡은 합계로 덮어쓴다.
     * 잠금 읽기는 상대가 커밋할 때까지 기다렸다 읽는다
     */
    /**
     * 마감세일(ClearancePolicy): 24시간 안에 끝나는 lot의 몫(clearance)과 기본·마감세일 각각 먼저 받는 기한도 같이 센다.
     * 값이 맞는 시각(valid_until)은 lot이 기한을 넘는 시각과 lot이 24시간 안으로 들어오는 시각(기한 − 24시간) 중 빠른 쪽이다.
     * 파라미터: 지금, 지금 + 24시간 순서로 반복된다(아래 publish 참고)
     */
    private static final String SNAPSHOT_SQL = """
            SELECT COALESCE(SUM(CASE WHEN expiry_date > ? THEN quantity - reserved_quantity ELSE 0 END), 0)
                       AS available_quantity,
                   COALESCE(SUM(CASE WHEN expiry_date > ? AND expiry_date <= ? THEN quantity - reserved_quantity ELSE 0 END), 0)
                       AS clearance_quantity,
                   MIN(CASE WHEN expiry_date > ? AND quantity > reserved_quantity THEN expiry_date END)
                       AS normal_expiry,
                   MIN(CASE WHEN expiry_date > ? AND expiry_date <= ? AND quantity > reserved_quantity THEN expiry_date END)
                       AS clearance_expiry,
                   MIN(CASE WHEN expiry_date > ? AND quantity > reserved_quantity THEN expiry_date END)
                       AS earliest_expiry
            FROM inventory_lot
            WHERE store_id = ? AND product_id = ?
            FOR SHARE
            """;

    private final InventoryLotRepository inventoryLotRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StockChangePublisher(
            InventoryLotRepository inventoryLotRepository,
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher events,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void publish(Long storeId, Collection<Long> productIds) {
        // 이 트랜잭션에서 바꾼 lot이 아직 JPA 안에만 있을 수 있다. DB에 먼저 써야 합산에 들어간다.
        // 저장소를 거쳐 flush 해야 낙관적 락 충돌이 스프링 예외(OptimisticLockingFailureException)로 바뀐다
        inventoryLotRepository.flush();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowEnd = ClearancePolicy.windowEnd(now);

        // 여러 상품을 늘 같은 순서(상품 ID 오름차순)로 읽어 서로 기다리는 일(교착)을 줄인다
        for (Long productId : new TreeSet<>(productIds)) {
            StockChanged changed = jdbcTemplate.queryForObject(
                    SNAPSHOT_SQL,
                    (rs, rowNum) -> {
                        LocalDateTime normalExpiry = rs.getObject("normal_expiry", LocalDateTime.class);
                        LocalDateTime earliest = rs.getObject("earliest_expiry", LocalDateTime.class);
                        // 24시간 밖 lot은 (기한 − 24시간)에 마감세일로 넘어가므로 그때 값이 바뀐다
                        LocalDateTime entersClearance = normalExpiry == null ? null : normalExpiry.minus(ClearancePolicy.WINDOW);
                        return new StockChanged(
                                storeId,
                                productId,
                                rs.getInt("available_quantity"),
                                rs.getInt("clearance_quantity"),
                                normalExpiry,
                                rs.getObject("clearance_expiry", LocalDateTime.class),
                                earlier(earliest, entersClearance)
                        );
                    },
                    now, now, windowEnd, windowEnd, now, windowEnd, now, storeId, productId
            );
            events.publishEvent(changed);
        }
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
