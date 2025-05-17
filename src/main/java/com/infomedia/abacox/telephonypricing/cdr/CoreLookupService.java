// FILE: com/infomedia/abacox/telephonypricing/cdr/lookup/CoreLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Log4j2
@Transactional(readOnly = true)
public class CoreLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM communication_location WHERE id = :id AND active = true", CommunicationLocation.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CommunicationLocation) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Indicator> findIndicatorById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM indicator WHERE id = :id AND active = true", Indicator.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Indicator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Operator> findOperatorById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM operator WHERE id = :id AND active = true", Operator.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Operator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Operator> findInternalOperatorByTelephonyType(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        String sql = "SELECT o.* FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId AND o.active = true AND p.active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((Operator) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM telephony_type WHERE id = :id AND active = true", TelephonyType.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((TelephonyType) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM cost_center WHERE id = :id AND active = true", CostCenter.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CostCenter) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM subdivision WHERE id = :id AND active = true", Subdivision.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((Subdivision) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<CallCategory> findCallCategoryById(Long id) {
        if (id == null) return Optional.empty();
        Query query = entityManager.createNativeQuery("SELECT * FROM call_category WHERE id = :id AND active = true", CallCategory.class);
        query.setParameter("id", id);
        try {
            return Optional.ofNullable((CallCategory) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<Trunk> findTrunkByNameAndCommLocation(String trunkName, Long commLocationId) {
        if (trunkName == null || trunkName.isEmpty() || commLocationId == null) return Optional.empty();
        String sql = "SELECT * FROM trunk WHERE name = :trunkName AND comm_location_id = :commLocationId AND active = true";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkName", trunkName);
        query.setParameter("commLocationId", commLocationId);
        try {
            return Optional.ofNullable((Trunk) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public Optional<TelephonyTypeConfig> findTelephonyTypeConfigByNumberLength(Long telephonyTypeId, Long originCountryId, int numberLength) {
        if (telephonyTypeId == null || originCountryId == null) {
            return Optional.empty();
        }
        String sql = "SELECT ttc.* FROM telephony_type_config ttc " +
                "WHERE ttc.telephony_type_id = :telephonyTypeId " +
                "  AND ttc.origin_country_id = :originCountryId " +
                "  AND ttc.min_value <= :numberLength AND ttc.max_value >= :numberLength " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, TelephonyTypeConfig.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("numberLength", numberLength);
        try {
            return Optional.ofNullable((TelephonyTypeConfig) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}