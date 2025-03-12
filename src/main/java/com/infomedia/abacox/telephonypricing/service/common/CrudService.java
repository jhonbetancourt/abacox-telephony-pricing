package com.infomedia.abacox.telephonypricing.service.common;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.exception.ResourceDeletionException;
import com.infomedia.abacox.telephonypricing.exception.ResourceDisabledException;
import com.infomedia.abacox.telephonypricing.exception.ResourceNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public abstract class CrudService<E, I, R extends JpaRepository<E, I> & JpaSpecificationExecutor<E>> {

    @Getter
    private final R repository;

    public Optional<E> find(I id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    public E get(I id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(getEntityClass(), id));
    }

    public Page<E> find(Specification<E> specification, Pageable pageable) {
        return repository.findAll(specification, pageable);
    }

    public List<E> find(Specification<E> specification) {
        return repository.findAll(specification);
    }

    public Optional<E> findOne(Specification<E> specification) {
        return repository.findOne(specification);
    }

    public void deleteById(I id) {
        if (!getRepository().existsById(id)) {
            throw new ResourceNotFoundException(getEntityClass(), id);
        }
        try {
            repository.deleteById(id);
        } catch (Exception e) {
            throw new ResourceDeletionException(getEntityClass(), id, e);
        }
    }

    @Transactional
    public void deleteAllById(Collection<I> ids) {
        for (I id : ids) {
            deleteById(id);
        }
    }

    public E getActive(I id) {
        E entity = get(id);
        if (entity instanceof ActivableEntity activableEntity && !activableEntity.isActive()) {
            throw new ResourceDisabledException(getEntityClass(), id);
        } else {
            return entity;
        }
    }

    public Class<E> getEntityClass() {
        ParameterizedType type = (ParameterizedType) getClass().getGenericSuperclass();
        return (Class<E>) type.getActualTypeArguments()[0];
    }

    public List<E> getByIds(Collection<I> ids) {
        return repository.findAllById(ids);
    }

    public List<E> getAll() {
        return repository.findAll();
    }

    public E changeActivation(I id, boolean active) {
        E entity = get(id);
        if (entity instanceof ActivableEntity activableEntity) {
            activableEntity.setActive(active);
        } else {
            throw new UnsupportedOperationException("Entity of type " + getEntityClass().getSimpleName() + " does not support activation");
        }
        return repository.save(entity);
    }

    protected E save(E entity) {
        return repository.save(entity);
    }

    protected List<E> saveAll(Collection<E> entities) {
        return repository.saveAll(entities);
    }
}
