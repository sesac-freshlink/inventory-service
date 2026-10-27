package com.freshlink.inventory.domain.adjustment;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdjustmentController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class AdjustmentControllerTest {

    static final String USER_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdjustmentService adjustmentService;

    @Test
    void 조정은_201과_조정_전후_수량을_돌려준다() throws Exception {
        AdjustmentRequest request = new AdjustmentRequest(301L, -2, AdjustmentReason.COUNT, "진열대 실사");
        when(adjustmentService.adjust(1L, USER_ID, request))
                .thenReturn(new AdjustmentResponse(7001L, 301L, -2, 10, 8));

        adjust("{\"lotId\":301,\"delta\":-2,\"reason\":\"COUNT\",\"memo\":\"진열대 실사\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustmentId").value(7001))
                .andExpect(jsonPath("$.quantityBefore").value(10))
                .andExpect(jsonPath("$.quantityAfter").value(8));
    }

    @Test
    void 본문이_틀리면_400이고_조정하지_않는다() throws Exception {
        String longMemo = "가".repeat(201);
        for (String body : List.of(
                "{\"delta\":-2,\"reason\":\"COUNT\"}",
                "{\"lotId\":301,\"reason\":\"COUNT\"}",
                "{\"lotId\":301,\"delta\":1000,\"reason\":\"COUNT\"}",
                "{\"lotId\":301,\"delta\":-1000,\"reason\":\"COUNT\"}",
                "{\"lotId\":301,\"delta\":-2}",
                "{\"lotId\":301,\"delta\":-2,\"reason\":\"COUNT\",\"memo\":\"" + longMemo + "\"}"
        )) {
            adjust(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verify(adjustmentService, never()).adjust(anyLong(), anyString(), any());
    }

    @Test
    void 예약보다_적게_줄이거나_쪽지_처리와_겹치면_409다() throws Exception {
        when(adjustmentService.adjust(1L, USER_ID, new AdjustmentRequest(301L, -9, AdjustmentReason.LOSS, null)))
                .thenThrow(new BusinessException(InventoryErrorCode.BELOW_RESERVED));
        when(adjustmentService.adjust(1L, USER_ID, new AdjustmentRequest(301L, -1, AdjustmentReason.COUNT, null)))
                .thenThrow(new BusinessException(InventoryErrorCode.LOT_CHANGED));

        adjust("{\"lotId\":301,\"delta\":-9,\"reason\":\"LOSS\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BELOW_RESERVED"));
        adjust("{\"lotId\":301,\"delta\":-1,\"reason\":\"COUNT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOT_CHANGED"));
    }

    @Test
    void 사용자_헤더가_없으면_400_점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(post("/owner/inventory/adjustments")
                        .header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\":301,\"delta\":-2,\"reason\":\"COUNT\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/owner/inventory/adjustments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void 이력은_200과_no_store로_응답한다() throws Exception {
        when(adjustmentService.getAdjustments(1L)).thenReturn(List.of(new AdjustmentHistoryResponse(
                7001L, 301L, 15L, "매운 컵라면 115g",
                OffsetDateTime.parse("2026-10-30T23:59:59+09:00"),
                -2, 10, 8, AdjustmentReason.COUNT, null,
                OffsetDateTime.parse("2026-10-25T12:00:00+09:00")
        )));

        mockMvc.perform(get("/owner/inventory/adjustments").header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].productName").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$[0].delta").value(-2))
                .andExpect(jsonPath("$[0].reason").value("COUNT"));
    }

    private ResultActions adjust(String body) throws Exception {
        return mockMvc.perform(post("/owner/inventory/adjustments")
                .header("X-Store-Id", "1")
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
