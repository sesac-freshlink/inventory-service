package com.freshlink.inventory.domain.clearance;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.ProductRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점주 마감세일 설정(분류별 적용 여부·할인율·판매 시간). 설정값만 다루고 재고(lot)는 바꾸지 않으므로 쪽지를 거치지 않는다.
 * 고객 응답·예약은 다음 요청부터 바뀐 설정을 읽는다(조회 표 store_product_stock은 설정과 상관없이 24시간 기준 수량만 담는다)
 */
@Service
@Transactional(readOnly = true)
public class ClearanceSettingService {

    private static final Logger log = LoggerFactory.getLogger(ClearanceSettingService.class);

    private final ClearanceSettingRepository clearanceSettingRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public ClearanceSettingService(
            ClearanceSettingRepository clearanceSettingRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.clearanceSettingRepository = clearanceSettingRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /** 상품 전체의 분류(가나다순)마다. 정하지 않은 분류는 기본값 */
    public List<ClearanceSettingResponse> getSettings(Long storeId) {
        Map<String, ClearanceSetting> saved = clearanceSettingRepository.findAll(storeId);
        return productRepository.findDistinctCategories().stream()
                .map(category -> saved.containsKey(category)
                        ? ClearanceSettingResponse.of(category, saved.get(category), true)
                        : ClearanceSettingResponse.of(category, ClearanceSetting.DEFAULT, false))
                .toList();
    }

    @Transactional
    public ClearanceSettingResponse update(Long storeId, ClearanceSettingRequest request) {
        if (!productRepository.findDistinctCategories().contains(request.category())) {
            throw new BusinessException(InventoryErrorCode.CATEGORY_NOT_FOUND);
        }
        if (request.saleStartHour() >= request.saleEndHour()) {
            throw new BusinessException(InventoryErrorCode.INVALID_CLEARANCE_SETTING);
        }
        ClearanceSetting setting = new ClearanceSetting(request.enabled(), request.discountPercent(),
                request.saleStartHour(), request.saleEndHour());
        clearanceSettingRepository.upsert(storeId, request.category(), setting, LocalDateTime.now(clock));

        log.info("마감세일 설정 변경: storeId={}, category={}, setting={}", storeId, request.category(), setting);

        return ClearanceSettingResponse.of(request.category(), setting, true);
    }
}
