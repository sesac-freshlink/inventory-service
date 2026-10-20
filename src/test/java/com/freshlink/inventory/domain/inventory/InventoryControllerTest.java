package com.freshlink.inventory.domain.inventory;

import java.time.OffsetDateTime;
import java.util.List;

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
}