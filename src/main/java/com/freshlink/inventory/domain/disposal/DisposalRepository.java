package com.freshlink.inventory.domain.disposal;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisposalRepository extends JpaRepository<Disposal, Long> {

    /** S-33 폐기 요청 이력. 최신순 */
    List<Disposal> findByStoreIdOrderByIdDesc(Long storeId, Pageable pageable);

    List<Disposal> findByStoreIdAndStatusOrderByIdDesc(
            Long storeId,
            DisposalStatus status,
            Pageable pageable
    );

    /** 경과 lot 한 번에 폐기: 아직 처리 전(REQUESTED)인 요청 수량을 빼고 남은 만큼만 다시 요청하려고 읽는다 */
    List<Disposal> findByStoreIdAndStatus(Long storeId, DisposalStatus status);
}
