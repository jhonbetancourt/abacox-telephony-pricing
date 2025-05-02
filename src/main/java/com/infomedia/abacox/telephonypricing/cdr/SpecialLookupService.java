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
// import org.springframework.cache.annotation.Cacheable; // Consider caching
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
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

    /**
     * Finds potential SpecialRateValue candidates based on call attributes.
     * Matches the query logic in PHP's Obtener_ValorEspecial.
     *
     * @param telephonyTypeId   The call's telephony type ID.
     * @param operatorId        The call's operator ID.
     * @param bandId            The call's band ID (can be null or 0).
     * @param originIndicatorId The call's origin indicator ID.
     * @param callDateTime      The timestamp of the call.
     * @return A list of candidate SpecialRateValue entities, ordered by specificity.
     */
    
    public List<SpecialRateValue> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorId, LocalDateTime callDateTime) {
        if (callDateTime == null || telephonyTypeId == null || operatorId == null) {
             log.trace("findSpecialRateValues - Invalid input: ttId={}, opId={}, dateTime={}", telephonyTypeId, operatorId, callDateTime);
            return Collections.emptyList();
        }
        // Use 0L for null IDs in the query where applicable (like originIndicatorId, bandId)
        Long effectiveOriginIndicatorId = originIndicatorId != null ? originIndicatorId : 0L;
        Long effectiveBandId = bandId != null ? bandId : 0L;

        log.debug("Finding special rate values for ttId: {}, opId: {}, effectiveBandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                telephonyTypeId, operatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime);

        // Determine day of week and holiday status
        DayOfWeek day = callDateTime.getDayOfWeek();
        boolean isHoliday = isHoliday(callDateTime); // Placeholder - Implement actual holiday lookup

        String dayColumn;
        switch (day) {
            case MONDAY: dayColumn = "monday_enabled"; break;
            case TUESDAY: dayColumn = "tuesday_enabled"; break;
            case WEDNESDAY: dayColumn = "wednesday_enabled"; break;
            case THURSDAY: dayColumn = "thursday_enabled"; break;
            case FRIDAY: dayColumn = "friday_enabled"; break;
            case SATURDAY: dayColumn = "saturday_enabled"; break;
            case SUNDAY: dayColumn = "sunday_enabled"; break;
            default:
                log.error("Invalid day of week: {}", day);
                return Collections.emptyList();
        }

        // Build the query mirroring PHP's logic and ordering
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.* ");
        sqlBuilder.append("FROM special_rate_value srv ");
        // LEFT JOIN prefix p ON (srv.telephony_type_id = p.telephone_type_id AND srv.operator_id = p.operator_id) "); // Join needed only if fetching IVA from Prefix
        sqlBuilder.append("WHERE srv.active = true ");
        // Match specific or NULL values for type, operator, band, origin indicator
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL) ");
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL) ");
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL OR srv.band_id = 0) "); // Allow matching 0 or NULL band
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) "); // Allow matching 0 or NULL origin
        // Match date validity range
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        // Match day of week OR holiday
        sqlBuilder.append("  AND (srv.").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        // Order by specificity (PHP: ORDER BY VALORESPECIAL_INDICAORIGEN_ID desc, VALORESPECIAL_TIPOTELE_ID, VALORESPECIAL_OPERADOR_ID, VALORESPECIAL_BANDA_ID)
        // We prioritize more specific matches (non-NULL IDs) first.
        sqlBuilder.append("ORDER BY ");
        sqlBuilder.append("  CASE WHEN srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0 THEN 1 ELSE 0 END, srv.origin_indicator_id DESC, "); // Specific origin first
        sqlBuilder.append("  CASE WHEN srv.telephony_type_id IS NULL THEN 1 ELSE 0 END, srv.telephony_type_id DESC, "); // Specific type first
        sqlBuilder.append("  CASE WHEN srv.operator_id IS NULL THEN 1 ELSE 0 END, srv.operator_id DESC, "); // Specific operator first
        sqlBuilder.append("  CASE WHEN srv.band_id IS NULL OR srv.band_id = 0 THEN 1 ELSE 0 END, srv.band_id DESC"); // Specific band first

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), SpecialRateValue.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("bandId", effectiveBandId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("callDateTime", callDateTime);

        try {
            List<SpecialRateValue> results = query.getResultList();
            log.trace("Found {} special rate candidates", results.size());
            return results;
        } catch (Exception e) {
            log.error("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                    telephonyTypeId, operatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime, e);
            return Collections.emptyList();
        }
    }

    /**
     * Finds a specific SpecialService based on phone number, indicator, and origin country.
     * Matches PHP's buscar_NumeroEspecial logic.
     *
     * @param phoneNumber     The exact phone number to match.
     * @param indicatorId     The indicator ID (can be null).
     * @param originCountryId The origin country ID.
     * @return Optional containing the matching SpecialService.
     */
    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        if (!StringUtils.hasText(phoneNumber) || originCountryId == null) {
            log.trace("findSpecialService - Invalid input: phone={}, indId={}, ocId={}", phoneNumber, indicatorId, originCountryId);
            return Optional.empty();
        }
        // PHP logic prioritizes specific indicator match over the generic (0) match.
        Long effectiveIndicatorId = indicatorId != null ? indicatorId : 0L;
        log.debug("Finding special service for number: {}, effectiveIndicatorId: {}, originCountryId: {}", phoneNumber, effectiveIndicatorId, originCountryId);

        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true " +
                "  AND ss.phone_number = :phoneNumber " +
                "  AND ss.indicator_id IN (0, :indicatorId) " + // Match specific or 0
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY ss.indicator_id DESC " + // Prioritize specific indicator match (non-zero)
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

    // --- Helper Methods ---

    /**
     * Placeholder for holiday lookup logic.
     * Implement this method to check against a holiday calendar/database.
     *
     * @param dateTime The date and time to check.
     * @return True if the date is a holiday, false otherwise.
     */
    private boolean isHoliday(LocalDateTime dateTime) {
        // TODO: Implement actual holiday lookup logic based on date and potentially country/region.
        // Example: Check against a Set<LocalDate> of holidays or a database table.
        return false;
    }
}