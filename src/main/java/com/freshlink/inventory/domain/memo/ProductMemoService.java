package com.freshlink.inventory.domain.memo;

import com.freshlink.common.error.BusinessException;
import com.freshlink.inventory.common.error.InventoryErrorCode;
import com.freshlink.inventory.domain.product.Product;
import com.freshlink.inventory.domain.product.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 점주 상품 메모. 화면이 읽은 version과 지금 version이 다르면 409 MEMO_CHANGED로 막는다.
 * 비교는 두 번 한다: 먼저 읽은 행의 version과 비교하고(대부분 여기서 걸린다),
 * 그 뒤 저장 순간에 다른 요청이 끼어들면 JPA @Version이 UPDATE … WHERE version = ?로 한 번 더 잡는다
 */
@Service
public class ProductMemoService {

    private static final Logger log = LoggerFactory.getLogger(ProductMemoService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ProductMemoRepository memoRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public ProductMemoService(
            ProductMemoRepository memoRepository,
            ProductRepository productRepository,
            Clock clock
    ) {
        this.memoRepository = memoRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MemoResponse> getMemos(Long storeId) {
        List<ProductMemo> memos = memoRepository.findByStoreIdOrderByUpdatedAtDescIdDesc(storeId);
        Map<Long, Product> products = productRepository
                .findAllById(memos.stream().map(ProductMemo::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return memos.stream()
                .map(memo -> toResponse(products.get(memo.getProductId()), memo))
                .toList();
    }

    /** 없으면 만들고 있으면 고친다. version은 화면이 읽은 값(처음 만들 때는 null) */
    @Transactional
    public MemoResponse save(Long storeId, Long productId, String content, Long version) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(InventoryErrorCode.PRODUCT_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<ProductMemo> existing = memoRepository.findByStoreIdAndProductId(storeId, productId);

        ProductMemo memo;
        if (existing.isEmpty()) {
            // version을 들고 왔는데 행이 없다 = 화면이 읽은 뒤 다른 화면이 지웠다
            if (version != null) {
                throw changed(storeId, productId, version, null);
            }
            try {
                memo = memoRepository.saveAndFlush(ProductMemo.create(storeId, productId, content, now));
            } catch (DataIntegrityViolationException e) {
                // 두 화면이 동시에 처음 만들었다. 유니크 키(store_id, product_id)가 한쪽을 막는다
                throw changed(storeId, productId, null, null);
            }
        } else {
            memo = existing.get();
            if (!Objects.equals(memo.getVersion(), version)) {
                throw changed(storeId, productId, version, memo.getVersion());
            }
            memo.edit(content, now);
            try {
                // UPDATE … WHERE id = ? AND version = ?가 여기서 바로 나간다. 커밋 때까지 미루면 409로 못 바꾼다
                memoRepository.saveAndFlush(memo);
            } catch (OptimisticLockingFailureException e) {
                throw changed(storeId, productId, version, null);
            }
        }

        log.info("메모 저장: storeId={}, productId={}, version={}", storeId, productId, memo.getVersion());
        return toResponse(product, memo);
    }

    /** 이미 없으면 그대로 끝낸다. version을 주면 그 사이 다른 화면이 고친 메모는 지우지 않는다 */
    @Transactional
    public void delete(Long storeId, Long productId, Long version) {
        Optional<ProductMemo> existing = memoRepository.findByStoreIdAndProductId(storeId, productId);
        if (existing.isEmpty()) {
            return;
        }
        ProductMemo memo = existing.get();
        if (version != null && !version.equals(memo.getVersion())) {
            throw changed(storeId, productId, version, memo.getVersion());
        }
        try {
            memoRepository.delete(memo);
            memoRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw changed(storeId, productId, version, null);
        }
        log.info("메모 삭제: storeId={}, productId={}", storeId, productId);
    }

    private static BusinessException changed(Long storeId, Long productId, Long readVersion, Long currentVersion) {
        log.warn("메모 충돌: storeId={}, productId={}, 읽은 version={}, 지금 version={}",
                storeId, productId, readVersion, currentVersion);
        return new BusinessException(InventoryErrorCode.MEMO_CHANGED);
    }

    private static MemoResponse toResponse(Product product, ProductMemo memo) {
        return new MemoResponse(
                memo.getProductId(),
                product == null ? null : product.getProductCode(),
                product == null ? null : product.getName(),
                product == null ? null : product.getCategory(),
                memo.getContent(),
                memo.getVersion(),
                memo.getUpdatedAt().atZone(SEOUL).toOffsetDateTime()
        );
    }
}
