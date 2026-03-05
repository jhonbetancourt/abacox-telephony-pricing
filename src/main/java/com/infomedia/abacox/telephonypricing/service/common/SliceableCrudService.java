package com.infomedia.abacox.telephonypricing.service.common;

import com.infomedia.abacox.telephonypricing.db.repository.SliceableSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public abstract class SliceableCrudService<E, I, R extends JpaRepository<E, I> & JpaSpecificationExecutor<E> & SliceableSpecificationExecutor<E>>
        extends CrudService<E, I, R> {

    protected SliceableCrudService(R repository) {
        super(repository);
    }

    public Slice<E> findAsSlice(Specification<E> specification, Pageable pageable) {
        return getRepository().findAllAsSlice(specification, pageable);
    }
}
