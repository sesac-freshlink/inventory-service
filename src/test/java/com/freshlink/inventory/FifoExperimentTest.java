package com.freshlink.inventory;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "FIFO가 없으면 깨진다" 실험 (E-01). 재고 10개 lot 하나에 1개짜리 예약 20건을 넣는다. 맞는 답은 성공 10, 실패 10이다.
 * 같은 점포 쪽지를 FIFO 그룹(storeId)이 한 줄로 세우지 않고 동시에 처리하면 어떻게 되는지 네 가지로 비교한다.
 *
 * <ol>
 *   <li>락 없이 읽고-계산하고-쓰기: 갱신 손실. 20건 모두 "성공"인데 예약 수량은 1</li>
 *   <li>같은 상황에서 쓰기만 {@code reserved = reserved + 1}: CHECK 제약(chk_lot_reserved)이 11번째부터 막는다</li>
 *   <li>실제 handle() 8건을 동시에(모두 lot을 읽은 뒤 처리): inventory_lot.version(낙관적 락) 때문에 먼저 커밋한 1건만 반영되고
 *       7건은 통째로 되돌려진다. 다시 처리하면 맞는 답</li>
 *   <li>실제 handle()을 하나씩 (= FIFO): 예외 없이 맞는 답</li>
 * </ol>
 * 1~3은 모든 스레드가 읽은 뒤에 쓰도록 CyclicBarrier로 맞춰서 결과가 매번 같다.
 * 결과 표와 설명은 docs/동시성-설계-FIFO와-낙관적-락.md. 점포 4601~4699, 상품 P46xx, 주문 46xxx를 쓴다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class FifoExperimentTest {

    private static final Logger log = LoggerFactory.getLogger(FifoExperimentTest.class);

    static final int STOCK = 10;
    static final int ORDERS = 20;
    /** 실험 3의 동시 트랜잭션 수. 트랜잭션마다 DB 연결을 쥐고 기다리므로 연결 풀(10)보다 작게 둔다 */
    static final int CONCURRENT = 8;
    static final String NORMAL = "2027-12-31T23:59:59+09:00";

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InventoryLotRepository inventoryLotRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void 락_없이_동시에_읽고_쓰면_갱신이_사라져_재고보다_많이_판다() throws Exception {
        long lotId = lotWithStock(4601L, "P4601");
        CyclicBarrier allRead = new CyclicBarrier(ORDERS);
        AtomicInteger accepted = new AtomicInteger();

        runConcurrently(ORDERS, i -> {
            Map<String, Object> lot = readLot(lotId);
            int quantity = (int) lot.get("quantity");
            int reserved = (int) lot.get("reserved_quantity");
            allRead.await();  // 모두 읽은 뒤에 쓴다: 순서 보장이 없을 때 생길 수 있는 가장 나쁜 겹침
            if (quantity - reserved >= 1) {
                jdbcTemplate.update(
                        "UPDATE inventory_lot SET reserved_quantity = ? WHERE id = ?", reserved + 1, lotId);
                accepted.incrementAndGet();
            }
        });

        int reserved = reservedQuantity(lotId);
        log.info("[실험 1] 락 없음·절대값 쓰기: 성공 응답 {}건, 실제 예약 수량 {}", accepted.get(), reserved);
        assertThat(accepted.get()).isEqualTo(ORDERS);  // 재고 10개인데 20건 모두 성공했다고 답했다
        assertThat(reserved).isEqualTo(1);              // 20번 쓴 값이 전부 "0 + 1"이라 19건이 사라졌다
    }

    @Test
    void 쓰기를_더하기로_바꾸면_CHECK_제약이_재고를_넘는_예약을_막는다() throws Exception {
        long lotId = lotWithStock(4602L, "P4602");
        CyclicBarrier allRead = new CyclicBarrier(ORDERS);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedByCheck = new AtomicInteger();

        runConcurrently(ORDERS, i -> {
            Map<String, Object> lot = readLot(lotId);
            int available = (int) lot.get("quantity") - (int) lot.get("reserved_quantity");
            allRead.await();
            if (available >= 1) {  // 20건 모두 "10개 남았다"를 보고 통과한다
                try {
                    jdbcTemplate.update(
                            "UPDATE inventory_lot SET reserved_quantity = reserved_quantity + 1 WHERE id = ?", lotId);
                    accepted.incrementAndGet();
                } catch (DataAccessException e) {
                    assertThat(e.getMostSpecificCause().getMessage()).contains("chk_lot_reserved");
                    rejectedByCheck.incrementAndGet();
                }
            }
        });

        log.info("[실험 2] 락 없음·더하기 쓰기: 성공 {}건, CHECK 제약 오류 {}건, 예약 수량 {}",
                accepted.get(), rejectedByCheck.get(), reservedQuantity(lotId));
        assertThat(accepted.get()).isEqualTo(STOCK);
        assertThat(rejectedByCheck.get()).isEqualTo(ORDERS - STOCK);
        assertThat(reservedQuantity(lotId)).isEqualTo(STOCK);
    }

    @Test
    void 실제_처리를_동시에_돌리면_낙관적_락이_겹친_쪽지를_되돌리고_다시_처리하면_맞는다() throws Exception {
        long storeId = 4603L;
        long lotId = lotWithStock(storeId, "P4603");
        List<ReserveMessage> messages = reserves(46_300L, storeId, productId(lotId));
        CyclicBarrier allRead = new CyclicBarrier(CONCURRENT);
        Queue<ReserveMessage> rolledBack = new ConcurrentLinkedQueue<>();
        Queue<String> errors = new ConcurrentLinkedQueue<>();

        // 앞 8건을 동시에. 각 쪽지 트랜잭션이 lot을 먼저 읽고(version n) 모두 읽은 뒤에 실제 handle()을 돈다
        runConcurrently(CONCURRENT, i -> {
            ReserveMessage message = messages.get(i);
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    inventoryLotRepository.findById(lotId).orElseThrow();
                    await(allRead);
                    inventoryCommandService.handle(message);
                });
            } catch (RuntimeException e) {
                // 운영에서는 리스너가 이 예외를 그대로 던지고 SQS가 같은 쪽지를 다시 보낸다
                rolledBack.add(message);
                errors.add(e.getClass().getSimpleName());
            }
        });

        int firstPassReserved = reservedQuantity(lotId);
        int firstPassReservations = reservationSum(46_300L);
        log.info("[실험 3] 실제 handle() 동시 {}건: 먼저 커밋한 {}건만 반영, 되돌린 쪽지 {}건 {}",
                CONCURRENT, firstPassReserved, rolledBack.size(), summarize(errors));
        // 모두 version n으로 UPDATE ... WHERE version = n을 보내므로 먼저 커밋한 1건만 맞고 나머지는 0행
        String diagnosis = "되돌린 쪽지 " + summarize(errors) + ", 예약 표 " + firstPassReservations;
        assertThat(firstPassReserved).as(diagnosis).isEqualTo(1);
        assertThat(firstPassReservations).as(diagnosis).isEqualTo(1);
        assertThat(rolledBack).as(diagnosis).hasSize(CONCURRENT - 1);
        assertThat(errors).allMatch(name -> name.contains("OptimisticLocking"));

        // 되돌려진 쪽지는 processed_message 기록도 없다. SQS 재전송처럼 다시 처리하고, 남은 12건도 처리한다
        rolledBack.forEach(inventoryCommandService::handle);
        messages.subList(CONCURRENT, ORDERS).forEach(inventoryCommandService::handle);

        assertResult(storeId, 46_300L);
    }

    @Test
    void 같은_점포_쪽지를_하나씩_처리하면_예외_없이_맞는다() {
        long storeId = 4604L;
        long productId = productId(lotWithStock(storeId, "P4604"));

        for (ReserveMessage message : reserves(46_400L, storeId, productId)) {
            inventoryCommandService.handle(message);  // FIFO 그룹 안에서는 이렇게 한 건씩 처리된다
        }

        log.info("[실험 4] 하나씩 처리: 예약 {}", reservedQuantity(storeLot(storeId)));
        assertResult(storeId, 46_400L);
    }

    /** 맞는 답: 성공(RESERVED) 10, 실패(RESERVE_FAILED) 10, lot 예약 수량 10 = 예약 표 합계 */
    private void assertResult(long storeId, long orderFrom) {
        assertThat(reservedQuantity(storeLot(storeId))).isEqualTo(STOCK);
        assertThat(reservationSum(orderFrom)).isEqualTo(STOCK);
        assertThat(resultCount(orderFrom, "RESERVED")).isEqualTo(STOCK);
        assertThat(resultCount(orderFrom, "RESERVE_FAILED")).isEqualTo(ORDERS - STOCK);
    }

    @FunctionalInterface
    interface Task {
        void run(int index) throws Exception;
    }

    /** 스레드 n개를 한꺼번에 출발시키고 모두 끝날 때까지 기다린다. 예상하지 못한 예외는 테스트 실패로 올린다 */
    private void runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 트랜잭션 콜백 안에서 쓰려고 검사 예외를 풀어 준다. 30초 안에 다 모이지 않으면 실패 */
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("스레드가 다 모이지 않았다", e);
        }
    }

    private List<ReserveMessage> reserves(long orderFrom, long storeId, long productId) {
        return IntStream.range(0, ORDERS)
                .mapToObj(i -> new ReserveMessage(
                        UUID.randomUUID().toString(), NOW, orderFrom + i, storeId, productId, 1))
                .toList();
    }

    private long lotWithStock(long storeId, String productCode) {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """, productCode, "실험 " + productCode, "테스트", 1500L);
        inventoryCommandService.handle(new InboundMessage(
                UUID.randomUUID().toString(),
                NOW,
                storeId,
                "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, STOCK, OffsetDateTime.parse(NORMAL)))
        ));
        return storeLot(storeId);
    }

    private long storeLot(long storeId) {
        return jdbcTemplate.queryForObject("SELECT id FROM inventory_lot WHERE store_id = ?", Long.class, storeId);
    }

    private long productId(long lotId) {
        return jdbcTemplate.queryForObject("SELECT product_id FROM inventory_lot WHERE id = ?", Long.class, lotId);
    }

    private Map<String, Object> readLot(long lotId) {
        return jdbcTemplate.queryForMap("SELECT quantity, reserved_quantity FROM inventory_lot WHERE id = ?", lotId);
    }

    private int reservedQuantity(long lotId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM inventory_lot WHERE id = ?", Integer.class, lotId);
    }

    private int reservationSum(long orderFrom) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM reservation
                WHERE order_id BETWEEN ? AND ? AND status = 'RESERVED'
                """, Integer.class, orderFrom, orderFrom + ORDERS - 1);
    }

    private int resultCount(long orderFrom, String messageType) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT JSON_EXTRACT(payload, '$.orderId')) FROM outbox
                WHERE message_type = ?
                  AND CAST(JSON_EXTRACT(payload, '$.orderId') AS UNSIGNED) BETWEEN ? AND ?
                """, Integer.class, messageType, orderFrom, orderFrom + ORDERS - 1);
    }

    private static String summarize(Queue<String> errors) {
        return errors.stream()
                .collect(java.util.stream.Collectors.groupingBy(name -> name, java.util.stream.Collectors.counting()))
                .toString();
    }
}
