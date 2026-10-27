package com.freshlink.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 이미지(V5). 더미 상품 코드(P001~P030)로 넣은 상품은 마이그레이션이 이미지를 채우고,
 * 그 밖의 상품은 null로 내려가는지 본다. 테스트 DB에는 seed가 없어 P015를 직접 넣는다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@SpringBootTest(properties = "spring.cloud.aws.sqs.listener.auto-startup=false")
class ProductImageTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void 더미_상품_코드는_V5가_이미지를_채우고_그_밖의_상품은_null이다() throws Exception {
        // V5는 이미 적용된 뒤라 UPDATE는 돌지 않는다. 같은 문장을 다시 실행해 더미 코드에 값이 들어가는지 본다
        jdbcTemplate.update("""
                INSERT INTO product (product_code, name, category, price)
                VALUES ('P015', '매운 컵라면 115g', '라면', 1700), ('P4701', '이미지 없는 상품', '테스트', 1000)
                ON DUPLICATE KEY UPDATE name = VALUES(name)
                """);
        jdbcTemplate.update(migrationUpdate());

        mockMvc.perform(get("/stores/4701/products"))
                .andExpect(status().isOk())
                // 필터 경로는 목록으로 오므로 목록 매처로 본다
                .andExpect(jsonPath("$[?(@.name == '매운 컵라면 115g')].imageUrl", contains(startsWith("https://"))))
                .andExpect(jsonPath("$[?(@.name == '이미지 없는 상품')].imageUrl", contains(nullValue())));
    }

    /** V5 파일의 UPDATE 문장을 그대로 읽는다 */
    private String migrationUpdate() throws Exception {
        String sql = new String(
                getClass().getResourceAsStream("/db/migration/V5__product_image.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        return sql.substring(sql.indexOf("UPDATE product"));
    }
}
