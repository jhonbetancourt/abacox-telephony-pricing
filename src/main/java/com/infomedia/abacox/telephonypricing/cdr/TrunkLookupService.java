// FILE: lookup/TrunkLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Trunk;
import com.infomedia.abacox.telephonypricing.entity.TrunkRate;
import com.infomedia.abacox.telephonypricing.entity.TrunkRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrunkLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<Trunk> findTrunkByCode(String trunkCode, Long commLocationId) {
        if (!StringUtils.hasText(trunkCode) || commLocationId == null) {
            log.trace("findTrunkByCode - Invalid input: trunkCode={}, commLocationId={}", trunkCode, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding trunk by code: '{}', commLocationId: {}", trunkCode, commLocationId);
        String sql = "SELECT t.* FROM trunk t " +
                "WHERE t.active = true " +
                "  AND t.name = :trunkCode " +
                "  AND t.comm_location_id = :commLocationId " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Trunk.class);
        query.setParameter("trunkCode", trunkCode);
        query.setParameter("commLocationId", commLocationId);
        try {
            Trunk trunk = (Trunk) query.getSingleResult();
            return Optional.of(trunk);
        } catch (NoResultException e) {
            log.trace("No active trunk found for code '{}' at location {}", trunkCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk for code: '{}', loc: {}", trunkCode, commLocationId, e);
            return Optional.empty();
        }
    }

    public Optional<TrunkRate> findTrunkRate(Long trunkId, Long operatorId, Long telephonyTypeId) {
        if (trunkId == null || operatorId == null || telephonyTypeId == null) {
            log.trace("findTrunkRate - Invalid input: trunkId={}, opId={}, ttId={}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        }
        log.debug("Finding trunk rate for trunkId: {}, operatorId: {}, telephonyTypeId: {}", trunkId, operatorId, telephonyTypeId);
        String sql = "SELECT tr.* FROM trunk_rate tr " +
                "WHERE tr.trunk_id = :trunkId " +
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
            log.trace("No TrunkRate found for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rate for trunkId: {}, opId: {}, ttId: {}", trunkId, operatorId, telephonyTypeId, e);
            return Optional.empty();
        }
    }

    public Optional<TrunkRule> findTrunkRule(String trunkCode, Long telephonyTypeId, Long indicatorId, Long originIndicatorId) {
        if (telephonyTypeId == null) {
            log.trace("findTrunkRule - Invalid input: ttId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding trunk rule for trunkCode: '{}', ttId: {}, indId: {}, effectiveOriginIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId);
        String indicatorIdStr = indicatorId != null ? String.valueOf(indicatorId) : null;

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT tr.* ");
        sqlBuilder.append("FROM trunk_rule tr ");
        sqlBuilder.append("LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true ");
        sqlBuilder.append("WHERE tr.active = true ");
        sqlBuilder.append("  AND (tr.trunk_id IS NULL OR tr.trunk_id = 0 OR (t.name = :trunkCode)) ");
        sqlBuilder.append("  AND tr.telephony_type_id = :telephonyTypeId ");
        sqlBuilder.append("  AND (tr.origin_indicator_id IS NULL OR tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) ");

        if (indicatorIdStr != null) {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL OR tr.indicator_ids = :indicatorIdStr OR tr.indicator_ids LIKE :indicatorIdStrLikeStart OR tr.indicator_ids LIKE :indicatorIdStrLikeEnd OR tr.indicator_ids LIKE :indicatorIdStrLikeMiddle) ");
        } else {
            sqlBuilder.append("  AND (tr.indicator_ids = '' OR tr.indicator_ids IS NULL) ");
        }

        sqlBuilder.append("ORDER BY tr.trunk_id DESC NULLS LAST, ");
        sqlBuilder.append("         tr.origin_indicator_id DESC NULLS LAST, ");
        if (indicatorIdStr != null) {
            sqlBuilder.append("         CASE WHEN tr.indicator_ids = :indicatorIdStr THEN 0 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeStart THEN 1 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeMiddle THEN 2 ");
            sqlBuilder.append("              WHEN tr.indicator_ids LIKE :indicatorIdStrLikeEnd THEN 3 ");
            sqlBuilder.append("              WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 5 ");
            sqlBuilder.append("              ELSE 4 ");
            sqlBuilder.append("         END, ");
        } else {
             sqlBuilder.append("         CASE WHEN tr.indicator_ids = '' OR tr.indicator_ids IS NULL THEN 0 ELSE 1 END, ");
        }
        sqlBuilder.append("         LENGTH(tr.indicator_ids) DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), TrunkRule.class);
        query.setParameter("trunkCode", trunkCode != null ? trunkCode : "");
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
            log.trace("No matching TrunkRule found for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding trunk rule for trunk: '{}', ttId: {}, indId: {}, originIndId: {}", trunkCode, telephonyTypeId, indicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }
}