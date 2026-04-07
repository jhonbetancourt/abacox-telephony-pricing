package com.infomedia.abacox.telephonypricing.service.common;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.exception.ResourceDeletionException;
import com.infomedia.abacox.telephonypricing.exception.ResourceDisabledException;
import com.infomedia.abacox.telephonypricing.exception.ResourceNotFoundException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public abstract class CrudService<E, I, R extends JpaRepository<E, I> & JpaSpecificationExecutor<E>> {

    @Getter
    private final R repository;
    @PersistenceContext
    private EntityManager entityManager;

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

    public void exportExcelStreaming(Specification<E> spec, Pageable pageable,
                                      OutputStream outputStream, ExcelGeneratorBuilder builder) {
        Sort sort = pageable.getSort();
        try {
            builder.generateStreaming(outputStream, (page, size) ->
                    findAsSlice(spec, PageRequest.of(page, size, sort)).getContent(),
                    ExcelGeneratorBuilder.DEFAULT_STREAMING_PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Slice<E> findAsSlice(Specification<E> spec, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<E> query = cb.createQuery(getEntityClass());
        Root<E> root = query.from(getEntityClass());

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> orders.add(order.isAscending()
                    ? cb.asc(root.get(order.getProperty()))
                    : cb.desc(root.get(order.getProperty()))));
            query.orderBy(orders);
        }

        List<E> result = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize() + 1)
                .getResultList();

        boolean hasNext = result.size() > pageable.getPageSize();
        if (hasNext) {
            result = result.subList(0, pageable.getPageSize());
        }

        return new SliceImpl<>(result, pageable, hasNext);
    }
}
