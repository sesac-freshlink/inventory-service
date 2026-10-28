package com.freshlink.inventory.domain.safetystock;

import java.util.List;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SafetyStockController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class SafetyStockControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SafetyStockService safetyStockService;

    @Test
    void 목록은_200과_no_store로_응답한다() throws Exception {
        when(safetyStockService.getSafetyStocks(1L)).thenReturn(List.of(
                new SafetyStockResponse(15L, "P015", "매운 컵라면 115g", "라면", 8, true),
                new SafetyStockResponse(16L, "P016", "흰 우유 200ml", "유제품", 5, false)
        ));

        mockMvc.perform(get("/owner/inventory/safety-stocks").header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].productId").value(15))
                .andExpect(jsonPath("$[0].safetyStock").value(8))
                .andExpect(jsonPath("$[0].customized").value(true))
                .andExpect(jsonPath("$[1].customized").value(false));
    }

    @Test
    void 변경은_200과_바뀐_값을_돌려준다() throws Exception {
        when(safetyStockService.update(1L, 15L, 12))
                .thenReturn(new SafetyStockResponse(15L, "P015", "매운 컵라면 115g", "라면", 12, true));

        mockMvc.perform(put("/owner/inventory/safety-stocks/15")
                        .header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safetyStock\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(15))
                .andExpect(jsonPath("$.safetyStock").value(12));
    }

    @Test
    void 범위를_벗어나거나_빈_값이면_400이고_저장하지_않는다() throws Exception {
        for (String body : List.of("{\"safetyStock\":-1}", "{\"safetyStock\":1000}", "{}")) {
            mockMvc.perform(put("/owner/inventory/safety-stocks/15")
                            .header("X-Store-Id", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verify(safetyStockService, never()).update(anyLong(), anyLong(), anyInt());
    }

    @Test
    void 없는_상품이면_404다() throws Exception {
        when(safetyStockService.update(1L, 999L, 3))
                .thenThrow(new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(put("/owner/inventory/safety-stocks/999")
                        .header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safetyStock\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(get("/owner/inventory/safety-stocks"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }
}
