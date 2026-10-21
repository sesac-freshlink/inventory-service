package com.freshlink.inventory;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class InventoryCommandServiceTest {

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void inboundCreatesLotLedgerAndInboundRecord() {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """,
                "P901",
                "WBS6 테스트 상품",
                "테스트",
                1500L
        );

        Long productId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM product
                WHERE product_code = ?
                """,
                Long.class,
                "P901"
        );

        InboundMessage message = new InboundMessage(
                "00000000-0000-0000-0000-000000000901",
                OffsetDateTime.parse("2026-10-21T11:00:00+09:00"),
                901L,
                "SHP-WBS6-901",
                List.of(
                        new InboundItem(
                                "P901",
                                10,
                                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
                        )
                )
        );

        inventoryCommandService.handle(message);

        Integer lotQuantity = jdbcTemplate.queryForObject("""
                SELECT quantity
                FROM inventory_lot
                WHERE store_id = ?
                  AND product_id = ?
                """,
                Integer.class,
                901L,
                productId
        );

        Integer ledgerQuantity = jdbcTemplate.queryForObject("""
                SELECT quantity
                FROM ledger
                WHERE store_id = ?
                  AND ref_id = ?
                """,
                Integer.class,
                901L,
                "SHP-WBS6-901"
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM inbound
                WHERE shipment_id = ?
                """,
                Integer.class,
                "SHP-WBS6-901"
        );

        Integer processedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_message
                WHERE message_id = ?
                """,
                Integer.class,
                message.messageId()
        );

        assertThat(lotQuantity).isEqualTo(10);
        assertThat(ledgerQuantity).isEqualTo(10);
        assertThat(inboundCount).isEqualTo(1);
        assertThat(processedCount).isEqualTo(1);
    }

    @Test
    void sameMessageIdIsAppliedOnlyOnce() {
        jdbcTemplate.update("""
            INSERT INTO product (product_code, name, category, price)
            VALUES (?, ?, ?, ?)
            """,
                "P902",
                "WBS6 멱등 테스트 상품",
                "테스트",
                2000L
        );

        Long productId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM product
            WHERE product_code = ?
            """,
                Long.class,
                "P902"
        );

        InboundMessage message = new InboundMessage(
                "00000000-0000-0000-0000-000000000902",
                OffsetDateTime.parse("2026-10-21T12:00:00+09:00"),
                902L,
                "SHP-WBS6-902",
                List.of(
                        new InboundItem(
                                "P902",
                                10,
                                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
                        )
                )
        );

        inventoryCommandService.handle(message);
        inventoryCommandService.handle(message);

        Integer lotQuantity = jdbcTemplate.queryForObject("""
            SELECT quantity
            FROM inventory_lot
            WHERE store_id = ?
              AND product_id = ?
            """,
                Integer.class,
                902L,
                productId
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
              AND ref_id = ?
            """,
                Integer.class,
                902L,
                "SHP-WBS6-902"
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id = ?
            """,
                Integer.class,
                "SHP-WBS6-902"
        );

        Integer processedCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM processed_message
            WHERE message_id = ?
            """,
                Integer.class,
                message.messageId()
        );

        assertThat(lotQuantity).isEqualTo(10);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(inboundCount).isEqualTo(1);
        assertThat(processedCount).isEqualTo(1);
    }

    @Test
    void sameShipmentIdWithDifferentMessageIdIsAppliedOnlyOnce() {
        jdbcTemplate.update("""
            INSERT INTO product (product_code, name, category, price)
            VALUES (?, ?, ?, ?)
            """,
                "P903",
                "WBS6 shipment 멱등 테스트 상품",
                "테스트",
                2500L
        );

        Long productId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM product
            WHERE product_code = ?
            """,
                Long.class,
                "P903"
        );

        InboundItem item = new InboundItem(
                "P903",
                10,
                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
        );

        InboundMessage first = new InboundMessage(
                "00000000-0000-0000-0000-000000000903",
                OffsetDateTime.parse("2026-10-21T12:00:00+09:00"),
                903L,
                "SHP-WBS6-903",
                List.of(item)
        );

        InboundMessage second = new InboundMessage(
                "00000000-0000-0000-0000-000000000904",
                OffsetDateTime.parse("2026-10-21T12:01:00+09:00"),
                903L,
                "SHP-WBS6-903",
                List.of(item)
        );

        inventoryCommandService.handle(first);
        inventoryCommandService.handle(second);

        Integer lotQuantity = jdbcTemplate.queryForObject("""
            SELECT quantity
            FROM inventory_lot
            WHERE store_id = ?
              AND product_id = ?
            """,
                Integer.class,
                903L,
                productId
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
              AND ref_id = ?
            """,
                Integer.class,
                903L,
                "SHP-WBS6-903"
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id = ?
            """,
                Integer.class,
                "SHP-WBS6-903"
        );

        Integer processedCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM processed_message
            WHERE message_id IN (?, ?)
            """,
                Integer.class,
                first.messageId(),
                second.messageId()
        );

        assertThat(lotQuantity).isEqualTo(10);
        assertThat(ledgerCount).isEqualTo(1);
        assertThat(inboundCount).isEqualTo(1);
        assertThat(processedCount).isEqualTo(2);
    }

    @Test
    void unknownProductRollsBackEntireTransaction() {
        InboundMessage message = new InboundMessage(
                "00000000-0000-0000-0000-000000000905",
                OffsetDateTime.parse("2026-10-21T13:00:00+09:00"),
                905L,
                "SHP-WBS6-905",
                List.of(
                        new InboundItem(
                                "UNKNOWN-PRODUCT-905",
                                10,
                                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
                        )
                )
        );

        assertThatThrownBy(() -> inventoryCommandService.handle(message))
                .isInstanceOf(BusinessException.class);

        Integer processedCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM processed_message
            WHERE message_id = ?
            """,
                Integer.class,
                message.messageId()
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id = ?
            """,
                Integer.class,
                message.shipmentId()
        );

        Integer lotCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inventory_lot
            WHERE store_id = ?
            """,
                Integer.class,
                message.storeId()
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
            """,
                Integer.class,
                message.storeId()
        );

        assertThat(processedCount).isZero();
        assertThat(inboundCount).isZero();
        assertThat(lotCount).isZero();
        assertThat(ledgerCount).isZero();
    }

    @Test
    void invalidQuantityRollsBackEntireTransaction() {
        jdbcTemplate.update("""
            INSERT INTO product (product_code, name, category, price)
            VALUES (?, ?, ?, ?)
            """,
                "P906",
                "WBS6 수량 검증 상품",
                "테스트",
                3000L
        );

        InboundMessage message = new InboundMessage(
                "00000000-0000-0000-0000-000000000906",
                OffsetDateTime.parse("2026-10-21T13:00:00+09:00"),
                906L,
                "SHP-WBS6-906",
                List.of(
                        new InboundItem(
                                "P906",
                                0,
                                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
                        )
                )
        );

        assertThatThrownBy(() -> inventoryCommandService.handle(message))
                .isInstanceOf(BusinessException.class);

        Integer processedCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM processed_message
            WHERE message_id = ?
            """,
                Integer.class,
                message.messageId()
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id = ?
            """,
                Integer.class,
                message.shipmentId()
        );

        Integer lotCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inventory_lot
            WHERE store_id = ?
            """,
                Integer.class,
                message.storeId()
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
            """,
                Integer.class,
                message.storeId()
        );

        assertThat(processedCount).isZero();
        assertThat(inboundCount).isZero();
        assertThat(lotCount).isZero();
        assertThat(ledgerCount).isZero();
    }

    @Test
    void inboundAddsQuantityToExistingLot() {
        jdbcTemplate.update("""
            INSERT INTO product (product_code, name, category, price)
            VALUES (?, ?, ?, ?)
            """,
                "P907",
                "WBS6 기존 lot 누적 상품",
                "테스트",
                3500L
        );

        Long productId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM product
            WHERE product_code = ?
            """,
                Long.class,
                "P907"
        );

        OffsetDateTime expiryDate =
                OffsetDateTime.parse("2026-10-30T23:59:59+09:00");

        InboundMessage first = new InboundMessage(
                "00000000-0000-0000-0000-000000000907",
                OffsetDateTime.parse("2026-10-21T13:00:00+09:00"),
                907L,
                "SHP-WBS6-907-A",
                List.of(new InboundItem("P907", 10, expiryDate))
        );

        InboundMessage second = new InboundMessage(
                "00000000-0000-0000-0000-000000000908",
                OffsetDateTime.parse("2026-10-21T13:01:00+09:00"),
                907L,
                "SHP-WBS6-907-B",
                List.of(new InboundItem("P907", 7, expiryDate))
        );

        inventoryCommandService.handle(first);
        inventoryCommandService.handle(second);

        Integer lotCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inventory_lot
            WHERE store_id = ?
              AND product_id = ?
            """,
                Integer.class,
                907L,
                productId
        );

        Integer lotQuantity = jdbcTemplate.queryForObject("""
            SELECT quantity
            FROM inventory_lot
            WHERE store_id = ?
              AND product_id = ?
            """,
                Integer.class,
                907L,
                productId
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id IN (?, ?)
            """,
                Integer.class,
                first.shipmentId(),
                second.shipmentId()
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
              AND ref_id IN (?, ?)
            """,
                Integer.class,
                907L,
                first.shipmentId(),
                second.shipmentId()
        );

        assertThat(lotCount).isEqualTo(1);
        assertThat(lotQuantity).isEqualTo(17);
        assertThat(inboundCount).isEqualTo(2);
        assertThat(ledgerCount).isEqualTo(2);
    }

    @Test
    void failureAfterPartialProcessingRollsBackLotAndLedger() {
        jdbcTemplate.update("""
            INSERT INTO product (product_code, name, category, price)
            VALUES (?, ?, ?, ?)
            """,
                "P908",
                "WBS6 부분 처리 롤백 상품",
                "테스트",
                4000L
        );

        Long productId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM product
            WHERE product_code = ?
            """,
                Long.class,
                "P908"
        );

        InboundMessage message = new InboundMessage(
                "00000000-0000-0000-0000-000000000909",
                OffsetDateTime.parse("2026-10-21T14:00:00+09:00"),
                908L,
                "SHP-WBS6-908",
                List.of(
                        new InboundItem(
                                "P908",
                                10,
                                OffsetDateTime.parse("2026-10-30T23:59:59+09:00")
                        ),
                        new InboundItem(
                                "UNKNOWN-PRODUCT-908",
                                5,
                                OffsetDateTime.parse("2026-10-31T23:59:59+09:00")
                        )
                )
        );

        assertThatThrownBy(() -> inventoryCommandService.handle(message))
                .isInstanceOf(BusinessException.class);

        Integer processedCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM processed_message
            WHERE message_id = ?
            """,
                Integer.class,
                message.messageId()
        );

        Integer lotCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inventory_lot
            WHERE store_id = ?
              AND product_id = ?
            """,
                Integer.class,
                message.storeId(),
                productId
        );

        Integer ledgerCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ledger
            WHERE store_id = ?
              AND ref_id = ?
            """,
                Integer.class,
                message.storeId(),
                message.shipmentId()
        );

        Integer inboundCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM inbound
            WHERE shipment_id = ?
            """,
                Integer.class,
                message.shipmentId()
        );

        assertThat(processedCount).isZero();
        assertThat(lotCount).isZero();
        assertThat(ledgerCount).isZero();
        assertThat(inboundCount).isZero();
    }
}