package com.freshlink.inventory.domain.product;

import java.util.List;

import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    StoreProductService storeProductService;

    @Test
    void 고객_상품목록은_헤더없이_200으로_응답한다() throws Exception {
        when(storeProductService.getStoreProducts(1L, StoreProductQuery.of(null)))
                .thenReturn(List.of(
                        new StoreProductResponse(15L, "매운 컵라면 115g", "라면", 1700L,
                                "https://img.example/P015.jpg", 5, false, true, false, false),
                        new StoreProductResponse(2L, "생수 500ml", "음료", 1000L,
                                null, 0, true, false, true, true)
                ));

        mockMvc.perform(get("/stores/1/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].productId").value(15))
                .andExpect(jsonPath("$[0].name").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[0].price").value(1700))
                .andExpect(jsonPath("$[0].availableQuantity").value(5))
                .andExpect(jsonPath("$[0].soldOut").value(false))
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.example/P015.jpg"))
                .andExpect(jsonPath("$[0].recommended").value(true))
                .andExpect(jsonPath("$[0].newArrival").value(false))
                .andExpect(jsonPath("$[1].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$[1].availableQuantity").value(0))
                .andExpect(jsonPath("$[1].soldOut").value(true))
                .andExpect(jsonPath("$[1].newArrival").value(true))
                .andExpect(jsonPath("$[1].firstCome").value(true));
    }

    @Test
    void 입고가_없는_점포도_상품전체를_품절로_응답한다() throws Exception {
        when(storeProductService.getStoreProducts(999L, StoreProductQuery.of(null)))
                .thenReturn(List.of(new StoreProductResponse(2L, "생수 500ml", "음료", 1000L,
                        null, 0, true, false, false, false)));

        mockMvc.perform(get("/stores/999/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].productId").value(2))
                .andExpect(jsonPath("$[0].availableQuantity").value(0))
                .andExpect(jsonPath("$[0].soldOut").value(true));
    }

    @Test
    void 검색어를_서비스에_넘기고_50자를_넘으면_400이다() throws Exception {
        when(storeProductService.getStoreProducts(1L, StoreProductQuery.of("우유"))).thenReturn(List.of());

        mockMvc.perform(get("/stores/1/products").param("q", "우유"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/stores/1/products").param("q", "가".repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 카테고리_정렬_추천_신상품_조건을_서비스에_그대로_넘긴다() throws Exception {
        when(storeProductService.getStoreProducts(eq(1L), any())).thenReturn(List.of());

        mockMvc.perform(get("/stores/1/products")
                        .param("category", "유제품")
                        .param("sort", "POPULAR")
                        .param("recommended", "true")
                        .param("newOnly", "true"))
                .andExpect(status().isOk());

        verify(storeProductService).getStoreProducts(1L,
                new StoreProductQuery(null, "유제품", StoreProductSort.POPULAR, true, true));
    }

    @Test
    void 모르는_정렬_값은_400이다() throws Exception {
        mockMvc.perform(get("/stores/1/products").param("sort", "PRICE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 카테고리_목록을_준다() throws Exception {
        when(storeProductService.getCategories()).thenReturn(List.of("라면", "유제품"));

        mockMvc.perform(get("/stores/1/products/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0]").value("라면"))
                .andExpect(jsonPath("$[1]").value("유제품"));
    }
}
