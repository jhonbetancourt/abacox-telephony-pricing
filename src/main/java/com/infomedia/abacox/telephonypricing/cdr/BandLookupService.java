// FILE: lookup/BandLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BandLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<Map<String, Object>> findBandByPrefixAndIndicator(Long prefixId, Long indicatorId, Long originIndicatorId) {
        if (prefixId == null) {
            log.trace("findBandByPrefixAndIndicator - Invalid input: prefixId is null");
            return Optional.empty();
        }
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding band for prefixId: {}, indicatorId: {}, effectiveOriginIndicatorId: {}", prefixId, indicatorId, effectiveOriginIndicatorId);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT b.id as band_id, b.value as band_value, b.vat_included as band_vat_included, b.name as band_name ");
        sqlBuilder.append("FROM band b ");
        sqlBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id ");
        sqlBuilder.append("WHERE b.active = true ");
        sqlBuilder.append("  AND b.prefix_id = :prefixId ");
        if (indicatorId != null) {
             sqlBuilder.append("  AND bi.indicator_id = :indicatorId ");
        } else {
             log.trace("IndicatorId is null, band lookup will likely find no match unless bands are linked to null/0 indicators.");
             // If null indicator should match bands linked to 0:
             // sqlBuilder.append(" AND bi.indicator_id = 0 ");
             // If null indicator means no band applies, return early:
             // return Optional.empty();
             // For now, let the query run; it will likely return empty.
        }
        sqlBuilder.append("  AND b.origin_indicator_id IN (0, :originIndicatorId) ");
        sqlBuilder.append("ORDER BY b.origin_indicator_id DESC ");
        sqlBuilder.append("LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("prefixId", prefixId);
        if (indicatorId != null) {
             query.setParameter("indicatorId", indicatorId);
        }
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("band_id", result[0]);
            map.put("band_value", result[1] != null ? result[1] : BigDecimal.ZERO);
            map.put("band_vat_included", result[2] != null ? result[2] : false);
            map.put("band_name", result[3]);
            log.trace("Found band {} for prefix {}, indicator {}", map.get("band_id"), prefixId, indicatorId);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No matching band found for prefix {}, indicator {}", prefixId, indicatorId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding band for prefixId: {}, indicatorId: {}, originIndicatorId: {}", prefixId, indicatorId, effectiveOriginIndicatorId, e);
            return Optional.empty();
        }
    }
}