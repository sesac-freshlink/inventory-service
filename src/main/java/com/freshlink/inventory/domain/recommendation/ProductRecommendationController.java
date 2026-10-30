package com.freshlink.inventory.domain.recommendation;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** gateway 라우트 /api/owner/inventory/** 아래라 gateway를 고치지 않아도 된다 */
@RestController
@RequestMapping("/owner/inventory/recommendations")
public class ProductRecommendationController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final ProductRecommendationService recommendationService;

    public ProductRecommendationController(ProductRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Operation(summary = "점주 추천상품 설정 목록 (상품 전체, 이름순)")
    @GetMapping
    public ResponseEntity<List<ProductRecommendationResponse>> getRecommendations(
            @RequestHeader(STORE_ID_HEADER) Long storeId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(recommendationService.getRecommendations(storeId));
    }

    @Operation(summary = "상품을 추천상품으로 정한다 (다시 보내도 결과가 같다)")
    @PutMapping("/{productId}")
    public ProductRecommendationResponse recommend(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId
    ) {
        return recommendationService.set(storeId, productId, true);
    }

    @Operation(summary = "추천상품에서 뺀다 (추천이 아니어도 결과가 같다)")
    @DeleteMapping("/{productId}")
    public ProductRecommendationResponse unrecommend(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId
    ) {
        return recommendationService.set(storeId, productId, false);
    }
}
