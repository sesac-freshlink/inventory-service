package com.freshlink.inventory.domain.product;

import java.util.List;

import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import com.freshlink.inventory.domain.inventory.InventoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StoreProductController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class StoreProductControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InventoryQueryService inventoryQueryService;

    @Test
    void 고객_상품목록은_헤더없이_200으로_응답한다() throws Exception {
        when(inventoryQueryService.getStoreProducts(1L, null))
                .thenReturn(List.of(
                        new StoreProductResponse(
                                15L,
                                "매운 컵라면 115g",
                                "라면",
                                1700L,
                                "https://img.example/P015.jpg",
                                5,
                                false
                        ),
                        new StoreProductResponse(
                                2L,
                                "생수 500ml",
                                "음료",
                                1000L,
                                null,
                                0,
                                true
                        )
                ));

        mockMvc.perform(get("/stores/1/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].productId").value(15))
                .andExpect(jsonPath("$[0].name")
                        .value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[0].price").value(1700))
                .andExpect(jsonPath("$[0].availableQuantity").value(5))
                .andExpect(jsonPath("$[0].soldOut").value(false))
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.example/P015.jpg"))
                .andExpect(jsonPath("$[1].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$[1].availableQuantity").value(0))
                .andExpect(jsonPath("$[1].soldOut").value(true));
    }

    @Test
    void 입고가_없는_점포도_상품전체를_품절로_응답한다() throws Exception {
        when(inventoryQueryService.getStoreProducts(999L, null))
                .thenReturn(List.of(
                        new StoreProductResponse(
                                2L,
                                "생수 500ml",
                                "음료",
                                1000L,
                                null,
                                0,
                                true
                        )
                ));

        mockMvc.perform(get("/stores/999/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].productId").value(2))
                .andExpect(jsonPath("$[0].availableQuantity").value(0))
                .andExpect(jsonPath("$[0].soldOut").value(true));
    }

    @Test
    void 검색어를_서비스에_넘기고_50자를_넘으면_400이다() throws Exception {
        when(inventoryQueryService.getStoreProducts(1L, "우유")).thenReturn(List.of());

        mockMvc.perform(get("/stores/1/products").param("q", "우유"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/stores/1/products").param("q", "가".repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}