package com.freshlink.inventory.domain.product;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;

/**
 * U-10 고객용 점포 상품 (공개). gateway 공개 라우트 /api/stores/{storeId}/products 와
 * /api/stores/{storeId}/products/{productId} 아래라 카테고리 주소(/categories)도 gateway를 고치지 않아도 된다
 */
@RestController
@RequestMapping("/stores/{storeId}/products")
public class StoreProductController {

    /** 검색어 최대 길이. 점포 검색(member ST-01)과 같다 */
    static final int MAX_QUERY_LENGTH = 50;
    /** 카테고리 최대 길이. product.category 칸 길이와 같다 */
    static final int MAX_CATEGORY_LENGTH = 30;

    private final StoreProductService storeProductService;

    public StoreProductController(StoreProductService storeProductService) {
        this.storeProductService = storeProductService;
    }

    @Operation(summary = "U-10 고객용 점포 상품 목록 (공개). q = 상품명 검색(50자까지), category = 카테고리, "
            + "sort = NAME(기본, 상품명 순)·POPULAR(추천순, 최근 7일 순판매 많은 순), "
            + "recommended=true = 점주 추천상품만, newOnly=true = 신상품(첫 입고 7일 안)만. 모두 선택")
    @GetMapping
    public ResponseEntity<List<StoreProductResponse>> getProducts(
            @PathVariable Long storeId,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q,
            @RequestParam(required = false) @Size(max = MAX_CATEGORY_LENGTH) String category,
            @RequestParam(required = false) StoreProductSort sort,
            @RequestParam(defaultValue = "false") boolean recommended,
            @RequestParam(defaultValue = "false") boolean newOnly
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(storeProductService.getStoreProducts(
                        storeId, new StoreProductQuery(q, category, sort, recommended, newOnly)));
    }

    @Operation(summary = "U-10 카테고리 목록 (공개). 상품 전체의 카테고리, 가나다순")
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories(@PathVariable Long storeId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(storeProductService.getCategories());
    }

    @Operation(summary = "U-10 고객용 상품 상세 (공개). 구매 가능 수량과 먼저 받게 될 상품의 유통기한")
    @GetMapping("/{productId}")
    public ResponseEntity<StoreProductDetailResponse> getProduct(
            @PathVariable Long storeId,
            @PathVariable Long productId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(storeProductService.getStoreProduct(storeId, productId));
    }
}
