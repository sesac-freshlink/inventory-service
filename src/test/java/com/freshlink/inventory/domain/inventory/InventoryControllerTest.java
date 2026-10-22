package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;
import java.util.List;
import java.time.LocalDate;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InventoryController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class InventoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InventoryQueryService inventoryQueryService;

    @MockitoBean
    LedgerQueryService ledgerQueryService;

    @Test
    void 재고_목록은_200과_no_store로_응답한다() throws Exception {
        InventorySummaryResponse response = new InventorySummaryResponse(
                15L,
                "P015",
                "매운 컵라면 115g",
                "라면",
                1700L,
                20,
                1,
                19,
                OffsetDateTime.parse("2026-10-20T23:59:59+09:00"),
                0,
                0
        );

        when(inventoryQueryService.getInventory(1L))
                .thenReturn(List.of(response));

        mockMvc.perform(
                        get("/owner/inventory")
                                .header("X-Store-Id", "1")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].productId").value(15))
                .andExpect(jsonPath("$[0].productCode").value("P015"))
                .andExpect(jsonPath("$[0].availableQuantity").value(19))
                .andExpect(jsonPath("$[0].nearestExpiry")
                        .value("2026-10-20T23:59:59+09:00"));
    }

    @Test
    void lot_목록은_200으로_응답한다() throws Exception {
        InventoryLotResponse response = new InventoryLotResponse(
                301L,
                OffsetDateTime.parse("2026-10-20T23:59:59+09:00"),
                20,
                1,
                19,
                InventoryLotStatus.NORMAL
        );

        when(inventoryQueryService.getLots(1L, 15L))
                .thenReturn(List.of(response));

        mockMvc.perform(
                        get("/owner/inventory/15/lots")
                                .header("X-Store-Id", "1")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].lotId").value(301))
                .andExpect(jsonPath("$[0].availableQuantity").value(19))
                .andExpect(jsonPath("$[0].status").value("NORMAL"));
    }

    @Test
    void 점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(get("/owner/inventory"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void 없는_상품의_lot_조회는_404다() throws Exception {
        when(inventoryQueryService.getLots(1L, 999L))
                .thenThrow(new BusinessException(
                        InventoryErrorCode.PRODUCT_NOT_FOUND
                ));

        mockMvc.perform(
                        get("/owner/inventory/999/lots")
                                .header("X-Store-Id", "1")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 원장_목록은_필터를_받아_200으로_응답한다() throws Exception {
        List<LedgerResponse> responses = List.of(
                new LedgerResponse(
                        9003L,
                        LedgerType.DISPOSE,
                        15L,
                        "매운 컵라면 115g",
                        301L,
                        OffsetDateTime.parse("2026-10-30T23:59:59+09:00"),
                        -3,
                        "DSP-001",
                        OffsetDateTime.parse("2026-10-22T10:02:00+09:00")
                ),
                new LedgerResponse(
                        9002L,
                        LedgerType.SALE,
                        15L,
                        "매운 컵라면 115g",
                        301L,
                        OffsetDateTime.parse("2026-10-30T23:59:59+09:00"),
                        -1,
                        "ORD-001",
                        OffsetDateTime.parse("2026-10-22T10:01:00+09:00")
                ),
                new LedgerResponse(
                        9001L,
                        LedgerType.INBOUND,
                        15L,
                        "매운 컵라면 115g",
                        301L,
                        OffsetDateTime.parse("2026-10-30T23:59:59+09:00"),
                        10,
                        "SHP-001",
                        OffsetDateTime.parse("2026-10-22T10:00:00+09:00")
                )
        );

        when(ledgerQueryService.getLedger(
                1L,
                15L,
                null,
                LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 22)
        )).thenReturn(responses);

        mockMvc.perform(
                        get("/owner/inventory/ledger")
                                .header("X-Store-Id", "1")
                                .param("productId", "15")
                                .param("from", "2026-10-22")
                                .param("to", "2026-10-22")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].type").value("DISPOSE"))
                .andExpect(jsonPath("$[0].quantity").value(-3))
                .andExpect(jsonPath("$[1].type").value("SALE"))
                .andExpect(jsonPath("$[1].quantity").value(-1))
                .andExpect(jsonPath("$[2].type").value("INBOUND"))
                .andExpect(jsonPath("$[2].quantity").value(10))
                .andExpect(jsonPath("$[2].productName")
                        .value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[2].expiryDate")
                        .value("2026-10-30T23:59:59+09:00"));
    }

    @Test
    void 원장_조회에서_시작일이_종료일보다_늦으면_400이다()
            throws Exception {

        when(ledgerQueryService.getLedger(
                1L,
                null,
                null,
                LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 10, 22)
        )).thenThrow(new BusinessException(
                InventoryErrorCode.INVALID_DATE_RANGE
        ));

        mockMvc.perform(
                        get("/owner/inventory/ledger")
                                .header("X-Store-Id", "1")
                                .param("from", "2026-10-23")
                                .param("to", "2026-10-22")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_DATE_RANGE"));
    }

    @Test
    void 임박_목록은_200과_no_store로_응답한다() throws Exception {
        List<ExpiringInventoryResponse> responses = List.of(
                new ExpiringInventoryResponse(
                        301L,
                        15L,
                        "매운 컵라면 115g",
                        OffsetDateTime.parse("2026-10-20T14:00:00+09:00"),
                        true,
                        10,
                        2,
                        8
                ),
                new ExpiringInventoryResponse(
                        302L,
                        15L,
                        "매운 컵라면 115g",
                        OffsetDateTime.parse("2026-10-20T16:00:00+09:00"),
                        false,
                        8,
                        3,
                        5
                )
        );

        when(inventoryQueryService.getExpiring(1L))
                .thenReturn(responses);

        mockMvc.perform(
                        get("/owner/inventory/expiring")
                                .header("X-Store-Id", "1")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].lotId").value(301))
                .andExpect(jsonPath("$[0].productId").value(15))
                .andExpect(jsonPath("$[0].productName")
                        .value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[0].expiryDate")
                        .value("2026-10-20T14:00:00+09:00"))
                .andExpect(jsonPath("$[0].expired").value(true))
                .andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[0].reservedQuantity").value(2))
                .andExpect(jsonPath("$[0].disposableQuantity").value(8))
                .andExpect(jsonPath("$[1].expired").value(false))
                .andExpect(jsonPath("$[1].disposableQuantity").value(5));
    }
}