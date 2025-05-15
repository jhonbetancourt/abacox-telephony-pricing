// FILE: com/infomedia/abacox/telephonypricing/cdr/TrunkLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Trunk;
import com.infomedia.abacox.telephonypricing.entity.TrunkRate;
import com.infomedia.abacox.telephonypricing.entity.TrunkRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Log4j2
@Transactional(readOnly = true)
public class TrunkLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public TrunkLookupService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Trunk> findTrunkByCode(String trunkCode, Long commLocationId) {
        if (!StringUtils.hasText(trunkCode) || commLocationId == null) {
            log.info("findTrunkByCode - Invalid input: trunkCode={}, commLocationId={}", trunkCode, commLocationId);
            return Optional.empty();
        }
        log.info("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        String sql = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true " +
                "  AND UPPER(t.name) = UPPER(:trunkCode) " +
                "  AND (t.comm_location_id = :commLocationId OR t.comm_location_id = 0 OR t.comm_location_id IS NULL) " +
                "ORDER BY CASE WHEN t.comm_location_id IS NOT NULL AND t.comm_location_id != 0 THEN 0 ELSE 1 END ASC, t.id DESC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkCode", trunkCode);
        query.setParameter("commLocationId", commLocationId);
        try {
            Trunk trunk = (Trunk) query.getSingleResult();
            return Optional.of(trunk);
        } catch (NoResultException e) {
            log.info("No active trunk found for code '{}' at location {} or globally", trunkCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding trunk for code: '{}', loc: {}", trunkCode, commLocationId, e);
            return Optional.empty();
        }
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        if (trunkId == null || operatorId == null || telephonyTypeId == null) {
            log.info("findTrunkRate - Invalid input: trunkId={}, opId={}, ttId={}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        }
        log.info("Finding trunk rate for trunkId: {}, operatorId: {}, telephonyTypeId: {}", trunkId, operatorId, telephonyTypeId);
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
            TrunkRate rate = (TrunkRate) query.getSingleResult();
            return Optional.of(rate);
        } catch (NoResultException e) {
            log.info("No active TrunkRate found for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding trunk rate for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId, e);
            return Optional.empty();
        }
    }

    public Optional<TrunkRule> findTrunkRule(String trunkName, Long telephonyTypeId, Long indicatorId, Long originCommLocationIndicatorId) {
        if (telephonyTypeId == null) {
            log.info("findTrunkRule - Invalid input: ttId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L;
        String indicatorIdStr = (indicatorId != null && indicatorId > 0) ? String.valueOf(indicatorId) : null;

        log.info("Finding trunk rule for trunkName: '{}', ttId: {}, indIdStr: {}, effectiveOriginIndId: {}",
                trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        sqlBuilder.append("  AND (tr.trunk_id = 0 OR tr.trunk_id IS NULL OR (t.name = :trunkName)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND (tr.origin_indicator_id = 0 OR tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = :originIndicatorId) ");

        if (indicatorIdStr != null) {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        } else {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL) ");
        }

        sqlBuilder.append("ORDER BY CASE WHEN tr.trunk_id IS NOT NULL AND tr.trunk_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN tr.origin_indicator_id IS NOT NULL AND tr.origin_indicator_id != 0 THEN 0 ELSE 1 END ASC, ");
        if (indicatorIdStr != null) {
            sqlBuilder.append("     CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
            sqlBuilder.append("          WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
            sqlBuilder.append("          WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 5 ");
            sqlBuilder.append("          ELSE 4 ");
            sqlBuilder.append("     END ASC, ");
        } else {
             sqlBuilder.append("     CASE WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 0 ELSE 1 END ASC, ");
        }
        sqlBuilder.append("     LENGTH(tr.indicator_ids) DESC "); // PHP logic implies longer, more specific indicator_ids strings might be preferred if other criteria are equal.
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkName", trunkName != null ? trunkName : "");
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        if (indicatorIdStr != null) {
            query.setParameter("indicatorIdStr", indicatorIdStr);
            query.setParameter("indicatorIdStrLikeStart", indicatorIdStr + ",%");
            query.setParameter("indicatorIdStrLikeEnd", "%," + indicatorIdStr);
            query.setParameter("indicatorIdStrLikeMiddle", "%," + indicatorIdStr + ",%");
        }

        try {
            TrunkRule rule = (TrunkRule) query.getSingleResult();
            return Optional.of(rule);
        } catch (NoResultException e) {
            log.info("No matching TrunkRule found for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding trunk rule for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkName, telephonyTypeId, indicatorIdStr, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }

    public Set<Long> getAllowedTelephonyTypesForTrunk(Long trunkId) {
        if (trunkId == null || trunkId <= 0) {
            log.info("getAllowedTelephonyTypesForTrunk - Invalid trunkId: {}", trunkId);
            return Collections.emptySet();
        }
        log.info("Fetching allowed telephony types for trunkId: {}", trunkId);
        String sql = "SELECT DISTINCT tr.telephony_type_id FROM trunk_rate tr WHERE tr.trunk_id = :trunkId AND tr.active = true";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("trunkId", trunkId);
        try {
            @SuppressWarnings("unchecked") // Native query returns List of Number or BigInteger
            List<Number> results = query.getResultList();
            Set<Long> typeIds = results.stream()
                                       .map(Number::longValue)
                                       .collect(Collectors.toSet());
            log.info("Found {} allowed telephony types for trunkId {}", typeIds.size(), trunkId);
            return typeIds;
        } catch (Exception e) {
            log.info("Error fetching allowed telephony types for trunkId {}: {}", trunkId, e.getMessage(), e);
            return Collections.emptySet();
        }
    }
}