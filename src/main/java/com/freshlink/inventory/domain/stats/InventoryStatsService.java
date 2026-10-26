package com.freshlink.inventory.domain.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.inventory.LedgerType;
import com.freshlink.inventory.domain.inventory.ProductLedgerSum;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-42 판매·폐기 통계. 원장(ledger)을 상품·종류별로 GROUP BY 한 결과를 화면 모양으로 바꾼다.
 * 금액은 order-service 몫이라 수량만 다룬다.
 */
@Service
@Transactional(readOnly = true)
public class InventoryStatsService {

    /** from을 주지 않을 때 기간 길이(to 포함) */
    static final int DEFAULT_DAYS = 7;

    private final LedgerRepository ledgerRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public InventoryStatsService(
            LedgerRepository ledgerRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.ledgerRepository = ledgerRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    public InventoryStatsResponse getStats(Long storeId, LocalDate from, LocalDate to) {
        // to를 빼면 오늘, from을 빼면 to까지 7일
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);

        if (start.isAfter(end)) {
            throw new BusinessException(InventoryErrorCode.INVALID_DATE_RANGE);
        }

        List<ProductLedgerSum> sums = ledgerRepository.sumByProductAndType(
                storeId,
                start.atStartOfDay(),
                end.plusDays(1).atStartOfDay()
        );

        Map<Long, Map<LedgerType, Integer>> byProduct = new HashMap<>();
        for (ProductLedgerSum sum : sums) {
            // 판매·폐기는 음수로 저장돼 있어 양수로 바꾼다. 조정은 늘고 준 것을 합친 값(±) 그대로 둔다
            long quantity = sum.type() == LedgerType.ADJUST ? sum.quantity() : Math.abs(sum.quantity());
            byProduct.computeIfAbsent(sum.productId(), id -> new EnumMap<>(LedgerType.class))
                    .merge(sum.type(), Math.toIntExact(quantity), Integer::sum);
        }

        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(byProduct.keySet())
                .forEach(product -> products.put(product.getId(), product));

        List<ProductStatsResponse> rows = byProduct.entrySet().stream()
                .map(entry -> toRow(products.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingInt(ProductStatsResponse::disposedQuantity).reversed()
                        .thenComparing(Comparator.comparingInt(ProductStatsResponse::soldQuantity).reversed())
                        .thenComparing(ProductStatsResponse::name)
                        .thenComparing(ProductStatsResponse::productId))
                .toList();

        int inbound = rows.stream().mapToInt(ProductStatsResponse::inboundQuantity).sum();
        int sold = rows.stream().mapToInt(ProductStatsResponse::soldQuantity).sum();
        int disposed = rows.stream().mapToInt(ProductStatsResponse::disposedQuantity).sum();
        int adjusted = rows.stream().mapToInt(ProductStatsResponse::adjustedQuantity).sum();

        return new InventoryStatsResponse(
                start, end, inbound, sold, disposed, adjusted, disposalRate(sold, disposed), rows);
    }

    private ProductStatsResponse toRow(Product product, Map<LedgerType, Integer> quantities) {
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        int sold = quantities.getOrDefault(LedgerType.SALE, 0);
        int disposed = quantities.getOrDefault(LedgerType.DISPOSE, 0);

        return new ProductStatsResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getCategory(),
                quantities.getOrDefault(LedgerType.INBOUND, 0),
                sold,
                disposed,
                quantities.getOrDefault(LedgerType.ADJUST, 0),
                disposalRate(sold, disposed)
        );
    }

    /** 폐기 ÷ (판매 + 폐기), 소수 넷째 자리까지. 나간 물건 중 버려진 비율이다. 나간 물건이 없으면 null */
    static Double disposalRate(int sold, int disposed) {
        int out = sold + disposed;
        if (out == 0) {
            return null;
        }
        return Math.round(disposed * 10_000.0 / out) / 10_000.0;
    }
}
