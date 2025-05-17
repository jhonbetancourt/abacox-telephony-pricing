package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@Transactional(readOnly = true)
public class PricingRuleLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<BigDecimal> findVatForTelephonyOperator(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        String sql = "SELECT p.vat_value FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND p.operator_id = :operatorId " +
                "  AND o.origin_country_id = :originCountryId " +
                "  AND p.active = true AND o.active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((BigDecimal) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public List<SpecialRateValue> findSpecialRateValues(LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId) {
        String dayOfWeekField = callTime.getDayOfWeek().name().toLowerCase() + "_enabled";

        String sql = "SELECT srv.* FROM special_rate_value srv " +
                "WHERE srv.active = true " +
                "  AND (srv.valid_from IS NULL OR srv.valid_from <= :callTime) " +
                "  AND (srv.valid_to IS NULL OR srv.valid_to >= :callTime) " +
                "  AND srv." + dayOfWeekField + " = true ";

        if (telephonyTypeId != null)
            sql += " AND (srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 OR srv.telephony_type_id = :telephonyTypeId) ";
        if (operatorId != null)
            sql += " AND (srv.operator_id IS NULL OR srv.operator_id = 0 OR srv.operator_id = :operatorId) ";
        if (bandId != null) sql += " AND (srv.band_id IS NULL OR srv.band_id = 0 OR srv.band_id = :bandId) ";
        if (originIndicatorId != null)
            sql += " AND (srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 OR srv.origin_indicator_id = :originIndicatorId) ";

        sql += "ORDER BY CASE WHEN srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 THEN 1 ELSE 0 END, srv.origin_indicator_id DESC, " +
                "CASE WHEN srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0 THEN 1 ELSE 0 END, srv.telephony_type_id DESC, " +
                "CASE WHEN srv.operator_id IS NULL OR srv.operator_id = 0 THEN 1 ELSE 0 END, srv.operator_id DESC, " +
                "CASE WHEN srv.band_id IS NULL OR srv.band_id = 0 THEN 1 ELSE 0 END, srv.band_id DESC, " +
                "srv.id";

        Query query = entityManager.createNativeQuery(sql, SpecialRateValue.class);
        query.setParameter("callTime", callTime);
        if (telephonyTypeId != null) query.setParameter("telephonyTypeId", telephonyTypeId);
        if (operatorId != null) query.setParameter("operatorId", operatorId);
        if (bandId != null) query.setParameter("bandId", bandId);
        if (originIndicatorId != null) query.setParameter("originIndicatorId", originIndicatorId);

        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<PbxSpecialRule> findPbxSpecialRules(Long commLocationId) {
        String sql = "SELECT psr.* FROM pbx_special_rule psr " +
                "WHERE psr.active = true AND (psr.comm_location_id IS NULL OR psr.comm_location_id = :commLocationId) " +
                "ORDER BY CASE WHEN psr.comm_location_id IS NULL THEN 1 ELSE 0 END, psr.comm_location_id DESC, " +
                "LENGTH(psr.search_pattern) DESC, psr.id";
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId == null ? 0L : commLocationId);
        return query.getResultList();
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true AND ss.phone_number = :phoneNumber " +
                "  AND (ss.indicator_id IS NULL OR ss.indicator_id = 0 OR ss.indicator_id = :indicatorId) " +
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY CASE WHEN ss.indicator_id IS NULL OR ss.indicator_id = 0 THEN 1 ELSE 0 END, ss.indicator_id DESC, ss.id LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", indicatorId == null ? 0L : indicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((SpecialService) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Band> findBandsForPrefixAndIndicator(Long prefixId, Long originIndicatorId) {
        String sql = "SELECT b.* FROM band b " +
                "WHERE b.active = true AND b.prefix_id = :prefixId ";
        if (originIndicatorId != null) {
            sql += "AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorId) ";
        }
        sql += "ORDER BY CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END, b.origin_indicator_id DESC, b.id";

        Query query = entityManager.createNativeQuery(sql, Band.class);
        query.setParameter("prefixId", prefixId);
        if (originIndicatorId != null) {
            query.setParameter("originIndicatorId", originIndicatorId);
        }
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<TrunkRule> findTrunkRules(Long trunkId, Long telephonyTypeId, String indicatorIdToMatch, Long originIndicatorId) {
        String sql = "SELECT tr.* FROM trunk_rule tr " +
                "WHERE tr.active = true " +
                "  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR tr.trunk_id = :trunkId) " +
                "  AND tr.telephony_type_id = :telephonyTypeId " +
                "  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) " +
                "  AND (tr.indicator_ids = '' OR tr.indicator_ids = :indicatorIdToMatch OR " +
                "       tr.indicator_ids LIKE :indicatorIdToMatch || ',%' OR " +
                "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch OR " +
                "       tr.indicator_ids LIKE '%,' || :indicatorIdToMatch || ',%') " +
                "ORDER BY CASE WHEN tr.trunk_id IS NULL OR tr.trunk_id = 0 THEN 1 ELSE 0 END, tr.trunk_id DESC, " +
                "CASE WHEN tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 THEN 1 ELSE 0 END, tr.origin_indicator_id DESC, " +
                "LENGTH(tr.indicator_ids) DESC, tr.id";

        Query query = entityManager.createNativeQuery(sql, TrunkRule.class);
        query.setParameter("trunkId", trunkId == null ? 0L : trunkId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", originIndicatorId == null ? 0L : originIndicatorId);
        query.setParameter("indicatorIdToMatch", indicatorIdToMatch == null ? "" : indicatorIdToMatch);

        return query.getResultList();
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.active = true " +
                "  AND tr.trunk_id = :trunkId " +
                "  AND tr.operator_id = :operatorId " +
                "  AND tr.telephony_type_id = :telephonyTypeId " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, TrunkRate.class);
        query.setParameter("trunkId", trunkId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        try {
            return Optional.ofNullable((TrunkRate) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
