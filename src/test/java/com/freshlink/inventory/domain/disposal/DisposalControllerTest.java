package com.freshlink.inventory.domain.disposal;

import java.time.OffsetDateTime;
import java.util.List;

import com.freshlink.inventory.common.error.GlobalExceptionHandler;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S-33 폐기 요청 이력 API 모양. 폐기 요청(POST)은 DisposalTest(Testcontainers)가 본다 */
@WebMvcTest(controllers = DisposalController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class DisposalControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DisposalService disposalService;

    @MockitoBean
    DisposalQueryService disposalQueryService;

    @Test
    void 폐기이력은_200과_no_store로_응답한다() throws Exception {
        when(disposalQueryService.getDisposals(1L, null))
                .thenReturn(List.of(new DisposalHistoryResponse(
                        501L,
                        412L,
                        3L,
                        "흰우유 200ml",
                        OffsetDateTime.parse("2026-10-24T23:59:59+09:00"),
                        2,
                        DisposalStatus.SKIPPED,
                        OffsetDateTime.parse("2026-10-25T12:00:00+09:00"),
                        OffsetDateTime.parse("2026-10-25T12:00:02+09:00")
                )));

        mockMvc.perform(get("/owner/disposals").header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].disposalId").value(501))
                .andExpect(jsonPath("$[0].productName").value("흰우유 200ml"))
                .andExpect(jsonPath("$[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$[0].expiryDate").value("2026-10-24T23:59:59+09:00"))
                .andExpect(jsonPath("$[0].processedAt").value("2026-10-25T12:00:02+09:00"));
    }

    @Test
    void 상태_필터를_넘긴다() throws Exception {
        when(disposalQueryService.getDisposals(1L, DisposalStatus.DONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/owner/disposals").param("status", "DONE").header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(get("/owner/disposals"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void 알_수_없는_상태값은_400이다() throws Exception {
        when(disposalQueryService.getDisposals(1L, null)).thenReturn(List.of());

        mockMvc.perform(get("/owner/disposals").param("status", "PENDING").header("X-Store-Id", "1"))
                .andExpect(status().isBadRequest());
    }
}
