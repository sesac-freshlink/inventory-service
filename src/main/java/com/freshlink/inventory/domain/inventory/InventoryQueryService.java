package com.freshlink.inventory.domain.inventory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import com.freshlink.inventory.domain.product.StoreProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InventoryLotRepository inventoryLotRepository;
    private final ProductRepository productRepository;
    private final InventoryCalculationService calculationService;
    private final Clock clock;

    public InventoryQueryService(
            InventoryLotRepository inventoryLotRepository,
            ProductRepository productRepository,
            InventoryCalculationService calculationService,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.productRepository = productRepository;
        this.calculationService = calculationService;
        this.clock = clock;
    }

    public List<InventorySummaryResponse> getInventory(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<InventoryLot> lots = inventoryLotRepository.findByStoreId(storeId);

        if (lots.isEmpty()) {
            return List.of();
        }

        Map<Long, List<InventoryLot>> lotsByProduct = lots.stream()
                .collect(Collectors.groupingBy(InventoryLot::getProductId));

        Map<Long, Product> products = productRepository.findAllById(lotsByProduct.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return lotsByProduct.entrySet().stream()
                .map(entry -> toSummary(
                        products.get(entry.getKey()),
                        entry.getValue(),
                        now
                ))
                .sorted(
                        java.util.Comparator
                                .comparing(InventorySummaryResponse::name)
                                .thenComparing(InventorySummaryResponse::productId)
                )
                .toList();
    }

    private InventorySummaryResponse toSummary(
            Product product,
            List<InventoryLot> lots,
            LocalDateTime now
    ) {
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        int totalQuantity = lots.stream()
                .mapToInt(InventoryLot::getQuantity)
                .sum();

        int reservedQuantity = lots.stream()
                .mapToInt(InventoryLot::getReservedQuantity)
                .sum();

        int availableQuantity =
                calculationService.calculateAvailableQuantity(lots, now);

        OffsetDateTime nearestExpiry = lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .map(InventoryLot::getExpiryDate)
                .min(LocalDateTime::compareTo)
                .map(this::toOffsetDateTime)
                .orElse(null);

        int imminentLotCount = (int) lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .filter(lot ->
                        calculationService.calculateStatus(lot, now)
                                == InventoryLotStatus.IMMINENT
                )
                .count();

        int expiredLotCount = (int) lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .filter(lot ->
                        calculationService.calculateStatus(lot, now)
                                == InventoryLotStatus.EXPIRED
                )
                .count();

        return new InventorySummaryResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getCategory(),
                product.getPrice(),
                totalQuantity,
                reservedQuantity,
                availableQuantity,
                nearestExpiry,
                imminentLotCount,
                expiredLotCount
        );
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(SEOUL).toOffsetDateTime();
    }

    public List<InventoryLotResponse> getLots(Long storeId, Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        return inventoryLotRepository
                .findByStoreIdAndProductIdOrderByExpiryDateAscIdAsc(
                        storeId,
                        productId
                )
                .stream()
                .filter(lot -> lot.getQuantity() > 0)
                .map(lot -> toLotResponse(lot, now))
                .toList();
    }

    private InventoryLotResponse toLotResponse(
            InventoryLot lot,
            LocalDateTime now
    ) {
        InventoryLotStatus status =
                calculationService.calculateStatus(lot, now);

        int availableQuantity =
                status == InventoryLotStatus.EXPIRED
                        ? 0
                        : lot.getQuantity() - lot.getReservedQuantity();

        return new InventoryLotResponse(
                lot.getId(),
                toOffsetDateTime(lot.getExpiryDate()),
                lot.getQuantity(),
                lot.getReservedQuantity(),
                availableQuantity,
                status
        );
    }

    public List<ExpiringInventoryResponse> getExpiring(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<InventoryLot> lots = inventoryLotRepository.findByStoreId(storeId);

        if (lots.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> products = productRepository.findAllById(
                        lots.stream()
                                .map(InventoryLot::getProductId)
                                .collect(Collectors.toSet())
                )
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return lots.stream()
                .filter(lot -> lot.getQuantity() > 0)
                .filter(lot -> {
                    InventoryLotStatus status =
                            calculationService.calculateStatus(lot, now);

                    return status == InventoryLotStatus.IMMINENT
                            || status == InventoryLotStatus.EXPIRED;
                })
                .sorted(
                        java.util.Comparator
                                .comparing(InventoryLot::getExpiryDate)
                                .thenComparing(InventoryLot::getId)
                )
                .map(lot -> toExpiringResponse(
                        lot,
                        products.get(lot.getProductId()),
                        now
                ))
                .toList();
    }

    private ExpiringInventoryResponse toExpiringResponse(
            InventoryLot lot,
            Product product,
            LocalDateTime now
    ) {
        if (product == null) {
            throw new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND);
        }

        InventoryLotStatus status =
                calculationService.calculateStatus(lot, now);

        return new ExpiringInventoryResponse(
                lot.getId(),
                lot.getProductId(),
                product.getName(),
                toOffsetDateTime(lot.getExpiryDate()),
                status == InventoryLotStatus.EXPIRED,
                lot.getQuantity(),
                lot.getReservedQuantity(),
                lot.getQuantity() - lot.getReservedQuantity()
        );
    }

    public List<StoreProductResponse> getStoreProducts(Long storeId) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<InventoryLot> lots =
                inventoryLotRepository.findByStoreId(storeId);

        if (lots.isEmpty()) {
            return List.of();
        }

        Map<Long, List<InventoryLot>> lotsByProduct =
                lots.stream()
                        .collect(Collectors.groupingBy(
                                InventoryLot::getProductId
                        ));

        return productRepository.findAll()
                .stream()
                .map(product -> {
                    List<InventoryLot> productLots =
                            lotsByProduct.getOrDefault(
                                    product.getId(),
                                    List.of()
                            );

                    int availableQuantity =
                            calculationService.calculateAvailableQuantity(
                                    productLots,
                                    now
                            );

                    return new StoreProductResponse(
                            product.getId(),
                            product.getName(),
                            product.getCategory(),
                            product.getPrice(),
                            availableQuantity,
                            availableQuantity == 0
                    );
                })
                .sorted(
                        java.util.Comparator
                                .comparing(StoreProductResponse::name)
                                .thenComparing(StoreProductResponse::productId)
                )
                .toList();
    }
}