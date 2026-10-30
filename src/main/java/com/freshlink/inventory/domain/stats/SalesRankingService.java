package com.freshlink.inventory.domain.stats;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.inventory.LedgerType;
import com.freshlink.inventory.domain.inventory.ProductLedgerSum;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품별 판매 순위. 판매는 결제가 확정되어 실물이 줄어든 수량, 곧 원장 SALE에서 환불로 되돌린 RETURN을 뺀 순판매다
 * (예약만 된 수량은 세지 않는다).
 * 원장 집계 쿼리는 S-42 통계와 같은 것(상품·종류별 합)을 쓰고 SALE만 고른다.
 * 환불로 재고를 되돌리는 기능이 생기면 그 원장 종류를 여기서 빼면 된다(지금은 없음)
 */
@Service
@Transactional(readOnly = true)
public class SalesRankingService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 100;

    private final LedgerRepository ledgerRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public SalesRankingService(
            LedgerRepository ledgerRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.ledgerRepository = ledgerRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    public SalesRankingResponse getRanking(Long storeId, LocalDate from, LocalDate to, Integer limit) {
        int size = limit != null ? limit : DEFAULT_LIMIT;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BusinessException(InventoryErrorCode.INVALID_LIMIT);
        }
        DateRange range = DateRange.of(from, to, clock);

        // 판매는 음수, 환불 복원은 양수로 저장돼 있어 둘을 더하면 −(순판매)다. 0 이하인 상품은 순위에 넣지 않는다
        Map<Long, Integer> soldByProduct = new HashMap<>();
        for (ProductLedgerSum sum : ledgerRepository.sumByProductAndType(
                storeId, range.fromInclusive(), range.toExclusive())) {
            if (sum.type() == LedgerType.SALE || sum.type() == LedgerType.RETURN) {
                soldByProduct.merge(sum.productId(), Math.toIntExact(-sum.quantity()), Integer::sum);
            }
        }
        soldByProduct.values().removeIf(sold -> sold <= 0);
        int total = soldByProduct.values().stream().mapToInt(Integer::intValue).sum();

        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(soldByProduct.keySet())
                .forEach(product -> products.put(product.getId(), product));

        List<Map.Entry<Long, Integer>> sorted = soldByProduct.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> nameOf(products, entry.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .toList();

        List<SalesRankItem> items = new ArrayList<>();
        for (int i = 0; i < sorted.size() && items.size() < size; i++) {
            Map.Entry<Long, Integer> entry = sorted.get(i);
            // 같은 수량이면 앞 상품과 같은 순위. 아니면 앞에 있는 상품 수 + 1 (1, 2, 2, 4)
            int rank = i > 0 && sorted.get(i - 1).getValue().equals(entry.getValue())
                    ? items.get(i - 1).rank()
                    : i + 1;
            items.add(toItem(rank, products.get(entry.getKey()), entry.getValue(), total));
        }

        return new SalesRankingResponse(range.start(), range.end(), total, items);
    }

    private static String nameOf(Map<Long, Product> products, Long productId) {
        Product product = products.get(productId);
        return product == null ? "" : product.getName();
    }

    private static SalesRankItem toItem(int rank, Product product, int sold, int total) {
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }
        return new SalesRankItem(
                rank,
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getCategory(),
                product.getImageUrl(),
                sold,
                Math.round(sold * 10000.0 / total) / 10000.0
        );
    }
}
