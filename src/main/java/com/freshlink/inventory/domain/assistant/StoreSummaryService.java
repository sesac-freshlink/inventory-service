package com.freshlink.inventory.domain.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.inventory.domain.inventory.InventoryCalculationService;
import com.freshlink.inventory.domain.inventory.InventoryLot;
import com.freshlink.inventory.domain.inventory.InventoryLotRepository;
import com.freshlink.inventory.domain.inventory.InventoryLotStatus;
import com.freshlink.inventory.domain.inventory.LedgerRepository;
import com.freshlink.inventory.domain.inventory.LedgerType;
import com.freshlink.inventory.domain.inventory.ProductLedgerSum;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.safetystock.SafetyStock;
import com.freshlink.inventory.domain.safetystock.SafetyStockRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점포 하나의 요약을 만든다(문서 2장·4-1). 이 점포(storeId)의 lot·원장·안전재고만 읽고, 주문·고객 표는 읽지 않는다.
 * 같은 점포는 10분 동안 만든 요약을 다시 쓴다(파드마다 메모리). 그 사이 바뀐 재고는 다음 요약에 반영된다
 */
@Service
public class StoreSummaryService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** 시간대 묶음(문서 4-1 예시): 이름, 시작 시(포함), 끝 시(제외). 그 밖의 시간은 OTHER_HOURS */
    static final List<HourBucket> HOUR_BUCKETS = List.of(
            new HourBucket("07-11", 7, 11),
            new HourBucket("11-14", 11, 14),
            new HourBucket("14-17", 14, 17),
            new HourBucket("17-21", 17, 21));
    static final String OTHER_HOURS = "기타";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    record HourBucket(String name, int fromHour, int toHourExclusive) {
    }

    private record Cached(StoreSummary summary, LocalDateTime expiresAt) {
    }

    private final InventoryLotRepository lotRepository;
    private final ProductRepository productRepository;
    private final SafetyStockRepository safetyStockRepository;
    private final LedgerRepository ledgerRepository;
    private final AssistantSalesRepository salesRepository;
    private final InventoryCalculationService calculationService;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public StoreSummaryService(
            InventoryLotRepository lotRepository,
            ProductRepository productRepository,
            SafetyStockRepository safetyStockRepository,
            LedgerRepository ledgerRepository,
            AssistantSalesRepository salesRepository,
            InventoryCalculationService calculationService,
            Clock clock
    ) {
        this.lotRepository = lotRepository;
        this.productRepository = productRepository;
        this.safetyStockRepository = safetyStockRepository;
        this.ledgerRepository = ledgerRepository;
        this.salesRepository = salesRepository;
        this.calculationService = calculationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StoreSummary build(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Cached cached = cache.get(storeId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.summary();
        }
        StoreSummary summary = load(storeId, now);
        cache.put(storeId, new Cached(summary, now.plus(CACHE_TTL)));
        return summary;
    }

    private StoreSummary load(Long storeId, LocalDateTime now) {
        List<InventoryLot> lots = lotRepository.findByStoreId(storeId);
        Map<Long, List<InventoryLot>> lotsByProduct = lots.stream()
                .collect(Collectors.groupingBy(InventoryLot::getProductId));
        Map<Long, Product> products = productRepository.findAllById(lotsByProduct.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Integer> safetyStocks = safetyStockRepository.quantitiesByProduct(storeId);

        LocalDateTime end = now.plusSeconds(1);
        LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        Map<Long, Integer> soldThisMonth = netSales(ledgerRepository.sumByProductAndType(storeId, monthStart, end));
        Map<Long, Integer> soldLastMonth = netSales(
                ledgerRepository.sumByProductAndType(storeId, monthStart.minusMonths(1), monthStart));
        List<ProductLedgerSum> last30 = ledgerRepository.sumByProductAndType(storeId, now.minusDays(30), end);
        Map<Long, Integer> inbound30 = sumOf(last30, LedgerType.INBOUND, 1);
        Map<Long, Integer> disposed30 = sumOf(last30, LedgerType.DISPOSE, -1);

        LocalDate firstDay = now.toLocalDate().minusDays(13);
        Map<Long, Map<LocalDate, Integer>> daily = new HashMap<>();
        salesRepository.dailySales(storeId, firstDay.atStartOfDay(), end)
                .forEach(s -> daily.computeIfAbsent(s.productId(), k -> new HashMap<>()).put(s.date(), s.quantity()));
        Map<Long, Map<String, Integer>> hourly = new HashMap<>();
        salesRepository.hourlySales(storeId, now.minusDays(7), end)
                .forEach(s -> hourly.computeIfAbsent(s.productId(), k -> new LinkedHashMap<>())
                        .merge(bucketOf(s.hour()), s.quantity(), Integer::sum));

        List<StoreSummary.ProductSummary> productSummaries = lotsByProduct.keySet().stream()
                .map(products::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Product::getName))
                .map(p -> new StoreSummary.ProductSummary(
                        p.getId(),
                        p.getName(),
                        p.getCategory(),
                        p.getPrice(),
                        calculationService.calculateAvailableQuantity(lotsByProduct.get(p.getId())),
                        safetyStocks.getOrDefault(p.getId(), SafetyStock.DEFAULT_QUANTITY),
                        soldThisMonth.getOrDefault(p.getId(), 0),
                        soldLastMonth.getOrDefault(p.getId(), 0),
                        dailySeries(daily.get(p.getId()), firstDay),
                        inbound30.getOrDefault(p.getId(), 0),
                        disposed30.getOrDefault(p.getId(), 0),
                        hourly.getOrDefault(p.getId(), Map.of())))
                .toList();

        List<StoreSummary.LotSummary> lotSummaries = lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .filter(lot -> {
                    InventoryLotStatus status = calculationService.calculateStatus(lot);
                    return status == InventoryLotStatus.IMMINENT || status == InventoryLotStatus.EXPIRED;
                })
                .sorted(Comparator.comparing(InventoryLot::getExpiryDate).thenComparing(InventoryLot::getId))
                .map(lot -> new StoreSummary.LotSummary(
                        lot.getProductId(),
                        products.containsKey(lot.getProductId()) ? products.get(lot.getProductId()).getName() : null,
                        lot.getQuantity(),
                        lot.getExpiryDate().atZone(SEOUL).toOffsetDateTime(),
                        calculationService.calculateStatus(lot).name()))
                .toList();

        return new StoreSummary(
                new StoreSummary.Store(storeId, now.atZone(SEOUL).toOffsetDateTime()),
                productSummaries,
                lotSummaries);
    }

    /** 판매(음수)와 환불 복원(양수)을 더해 부호를 뒤집는다. 0 아래면 0 */
    private static Map<Long, Integer> netSales(List<ProductLedgerSum> sums) {
        Map<Long, Integer> net = new HashMap<>();
        for (ProductLedgerSum sum : sums) {
            if (sum.type() == LedgerType.SALE || sum.type() == LedgerType.RETURN) {
                net.merge(sum.productId(), Math.toIntExact(-sum.quantity()), Integer::sum);
            }
        }
        net.replaceAll((id, quantity) -> Math.max(0, quantity));
        return net;
    }

    private static Map<Long, Integer> sumOf(List<ProductLedgerSum> sums, LedgerType type, int sign) {
        return sums.stream()
                .filter(sum -> sum.type() == type)
                .collect(Collectors.toMap(ProductLedgerSum::productId,
                        sum -> Math.toIntExact(sign * sum.quantity()), Integer::sum));
    }

    /** 14일 날짜별 판매(오래된 날부터). 모두 0이면 빈 목록으로 요약 크기를 줄인다 */
    private static List<Integer> dailySeries(Map<LocalDate, Integer> byDate, LocalDate firstDay) {
        if (byDate == null || byDate.values().stream().allMatch(q -> q <= 0)) {
            return List.of();
        }
        List<Integer> series = new ArrayList<>(14);
        for (int i = 0; i < 14; i++) {
            series.add(Math.max(0, byDate.getOrDefault(firstDay.plusDays(i), 0)));
        }
        return series;
    }

    static String bucketOf(int hour) {
        return HOUR_BUCKETS.stream()
                .filter(b -> hour >= b.fromHour() && hour < b.toHourExclusive())
                .map(HourBucket::name)
                .findFirst()
                .orElse(OTHER_HOURS);
    }
}
