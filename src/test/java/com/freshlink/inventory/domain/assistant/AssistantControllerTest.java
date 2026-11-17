package com.freshlink.inventory.domain.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(controllers = AssistantController.class)
@Import({XStoreIdFilter.class, GlobalExceptionHandler.class})
class AssistantControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AssistantService assistantService;

    @Test
    void 질문하면_200과_답_세_칸을_돌려준다() throws Exception {
        when(assistantService.ask(1L, new AskRequest("Q1", null))).thenReturn(new AssistantAnswer(
                "샐러드 4개가 오늘 끝나요.", AssistantAnswer.AI, OffsetDateTime.parse("2026-11-20T14:05:12+09:00")));

        ask("1", "{\"presetId\":\"Q1\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.answer").value("샐러드 4개가 오늘 끝나요."))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-20T14:05:12+09:00"));
    }

    @Test
    void 점포_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(post("/owner/inventory/assistant/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetId\":\"Q1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }

    @Test
    void 본문이_틀리면_400이고_묻지_않는다() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"presetId\":\"Q1\",\"question\":\"둘 다\"}",
                "{\"question\":\"   \"}",
                "{\"presetId\":\"Q6\"}",
                "{\"question\":\"" + "가".repeat(201) + "\"}"
        )) {
            ask("1", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(assistantService, never()).ask(anyLong(), any());
    }

    @Test
    void 꺼짐은_503_제한은_429다() throws Exception {
        when(assistantService.ask(anyLong(), any()))
                .thenThrow(new BusinessException(InventoryErrorCode.ASSISTANT_DISABLED))
                .thenThrow(new BusinessException(InventoryErrorCode.ASSISTANT_BUSY))
                .thenThrow(new BusinessException(InventoryErrorCode.ASSISTANT_RATE_LIMITED));

        ask("1", "{\"question\":\"오늘 뭐 챙겨?\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_DISABLED"));
        ask("1", "{\"question\":\"오늘 뭐 챙겨?\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_BUSY"));
        ask("1", "{\"question\":\"오늘 뭐 챙겨?\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));
    }

    private ResultActions ask(String storeId, String body) throws Exception {
        return mockMvc.perform(post("/owner/inventory/assistant/ask")
                .header("X-Store-Id", storeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
