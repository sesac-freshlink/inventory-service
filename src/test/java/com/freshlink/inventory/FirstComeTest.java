package com.freshlink.inventory;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.inventory.common.web.XStoreIdFilter;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.freshlink.inventory.FixedClockConfiguration.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선착순 판매 한도: 점주 설정 API, order 대기열이 부르는 남은 수량, 한도를 넘는 예약 거절. 점포·상품·주문 ID는 55xx.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class FirstComeTest {

    static final String EXPIRY = "2027-12-31T23:59:59+09:00";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    XStoreIdFilter xStoreIdFilter;

    @Autowired
    InventoryCommandService inventoryCommandService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(xStoreIdFilter)
                .build();
    }

    @Test
    void 한도를_넘는_예약은_재고가_있어도_거절하고_남은_수량을_알려_준다() throws Exception {
        long storeId = 5501L;
        long productId = product("P5501");
        inbound(storeId, "P5501", 10);
        enable(storeId, productId, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstCome").value(true))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.remaining").value(3));

        reserve(5501L, storeId, productId, 2);
        remainingOf(storeId, productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.used").value(2))
                .andExpect(jsonPath("$.remaining").value(1));

        reserve(5502L, storeId, productId, 2);
        ReserveFailedMessage failed = lastReserveFailed(5502L);
        assertThat(failed.reason()).isEqualTo(ReserveFailReason.OUT_OF_STOCK);
        assertThat(failed.availableQuantity()).isEqualTo(1);
        assertThat(reservedQuantity(storeId)).isEqualTo(2);

        reserve(5503L, storeId, productId, 1);
        remainingOf(storeId, productId).andExpect(jsonPath("$.remaining").value(0));
    }

    @Test
    void 해제된_예약은_자리가_다시_나고_확정과_환불은_자리를_내지_않는다() throws Exception {
        long storeId = 5502L;
        long productId = product("P5502");
        inbound(storeId, "P5502", 10);
        enable(storeId, productId, 2);

        reserve(5511L, storeId, productId, 1);
        reserve(5512L, storeId, productId, 1);
        remainingOf(storeId, productId).andExpect(jsonPath("$.remaining").value(0));

        handle(new ReleaseMessage(newId(), NOW, 5511L, storeId, ReleaseReason.EXPIRED));
        remainingOf(storeId, productId).andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.remaining").value(1));

        handle(new ConfirmMessage(newId(), NOW, 5512L, storeId));
        handle(ReturnMessage.of(5512L, storeId));
        remainingOf(storeId, productId).andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.remaining").value(1));
    }

    @Test
    void 남은_수량은_구매_가능_수량을_넘지_않는다() throws Exception {
        long storeId = 5503L;
        long productId = product("P5503");
        inbound(storeId, "P5503", 2);
        enable(storeId, productId, 100);

        remainingOf(storeId, productId).andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void 한도를_바꿔도_센_수량은_이어지고_끄면_한도_없이_예약된다() throws Exception {
        long storeId = 5504L;
        long productId = product("P5504");
        inbound(storeId, "P5504", 10);
        enable(storeId, productId, 2);
        reserve(5521L, storeId, productId, 2);

        enable(storeId, productId, 5).andExpect(jsonPath("$.used").value(2))
                .andExpect(jsonPath("$.remaining").value(3));

        owner(delete("/owner/inventory/first-come/" + productId), storeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstCome").value(false));
        remainingOf(storeId, productId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FIRST_COME_NOT_FOUND"));
        reserve(5522L, storeId, productId, 6);
        assertThat(reservedQuantity(storeId)).isEqualTo(8);
    }

    @Test
    void 고객_목록과_상세에_선착순이_표시되고_점주_목록에_한도가_보인다() throws Exception {
        long storeId = 5505L;
        long productId = product("P5505");
        inbound(storeId, "P5505", 4);
        enable(storeId, productId, 3);

        mockMvc.perform(get("/stores/5505/products").param("category", "선착순시험"))
                .andExpect(jsonPath("$[?(@.productId == " + productId + ")].firstCome").value(true));
        mockMvc.perform(get("/stores/5505/products/" + productId))
                .andExpect(jsonPath("$.firstCome").value(true));
        mockMvc.perform(get("/stores/5506/products/" + productId))
                .andExpect(jsonPath("$.firstCome").value(false));
        owner(get("/owner/inventory/first-come"), storeId)
                .andExpect(jsonPath("$[?(@.productId == " + productId + ")].limit").value(3))
                .andExpect(jsonPath("$[?(@.productId == " + productId + ")].remaining").value(3));
    }

    @Test
    void 한도는_1에서_999이고_없는_상품은_404다() throws Exception {
        long productId = product("P5506");
        owner(put("/owner/inventory/first-come/" + productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"), 5507L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        owner(put("/owner/inventory/first-come/" + productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1000}"), 5507L)
                .andExpect(status().isBadRequest());
        owner(put("/owner/inventory/first-come/999999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"), 5507L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private ResultActions enable(long storeId, long productId, int limit) throws Exception {
        return owner(put("/owner/inventory/first-come/" + productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":" + limit + "}"), storeId);
    }

    private ResultActions remainingOf(long storeId, long productId) throws Exception {
        return mockMvc.perform(get("/stores/" + storeId + "/products/" + productId + "/first-come"));
    }

    private ResultActions owner(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, long storeId)
            throws Exception {
        return mockMvc.perform(request.header("X-Store-Id", String.valueOf(storeId)));
    }

    private void reserve(long orderId, long storeId, long productId, int quantity) {
        handle(new ReserveMessage(newId(), NOW, orderId, storeId, productId, quantity));
    }

    private void inbound(long storeId, String productCode, int quantity) {
        handle(new InboundMessage(newId(), NOW, storeId, "SHP-" + UUID.randomUUID(),
                List.of(new InboundItem(productCode, quantity, OffsetDateTime.parse(EXPIRY)))));
    }

    private void handle(BaseMessage message) {
        inventoryCommandService.handle(message);
    }

    private ReserveFailedMessage lastReserveFailed(long orderId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox WHERE message_type = 'RESERVE_FAILED' ORDER BY id DESC", String.class);
        return payloads.stream()
                .map(payload -> (ReserveFailedMessage) MessageJson.fromJson(payload))
                .filter(message -> message.orderId() == orderId)
                .findFirst()
                .orElseThrow();
    }

    private Integer reservedQuantity(long storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(reserved_quantity), 0) FROM inventory_lot WHERE store_id = ?",
                Integer.class, storeId);
    }

    private long product(String productCode) {
        jdbcTemplate.update("INSERT INTO product (product_code, name, category, price) VALUES (?, ?, ?, ?)",
                productCode, "선착순 " + productCode, "선착순시험", 1000L);
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, productCode);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
