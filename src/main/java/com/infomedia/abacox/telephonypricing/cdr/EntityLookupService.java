// FILE: lookup/EntityLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for simple lookups of entities by their primary key.
 */
@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntityLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Cacheable("communicationLocationById")
    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        return findById(id, CommunicationLocation.class, "communication_location");
    }

    @Cacheable("operatorByIdLookup")
    public Optional<Operator> findOperatorById(Long id) {
        return findById(id, Operator.class, "operator");
    }

    @Cacheable("telephonyTypeByIdLookup")
    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        return findById(id, TelephonyType.class, "telephony_type");
    }

    @Cacheable("subdivisionById")
    public Optional<Subdivision> findSubdivisionById(Long id) {
        return findById(id, Subdivision.class, "subdivision");
    }

    @Cacheable("costCenterById")
    public Optional<CostCenter> findCostCenterById(Long id) {
        return findById(id, CostCenter.class, "cost_center");
    }

    // Generic findById method
    private <T> Optional<T> findById(Long id, Class<T> entityClass, String tableName) {
        if (id == null || id <= 0) {
            log.trace("findById requested for invalid ID {} for table {}", id, tableName);
            return Optional.empty();
        }
        // Basic check to prevent SQL injection, although parameter binding handles it
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
             log.error("Invalid table name provided for lookup: {}", tableName);
             return Optional.empty();
        }

        String sql = String.format("SELECT e.* FROM %s e WHERE e.id = :id", tableName);
        // Add active check if the entity extends ActivableEntity (requires reflection or separate methods)
        // For simplicity, assuming base lookup first. Add active check if needed.
        // String sql = String.format("SELECT e.* FROM %s e WHERE e.id = :id AND e.active = true", tableName);

        Query query = entityManager.createNativeQuery(sql, entityClass);
        query.setParameter("id", id);
        try {
            T entity = entityClass.cast(query.getSingleResult());
            return Optional.of(entity);
        } catch (NoResultException e) {
            log.trace("No entity found for ID {} in table {}", id, tableName);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding entity by ID {} in table {}: {}", id, tableName, e.getMessage(), e);
            return Optional.empty();
        }
    }
}