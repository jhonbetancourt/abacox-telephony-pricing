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
public class EntityLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public EntityLookupService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<CommunicationLocation> findCommunicationLocationById(Long id) {
        if (id == null || id <=0) return Optional.empty();
        String sql = "SELECT cl.* FROM communication_location cl WHERE cl.id = :id AND cl.active = true";
        Query query = entityManager.createNativeQuery(sql, CommunicationLocation.class);
        query.setParameter("id", id);
        try { return Optional.of((CommunicationLocation) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<Indicator> findIndicatorById(Long id) {
        if (id == null || id <= 0) {
             log.info("findIndicatorById requested for invalid ID: {}", id);
             return Optional.empty();
        }
        String sql = "SELECT i.* FROM indicator i WHERE i.id = :id AND i.active = true";
        Query query = entityManager.createNativeQuery(sql, Indicator.class);
        query.setParameter("id", id);
        try { return Optional.of((Indicator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<Operator> findOperatorById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT o.* FROM operator o WHERE o.id = :id AND o.active = true";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("id", id);
        try { return Optional.of((Operator) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<TelephonyType> findTelephonyTypeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT tt.* FROM telephony_type tt WHERE tt.id = :id AND tt.active = true";
        Query query = entityManager.createNativeQuery(sql, TelephonyType.class);
        query.setParameter("id", id);
        try { return Optional.of((TelephonyType) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<OriginCountry> findOriginCountryById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT oc.* FROM origin_country oc WHERE oc.id = :id AND oc.active = true";
        Query query = entityManager.createNativeQuery(sql, OriginCountry.class);
        query.setParameter("id", id);
        try { return Optional.of((OriginCountry) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<Subdivision> findSubdivisionById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT s.* FROM subdivision s WHERE s.id = :id AND s.active = true";
        Query query = entityManager.createNativeQuery(sql, Subdivision.class);
        query.setParameter("id", id);
        try { return Optional.of((Subdivision) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<CostCenter> findCostCenterById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT cc.* FROM cost_center cc WHERE cc.id = :id AND cc.active = true";
        Query query = entityManager.createNativeQuery(sql, CostCenter.class);
        query.setParameter("id", id);
        try { return Optional.of((CostCenter) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }
}