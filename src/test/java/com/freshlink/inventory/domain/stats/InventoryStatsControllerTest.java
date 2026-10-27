package com.freshlink.inventory.domain.stats;

import java.time.LocalDate;
import java.util.List;

import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InventoryStatsController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class InventoryStatsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InventoryStatsService inventoryStatsService;

    @Test
    void 기간을_받아_200과_no_store로_응답한다() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 19);
        LocalDate to = LocalDate.of(2026, 10, 25);
        when(inventoryStatsService.getStats(1L, from, to)).thenReturn(new InventoryStatsResponse(
                from, to, 16, 3, 2, 0.4,
                List.of(new ProductStatsResponse(12L, "P012", "흰 우유 500ml", "유제품", 6, 0, 2, 1.0))
        ));

        mockMvc.perform(get("/owner/inventory/stats")
                        .param("from", "2026-10-19")
                        .param("to", "2026-10-25")
                        .header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.disposalRate").value(0.4))
                .andExpect(jsonPath("$.products[0].name").value("흰 우유 500ml"))
                .andExpect(jsonPath("$.products[0].disposedQuantity").value(2));
    }

    @Test
    void 날짜_모양이_틀리면_400이다() throws Exception {
        mockMvc.perform(get("/owner/inventory/stats").param("from", "10/19").header("X-Store-Id", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(get("/owner/inventory/stats"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void 폐기율은_폐기를_판매와_폐기의_합으로_나눈다() {
        assertThat(InventoryStatsService.disposalRate(3, 2)).isEqualTo(0.4);
        assertThat(InventoryStatsService.disposalRate(2, 1)).isEqualTo(0.3333);
        assertThat(InventoryStatsService.disposalRate(5, 0)).isEqualTo(0.0);
        assertThat(InventoryStatsService.disposalRate(0, 0)).isNull();
    }
}
