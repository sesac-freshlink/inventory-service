package com.freshlink.inventory.domain.safetystock;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-40 안전재고 조회·변경. 설정값만 다루고 재고(lot)는 바꾸지 않으므로 쪽지를 거치지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class SafetyStockService {

    private static final Logger log = LoggerFactory.getLogger(SafetyStockService.class);

    private final SafetyStockRepository safetyStockRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public SafetyStockService(
            SafetyStockRepository safetyStockRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.safetyStockRepository = safetyStockRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /** 상품 전체를 이름순으로. 입고 전인 상품도 미리 정할 수 있게 lot 유무와 상관없이 내린다 */
    public List<SafetyStockResponse> getSafetyStocks(Long storeId) {
        Map<Long, Integer> quantities = safetyStockRepository.quantitiesByProduct(storeId);

        return productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName).thenComparing(Product::getId))
                .map(product -> toResponse(product, quantities.get(product.getId())))
                .toList();
    }

    @Transactional
    public SafetyStockResponse update(Long storeId, Long productId, int safetyStock) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));

        safetyStockRepository.upsert(storeId, productId, safetyStock, LocalDateTime.now(clock));

        log.info("안전재고 변경: storeId={}, productId={}, safetyStock={}", storeId, productId, safetyStock);

        return toResponse(product, safetyStock);
    }

    private SafetyStockResponse toResponse(Product product, Integer quantity) {
        return new SafetyStockResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getCategory(),
                quantity == null ? SafetyStock.DEFAULT_QUANTITY : quantity,
                quantity != null
        );
    }
}
