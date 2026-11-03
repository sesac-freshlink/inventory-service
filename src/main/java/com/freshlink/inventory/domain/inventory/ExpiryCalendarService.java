package com.freshlink.inventory.domain.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 유통기한 달력. 한 달 동안 기한이 끝나는 lot을 DB에서 그 달 범위로만 읽고(기한 순) 날짜별로 묶는다.
 * 날짜마다 lot 목록까지 같이 줘서 화면이 날짜를 누를 때 다시 묻지 않는다.
 * 상태는 저장된 status(자정 스케줄러가 바꾸는 표시용)가 아니라 InventoryCalculationService 규칙으로 지금 계산한다
 */
@Service
@Transactional(readOnly = true)
public class ExpiryCalendarService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final InventoryLotRepository inventoryLotRepository;
    private final InventoryCalculationService calculationService;
    private final Clock clock;

    public ExpiryCalendarService(
            InventoryLotRepository inventoryLotRepository,
            InventoryCalculationService calculationService,
            Clock clock
    ) {
        this.inventoryLotRepository = inventoryLotRepository;
        this.calculationService = calculationService;
        this.clock = clock;
    }

    /** month를 빼면 이번 달(한국 날짜) */
    public ExpiryCalendarResponse getCalendar(Long storeId, YearMonth month) {
        YearMonth target = month != null ? month : YearMonth.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);

        List<ExpiryLotRow> rows = inventoryLotRepository.findExpiringBetween(
                storeId,
                target.atDay(1).atStartOfDay(),
                target.plusMonths(1).atDay(1).atStartOfDay()
        );

        // 기한 순으로 오므로 넣은 순서가 곧 날짜 순이다
        Map<LocalDate, List<ExpiryCalendarResponse.Lot>> byDate = new LinkedHashMap<>();
        for (ExpiryLotRow row : rows) {
            byDate.computeIfAbsent(row.expiryDate().toLocalDate(), date -> new java.util.ArrayList<>())
                    .add(new ExpiryCalendarResponse.Lot(
                            row.lotId(),
                            row.productId(),
                            row.productCode(),
                            row.name(),
                            row.expiryDate().atZone(SEOUL).toOffsetDateTime(),
                            row.quantity(),
                            row.reservedQuantity(),
                            calculationService.calculateStatus(row.expiryDate(), now)
                    ));
        }

        List<ExpiryCalendarResponse.Day> days = byDate.entrySet().stream()
                .map(entry -> new ExpiryCalendarResponse.Day(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().mapToInt(ExpiryCalendarResponse.Lot::quantity).sum(),
                        entry.getValue()
                ))
                .toList();

        return new ExpiryCalendarResponse(target.toString(), days);
    }
}
