package com.freshlink.inventory.domain.recommendation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점주 추천상품 설정. 재고(lot)를 바꾸지 않는 설정값이라 쪽지를 거치지 않는다(안전재고 S-40과 같다).
 */
@Service
@Transactional(readOnly = true)
public class ProductRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ProductRecommendationService.class);

    private final ProductRecommendationRepository recommendationRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public ProductRecommendationService(
            ProductRecommendationRepository recommendationRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.recommendationRepository = recommendationRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /** 상품 전체를 이름순으로. 입고 전인 상품도 미리 정할 수 있다 */
    public List<ProductRecommendationResponse> getRecommendations(Long storeId) {
        Set<Long> recommended = recommendationRepository.findProductIds(storeId);
        return productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName).thenComparing(Product::getId))
                .map(product -> toResponse(product, recommended.contains(product.getId())))
                .toList();
    }

    @Transactional
    public ProductRecommendationResponse set(Long storeId, Long productId, boolean recommended) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
        if (recommended) {
            recommendationRepository.add(storeId, productId, LocalDateTime.now(clock));
        } else {
            recommendationRepository.remove(storeId, productId);
        }
        log.info("추천상품 변경: storeId={}, productId={}, recommended={}", storeId, productId, recommended);
        return toResponse(product, recommended);
    }

    /**
     * 여러 상품을 한 번에 켜거나 끈다. 상품을 먼저 모두 확인해 없는 상품이 하나라도 있으면 아무것도 바꾸지 않는다(404).
     * 한 개씩 바꿀 때와 같은 문장(INSERT IGNORE, 조건 있는 DELETE)이라 다시 보내도 결과가 같다. 결과는 이름순
     */
    @Transactional
    public List<ProductRecommendationResponse> setAll(Long storeId, List<Long> productIds, boolean recommended) {
        Set<Long> ids = new LinkedHashSet<>(productIds);
        List<Product> products = productRepository.findAllById(ids);
        if (products.size() != ids.size()) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (Long productId : ids) {
            if (recommended) {
                recommendationRepository.add(storeId, productId, now);
            } else {
                recommendationRepository.remove(storeId, productId);
            }
        }
        log.info("추천상품 한 번에 변경: storeId={}, products={}, recommended={}", storeId, ids.size(), recommended);
        return products.stream()
                .sorted(Comparator.comparing(Product::getName).thenComparing(Product::getId))
                .map(product -> toResponse(product, recommended))
                .toList();
    }

    private static ProductRecommendationResponse toResponse(Product product, boolean recommended) {
        return new ProductRecommendationResponse(
                product.getId(), product.getProductCode(), product.getName(), product.getCategory(), recommended);
    }
}
