// FILE: lookup/SpecialLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.SpecialRateValue;
import com.infomedia.abacox.telephonypricing.entity.SpecialService;
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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecialLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public List<SpecialRateValue> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId, LocalDateTime callDateTime) {
        if (callDateTime == null) return Collections.emptyList();
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        log.debug("Finding special rate values for ttId: {}, opId: {}, bandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, bandId, effectiveOriginIndicatorId, callDateTime);

        int dayOfWeek = callDateTime.getDayOfWeek().getValue();
        boolean isHoliday = false; // Placeholder - Implement holiday lookup if needed

        String dayColumn;
        switch (dayOfWeek) {
            case 1: dayColumn = "monday_enabled"; break;
            case 2: dayColumn = "tuesday_enabled"; break;
            case 3: dayColumn = "wednesday_enabled"; break;
            case 4: dayColumn = "thursday_enabled"; break;
            case 5: dayColumn = "friday_enabled"; break;
            case 6: dayColumn = "saturday_enabled"; break;
            case 7: dayColumn = "sunday_enabled"; break;
            default:
                log.error("Invalid day of week: {}", dayOfWeek);
                return Collections.emptyList();
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.* ");
        sqlBuilder.append("FROM special_rate_value srv ");
        sqlBuilder.append("WHERE srv.active = true ");
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL) ");
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL) ");
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL) ");
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) ");
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        sqlBuilder.append("  AND (srv.").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        sqlBuilder.append("ORDER BY srv.origin_indicator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.telephony_type_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.operator_id DESC NULLS LAST, ");
        sqlBuilder.append("         srv.band_id DESC NULLS LAST");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), SpecialRateValue.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("bandId", bandId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("callDateTime", callDateTime);

        try {
            List<SpecialRateValue> results = query.getResultList();
            log.trace("Found {} special rate candidates", results.size());
            return results;
        } catch (Exception e) {
            log.error("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                    telephonyTypeId, operatorId, bandId, effectiveOriginIndicatorId, callDateTime, e);
            return Collections.emptyList();
        }
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        if (!StringUtils.hasText(phoneNumber) || originCountryId == null) {
            log.trace("findSpecialService - Invalid input: phone={}, indId={}, ocId={}", phoneNumber, indicatorId, originCountryId);
            return Optional.empty();
        }
        Long effectiveIndicatorId = indicatorId != null ? indicatorId : 0L;
        log.debug("Finding special service for number: {}, effectiveIndicatorId: {}, originCountryId: {}", phoneNumber, effectiveIndicatorId, originCountryId);

        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true " +
                "  AND ss.phone_number = :phoneNumber " +
                "  AND ss.indicator_id IN (0, :indicatorId) " +
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY ss.indicator_id DESC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", effectiveIndicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            SpecialService service = (SpecialService) query.getSingleResult();
            return Optional.of(service);
        } catch (NoResultException e) {
            log.trace("No special service found for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding special service for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId, e);
            return Optional.empty();
        }
    }
}