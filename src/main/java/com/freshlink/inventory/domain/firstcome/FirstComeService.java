package com.freshlink.inventory.domain.firstcome;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.firstcome.FirstComeSaleRepository.FirstComeSale;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.storestock.StoreProductRow;
import com.freshlink.inventory.domain.storestock.StoreProductStockRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선착순 판매 한도. 점주가 상품에 "선착순, 한도 N개"를 정하고, order 대기열은 남은 수량만큼 입장시킨다.
 * 한도를 넘는 예약은 RESERVE 처리(InventoryCommandService)가 재고 부족으로 거절한다.
 * 설정값이라 쪽지를 거치지 않는다(안전재고 S-40과 같다).
 */
@Service
@Transactional(readOnly = true)
public class FirstComeService {

    private static final Logger log = LoggerFactory.getLogger(FirstComeService.class);

    private final FirstComeSaleRepository firstComeSaleRepository;
    private final ProductRepository productRepository;
    private final StoreProductStockRepository storeProductStockRepository;
    private final Clock clock;

    public FirstComeService(
            FirstComeSaleRepository firstComeSaleRepository,
            ProductRepository productRepository,
            StoreProductStockRepository storeProductStockRepository,
            Clock clock
    ) {
        this.firstComeSaleRepository = firstComeSaleRepository;
        this.productRepository = productRepository;
        this.storeProductStockRepository = storeProductStockRepository;
        this.clock = clock;
    }

    /** order 대기열이 부르는 남은 수량. 선착순이 아니면 404 FIRST_COME_NOT_FOUND */
    public FirstComeResponse getStatus(Long storeId, Long productId) {
        FirstComeSale sale = firstComeSaleRepository.find(storeId, productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.FIRST_COME_NOT_FOUND));
        return status(storeId, sale, LocalDateTime.now(clock));
    }

    /** 점주 설정 탭. 상품 전체를 이름순으로, 선착순인 상품에는 한도·센 수량·남은 수량을 붙인다 */
    public List<FirstComeSettingResponse> getSettings(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, FirstComeSale> sales = firstComeSaleRepository.findAll(storeId);
        return productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName).thenComparing(Product::getId))
                .map(product -> {
                    FirstComeSale sale = sales.get(product.getId());
                    if (sale == null) {
                        return toSetting(product, null);
                    }
                    return toSetting(product, status(storeId, sale, now));
                })
                .toList();
    }

    @Transactional
    public FirstComeSettingResponse enable(Long storeId, Long productId, int limit) {
        Product product = findProduct(productId);
        LocalDateTime now = LocalDateTime.now(clock);
        firstComeSaleRepository.upsert(storeId, productId, limit, now);
        log.info("선착순 켜기·한도 변경: storeId={}, productId={}, limit={}", storeId, productId, limit);
        FirstComeSale sale = firstComeSaleRepository.find(storeId, productId).orElseThrow();
        return toSetting(product, status(storeId, sale, now));
    }

    @Transactional
    public FirstComeSettingResponse disable(Long storeId, Long productId) {
        Product product = findProduct(productId);
        firstComeSaleRepository.remove(storeId, productId);
        log.info("선착순 끄기: storeId={}, productId={}", storeId, productId);
        return toSetting(product, null);
    }

    /**
     * RESERVE 처리에서 쓴다. 선착순이면 한도에서 더 잡을 수 있는 수량(limit − used, 0 아래로 안 감), 아니면 null.
     * 같은 점포 쪽지는 FIFO 한 줄이라 이 값을 읽고 예약하는 사이에 다른 예약이 끼지 않는다
     */
    public Integer limitLeft(Long storeId, Long productId) {
        return firstComeSaleRepository.find(storeId, productId)
                .map(sale -> Math.max(0, sale.limit() - firstComeSaleRepository.countUsed(
                        storeId, productId, sale.startedAt())))
                .orElse(null);
    }

    private FirstComeResponse status(Long storeId, FirstComeSale sale, LocalDateTime now) {
        int used = firstComeSaleRepository.countUsed(storeId, sale.productId(), sale.startedAt());
        int available = storeProductStockRepository.findStoreProduct(storeId, sale.productId())
                .map(row -> row.availableAt(now))
                .orElse(0);
        int remaining = Math.max(0, Math.min(sale.limit() - used, available));
        return new FirstComeResponse(sale.productId(), sale.limit(), used, remaining);
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
    }

    private static FirstComeSettingResponse toSetting(Product product, FirstComeResponse status) {
        return new FirstComeSettingResponse(
                product.getId(), product.getProductCode(), product.getName(), product.getCategory(),
                status != null,
                status == null ? null : status.limit(),
                status == null ? null : status.used(),
                status == null ? null : status.remaining());
    }
}
