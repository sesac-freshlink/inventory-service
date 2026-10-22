package com.freshlink.inventory;

import com.freshlink.inventory.domain.inventory.Ledger;
import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.inventory.LedgerType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class LedgerRepositoryTest {

    @Autowired
    LedgerRepository ledgerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 점포의_최근_원장을_최신순으로_최대100건_조회한다() {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """,
                "P971",
                "WBS7 테스트 상품",
                "테스트",
                1500L
        );

        Long productId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM product
                WHERE product_code = ?
                """,
                Long.class,
                "P971"
        );

        jdbcTemplate.update("""
                INSERT INTO inventory_lot (
                    store_id,
                    product_id,
                    expiry_date,
                    quantity,
                    reserved_quantity,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                971L,
                productId,
                Timestamp.valueOf(
                        LocalDateTime.of(2026, 11, 1, 23, 59, 59)
                ),
                101,
                0,
                "NORMAL"
        );

        Long lotId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM inventory_lot
                WHERE store_id = ?
                  AND product_id = ?
                """,
                Long.class,
                971L,
                productId
        );

        LocalDateTime base =
                LocalDateTime.of(2026, 10, 22, 9, 0);

        for (int i = 0; i < 101; i++) {
            insertLedger(
                    971L,
                    lotId,
                    "INBOUND",
                    i + 1,
                    "REF-" + i,
                    base.plusSeconds(i)
            );
        }

        List<Ledger> result = ledgerRepository.findLedger(
                971L,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 100)
        );

        assertThat(result).hasSize(100);

        assertThat(result.getFirst().getRefId())
                .isEqualTo("REF-100");

        assertThat(result.getLast().getRefId())
                .isEqualTo("REF-1");

        assertThat(result)
                .extracting(Ledger::getRefId)
                .doesNotContain("REF-0");
    }

    @Test
    void 상품으로_원장을_필터링한다() {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?)
                """,
                "P972A", "상품 A", "테스트", 1000L,
                "P972B", "상품 B", "테스트", 2000L
        );

        Long productAId = jdbcTemplate.queryForObject("""
                SELECT id FROM product WHERE product_code = ?
                """,
                Long.class,
                "P972A"
        );

        Long productBId = jdbcTemplate.queryForObject("""
                SELECT id FROM product WHERE product_code = ?
                """,
                Long.class,
                "P972B"
        );

        jdbcTemplate.update("""
                INSERT INTO inventory_lot (
                    store_id, product_id, expiry_date,
                    quantity, reserved_quantity, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                972L,
                productAId,
                Timestamp.valueOf(
                        LocalDateTime.of(2026, 11, 1, 23, 59, 59)
                ),
                10,
                0,
                "NORMAL"
        );

        jdbcTemplate.update("""
                INSERT INTO inventory_lot (
                    store_id, product_id, expiry_date,
                    quantity, reserved_quantity, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                972L,
                productBId,
                Timestamp.valueOf(
                        LocalDateTime.of(2026, 11, 2, 23, 59, 59)
                ),
                20,
                0,
                "NORMAL"
        );

        Long lotAId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM inventory_lot
                WHERE store_id = ? AND product_id = ?
                """,
                Long.class,
                972L,
                productAId
        );

        Long lotBId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM inventory_lot
                WHERE store_id = ? AND product_id = ?
                """,
                Long.class,
                972L,
                productBId
        );

        insertLedger(
                972L,
                lotAId,
                "INBOUND",
                10,
                "PRODUCT-A",
                LocalDateTime.of(2026, 10, 22, 10, 0)
        );

        insertLedger(
                972L,
                lotBId,
                "INBOUND",
                20,
                "PRODUCT-B",
                LocalDateTime.of(2026, 10, 22, 10, 1)
        );

        List<Ledger> result = ledgerRepository.findLedger(
                972L,
                productAId,
                null,
                null,
                null,
                PageRequest.of(0, 100)
        );

        assertThat(result)
                .extracting(Ledger::getRefId)
                .containsExactly("PRODUCT-A");
    }

    @Test
    void 원장_유형으로_필터링한다() {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """,
                "P973",
                "원장 유형 테스트 상품",
                "테스트",
                1500L
        );

        Long productId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM product
                WHERE product_code = ?
                """,
                Long.class,
                "P973"
        );

        jdbcTemplate.update("""
                INSERT INTO inventory_lot (
                    store_id, product_id, expiry_date,
                    quantity, reserved_quantity, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                973L,
                productId,
                Timestamp.valueOf(
                        LocalDateTime.of(2026, 11, 1, 23, 59, 59)
                ),
                10,
                0,
                "NORMAL"
        );

        Long lotId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM inventory_lot
                WHERE store_id = ? AND product_id = ?
                """,
                Long.class,
                973L,
                productId
        );

        insertLedger(
                973L,
                lotId,
                "INBOUND",
                10,
                "TYPE-INBOUND",
                LocalDateTime.of(2026, 10, 22, 10, 0)
        );

        insertLedger(
                973L,
                lotId,
                "SALE",
                -1,
                "TYPE-SALE",
                LocalDateTime.of(2026, 10, 22, 10, 1)
        );

        insertLedger(
                973L,
                lotId,
                "DISPOSE",
                -3,
                "TYPE-DISPOSE",
                LocalDateTime.of(2026, 10, 22, 10, 2)
        );

        List<Ledger> result = ledgerRepository.findLedger(
                973L,
                null,
                LedgerType.SALE,
                null,
                null,
                PageRequest.of(0, 100)
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getType())
                .isEqualTo(LedgerType.SALE);
        assertThat(result.getFirst().getQuantity())
                .isEqualTo(-1);
        assertThat(result.getFirst().getRefId())
                .isEqualTo("TYPE-SALE");
    }

    @Test
    void 날짜_범위로_원장을_필터링한다() {
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES (?, ?, ?, ?)
                """,
                "P974",
                "날짜 필터 테스트 상품",
                "테스트",
                1500L
        );

        Long productId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM product
                WHERE product_code = ?
                """,
                Long.class,
                "P974"
        );

        jdbcTemplate.update("""
                INSERT INTO inventory_lot (
                    store_id, product_id, expiry_date,
                    quantity, reserved_quantity, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                974L,
                productId,
                Timestamp.valueOf(
                        LocalDateTime.of(2026, 11, 1, 23, 59, 59)
                ),
                10,
                0,
                "NORMAL"
        );

        Long lotId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM inventory_lot
                WHERE store_id = ? AND product_id = ?
                """,
                Long.class,
                974L,
                productId
        );

        insertLedger(
                974L,
                lotId,
                "INBOUND",
                10,
                "BEFORE",
                LocalDateTime.of(
                        2026, 10, 21,
                        23, 59, 59, 999_000_000
                )
        );

        insertLedger(
                974L,
                lotId,
                "SALE",
                -1,
                "START",
                LocalDateTime.of(2026, 10, 22, 0, 0)
        );

        insertLedger(
                974L,
                lotId,
                "DISPOSE",
                -3,
                "END",
                LocalDateTime.of(
                        2026, 10, 22,
                        23, 59, 59, 999_000_000
                )
        );

        insertLedger(
                974L,
                lotId,
                "INBOUND",
                5,
                "AFTER",
                LocalDateTime.of(2026, 10, 23, 0, 0)
        );

        List<Ledger> result = ledgerRepository.findLedger(
                974L,
                null,
                null,
                LocalDateTime.of(2026, 10, 22, 0, 0),
                LocalDateTime.of(2026, 10, 23, 0, 0),
                PageRequest.of(0, 100)
        );

        assertThat(result)
                .extracting(Ledger::getRefId)
                .containsExactly(
                        "END",
                        "START"
                );
    }

    private void insertLedger(
            Long storeId,
            Long lotId,
            String type,
            int quantity,
            String refId,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO ledger (
                    store_id,
                    lot_id,
                    type,
                    quantity,
                    ref_id,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                ps -> {
                    ps.setLong(1, storeId);
                    ps.setLong(2, lotId);
                    ps.setString(3, type);
                    ps.setInt(4, quantity);
                    ps.setString(5, refId);
                    ps.setObject(6, createdAt);
                }
        );
    }
}