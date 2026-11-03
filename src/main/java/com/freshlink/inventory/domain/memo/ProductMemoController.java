package com.freshlink.inventory.domain.memo;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/owner/inventory/memos")
public class ProductMemoController {

    private static final String STORE_ID_HEADER = "X-Store-Id";

    private final ProductMemoService memoService;

    public ProductMemoController(ProductMemoService memoService) {
        this.memoService = memoService;
    }

    @Operation(summary = "점주 상품 메모 목록 (메모가 있는 상품만, 최근에 고친 것부터)")
    @GetMapping
    public ResponseEntity<List<MemoResponse>> getMemos(@RequestHeader(STORE_ID_HEADER) Long storeId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(memoService.getMemos(storeId));
    }

    @Operation(summary = "상품 메모 저장 (없으면 만들고 있으면 고친다. 읽은 version과 다르면 409 MEMO_CHANGED)")
    @PutMapping("/{productId}")
    public MemoResponse save(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId,
            @Valid @RequestBody MemoRequest request
    ) {
        return memoService.save(storeId, productId, request.content().strip(), request.version());
    }

    @Operation(summary = "상품 메모 삭제 (없어도 204. version을 주면 그 사이 바뀐 메모는 409)")
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(STORE_ID_HEADER) Long storeId,
            @PathVariable Long productId,
            @RequestParam(required = false) Long version
    ) {
        memoService.delete(storeId, productId, version);
        return ResponseEntity.noContent().build();
    }
}
