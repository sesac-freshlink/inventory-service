package com.freshlink.inventory.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.freshlink.inventory.domain.storestock.StoreProductRow;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreProductServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-10-20 15:00 KST */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-20T06:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 15, 0);

    @Mock
    StoreProductStockRepository storeProductStockRepository;

    @Mock
    ProductRepository productRepository;

    @Mock
    StoreProductBadges badges;

    private StoreProductService service;

    @BeforeEach
    void setUp() {
        service = new StoreProductService(
                storeProductStockRepository, productRepository, badges, Clock.fixed(FIXED_INSTANT, SEOUL));
    }

    private void noBadges() {
        when(badges.load(any(), any())).thenReturn(
                new StoreProductBadges.Snapshot(Set.of(), Map.of(), Map.of(), Set.of(), NOW));
    }

    @Test
    void 고객_상품목록은_조회표_JOIN_결과를_그대로_쓰고_값이_맞는_시각이_지났거나_행이_없으면_품절이다() {
        noBadges();
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(List.of(
                new StoreProductRow(8L, "감자칩", "과자", 1500L, null, null, null, 0, null, null),
                new StoreProductRow(15L, "매운 컵라면 115g", "라면", 1700L, "https://img.example/P015.jpg",
                        5, NOW.plusHours(1), 0, null, null),
                new StoreProductRow(2L, "생수 500ml", "음료", 1000L, null, 3, NOW, 0, null, null)
        ));

        List<StoreProductResponse> result = service.getStoreProducts(1L, StoreProductQuery.of(null));

        // 순서는 쿼리(상품명 순) 그대로
        assertThat(result)
                .extracting(StoreProductResponse::name, StoreProductResponse::availableQuantity,
                        StoreProductResponse::soldOut, StoreProductResponse::imageUrl)
                .containsExactly(
                        tuple("감자칩", 0, true, null),
                        tuple("매운 컵라면 115g", 5, false, "https://img.example/P015.jpg"),
                        // 값이 맞는 시각(15:00)이 지금과 같으면 이미 지난 것으로 본다
                        tuple("생수 500ml", 0, true, null)
                );
    }

    @Test
    void 검색어는_앞뒤_공백을_빼고_LIKE_조건으로_넘기며_빈_검색어와_빈_카테고리는_조건이_없다() {
        noBadges();
        when(storeProductStockRepository.findStoreProducts(1L, "%우유%", null)).thenReturn(List.of());
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(List.of());

        service.getStoreProducts(1L, StoreProductQuery.of("  우유 "));
        service.getStoreProducts(1L, new StoreProductQuery("   ", " ", null, false, false));

        verify(storeProductStockRepository).findStoreProducts(1L, "%우유%", null);
        verify(storeProductStockRepository).findStoreProducts(1L, null, null);
    }

    @Test
    void 추천순은_최근_순판매_많은_순이고_같으면_상품명_순을_유지한다() {
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(), Map.of(), Map.of(1L, 2, 3L, 5), Set.of(), NOW));
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(List.of(
                new StoreProductRow(1L, "가", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(2L, "나", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(3L, "다", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(4L, "라", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null)
        ));

        List<StoreProductResponse> result = service.getStoreProducts(
                1L, new StoreProductQuery(null, null, StoreProductSort.POPULAR, false, false));

        assertThat(result).extracting(StoreProductResponse::productId).containsExactly(3L, 1L, 2L, 4L);
    }

    @Test
    void 응답에_최근_7일_순판매_수량을_싣고_환불이_더_많으면_0이다() {
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(), Map.of(), Map.of(1L, 4, 2L, -1), Set.of(), NOW));
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(List.of(
                new StoreProductRow(1L, "가", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(2L, "나", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(3L, "다", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null)
        ));

        assertThat(service.getStoreProducts(1L, StoreProductQuery.of(null)))
                .extracting(StoreProductResponse::productId, StoreProductResponse::soldRecently)
                .containsExactly(tuple(1L, 4), tuple(2L, 0), tuple(3L, 0));
    }

    @Test
    void 추천만_신상품만은_표시값으로_거르고_응답에도_표시한다() {
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(1L, 2L),
                Map.of(2L, NOW.minusDays(1), 3L, NOW.minusDays(8)),
                Map.of(),
                Set.of(),
                NOW));
        List<StoreProductRow> rows = List.of(
                new StoreProductRow(1L, "가", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(2L, "나", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null),
                new StoreProductRow(3L, "다", "c", 1L, null, 1, NOW.plusDays(1), 0, null, null)
        );
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(rows);

        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, true, false)))
                .extracting(StoreProductResponse::productId).containsExactly(1L, 2L);
        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, true)))
                .extracting(StoreProductResponse::productId, StoreProductResponse::recommended,
                        StoreProductResponse::newArrival)
                .containsExactly(tuple(2L, true, true));
        // 첫 입고 8일 전은 신상품이 아니다
        assertThat(service.getStoreProducts(1L, StoreProductQuery.of(null)))
                .filteredOn(p -> p.productId() == 3L)
                .extracting(StoreProductResponse::newArrival).containsExactly(false);
    }

    /** 1: 마감세일 2개, 2: 24시간 안 lot 없음, 3: 24시간 안 lot이 있지만 선착순이라 마감세일이 아니다 */
    private List<StoreProductRow> clearanceRows(LocalDateTime now) {
        return List.of(
                new StoreProductRow(1L, "가", "c", 1000L, null, 5, now.plusHours(1), 2, now.plusDays(2), now.plusHours(8)),
                new StoreProductRow(2L, "나", "c", 1000L, null, 5, now.plusHours(1), 0, now.plusDays(2), null),
                new StoreProductRow(3L, "다", "c", 1000L, null, 5, now.plusHours(1), 2, now.plusDays(2), now.plusHours(8))
        );
    }

    @Test
    void 마감세일만은_지금_마감세일로_살_수_있는_상품만이고_선착순만은_선착순을_켠_상품만이다() {
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(), Map.of(), Map.of(), Set.of(3L), NOW));
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(clearanceRows(NOW));

        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, false, true, false)))
                .extracting(StoreProductResponse::productId, StoreProductResponse::clearanceQuantity)
                .containsExactly(tuple(1L, 2));
        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, false, false, true)))
                .extracting(StoreProductResponse::productId, StoreProductResponse::firstCome)
                .containsExactly(tuple(3L, true));
        // 둘 다 켜면 선착순 상품은 마감세일이 없으므로 비어 있다
        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, false, true, true)))
                .isEmpty();
    }

    @Test
    void 마감세일만은_판매_시간_밖이면_비어_있고_page_size는_거른_뒤에_자른다() {
        // 2026-10-20 23:30 KST: 마감세일 판매 시간(06~23시) 밖
        LocalDateTime night = LocalDateTime.of(2026, 10, 20, 23, 30);
        StoreProductService nightService = new StoreProductService(storeProductStockRepository, productRepository,
                badges, Clock.fixed(Instant.parse("2026-10-20T14:30:00Z"), SEOUL));
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(), Map.of(), Map.of(), Set.of(), night));
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(clearanceRows(night));

        assertThat(nightService.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, false, true, false)))
                .isEmpty();

        // 낮에는 거른 결과(1·3번)에서 첫 묶음 1개만
        when(badges.load(any(), any())).thenReturn(new StoreProductBadges.Snapshot(
                Set.of(), Map.of(), Map.of(), Set.of(), NOW));
        when(storeProductStockRepository.findStoreProducts(1L, null, null)).thenReturn(clearanceRows(NOW));
        assertThat(service.getStoreProducts(1L, new StoreProductQuery(null, null, null, false, false, true, false), 1, 1))
                .extracting(StoreProductResponse::productId).containsExactly(3L);
    }
}
