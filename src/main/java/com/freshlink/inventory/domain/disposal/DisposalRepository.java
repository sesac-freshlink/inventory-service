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
}
