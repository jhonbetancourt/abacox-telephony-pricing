package com.infomedia.abacox.telephonypricing.db.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;

public interface SliceableSpecificationExecutor<T> {
    Slice<T> findAllAsSlice(Specification<T> spec, Pageable pageable);
}
