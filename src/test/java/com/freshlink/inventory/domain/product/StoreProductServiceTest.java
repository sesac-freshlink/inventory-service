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
                new StoreProductRow(8L, "감자칩", "과자", 1500L, null, null, null),
                new StoreProductRow(15L, "매운 컵라면 115g", "라면", 1700L, "https://img.example/P015.jpg",
                        5, NOW.plusHours(1)),
                new StoreProductRow(2L, "생수 500ml", "음료", 1000L, null, 3, NOW)
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
                new StoreProductRow(1L, "가", "c", 1L, null, 1, NOW.plusDays(1)),
                new StoreProductRow(2L, "나", "c", 1L, null, 1, NOW.plusDays(1)),
                new StoreProductRow(3L, "다", "c", 1L, null, 1, NOW.plusDays(1)),
                new StoreProductRow(4L, "라", "c", 1L, null, 1, NOW.plusDays(1))
        ));

        List<StoreProductResponse> result = service.getStoreProducts(
                1L, new StoreProductQuery(null, null, StoreProductSort.POPULAR, false, false));

        assertThat(result).extracting(StoreProductResponse::productId).containsExactly(3L, 1L, 2L, 4L);
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
                new StoreProductRow(1L, "가", "c", 1L, null, 1, NOW.plusDays(1)),
                new StoreProductRow(2L, "나", "c", 1L, null, 1, NOW.plusDays(1)),
                new StoreProductRow(3L, "다", "c", 1L, null, 1, NOW.plusDays(1))
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
}
