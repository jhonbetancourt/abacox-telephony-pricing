package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class SpecialRateValueLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    // HolidayLookupService would be needed for isHoliday check
    // private final HolidayLookupService holidayLookupService;

    @Transactional(readOnly = true)
    public Optional<SpecialRateInfo> getApplicableSpecialRate(LocalDateTime callDateTime, Long originIndicatorId,
                                                              Long telephonyTypeId, Long operatorId, Long bandId) {
        // PHP's Obtener_ValorEspecial
        if (telephonyTypeId == null || telephonyTypeId <= 0 || callDateTime == null) {
            return Optional.empty();
        }

        DayOfWeek dayOfWeek = callDateTime.getDayOfWeek();
        LocalTime callTime = callDateTime.toLocalTime();
        // boolean isHoliday = holidayLookupService.isHoliday(callDateTime.toLocalDate(), originCountryId); // Needs HolidayService
        boolean isHoliday = false; // Placeholder

        String dayColumn;
        switch (dayOfWeek) {
            case SUNDAY: dayColumn = "sunday_enabled"; break;
            case MONDAY: dayColumn = "monday_enabled"; break;
            case TUESDAY: dayColumn = "tuesday_enabled"; break;
            case WEDNESDAY: dayColumn = "wednesday_enabled"; break;
            case THURSDAY: dayColumn = "thursday_enabled"; break;
            case FRIDAY: dayColumn = "friday_enabled"; break;
            case SATURDAY: dayColumn = "saturday_enabled"; break;
            default: return Optional.empty();
        }

        String queryStr = "SELECT sr.rate_value, sr.includes_vat, sr.value_type, sr.hours_specification, " +
                          "COALESCE(p.vat_value, 0) as prefix_vat_rate " + // Get VAT from associated prefix
                          "FROM special_rate_value sr " +
                          "LEFT JOIN prefix p ON sr.telephony_type_id = p.telephony_type_id AND sr.operator_id = p.operator_id AND p.active = true " +
                          // LEFT JOIN operator o ON p.operator_id = o.id AND o.origin_country_id = :originCountryId ... (if prefix VAT depends on country)
                          "WHERE sr.active = true " +
                          "AND (sr.valid_from IS NULL OR sr.valid_from <= :callDateTime) " +
                          "AND (sr.valid_to IS NULL OR sr.valid_to >= :callDateTime) " +
                          "AND (" + dayColumn + " = true " + (isHoliday ? "OR sr.holiday_enabled = true" : "") + ") " +
                          "AND (sr.origin_indicator_id = 0 OR sr.origin_indicator_id = :originIndicatorId) " +
                          "AND (sr.telephony_type_id = 0 OR sr.telephony_type_id = :telephonyTypeId) " + // 0 for all types
                          "AND (sr.operator_id = 0 OR sr.operator_id = :operatorId) " + // 0 for all operators
                          "AND (sr.band_id = 0 OR sr.band_id = :bandId) " + // 0 for all bands
                          "ORDER BY sr.origin_indicator_id DESC, sr.telephony_type_id DESC, sr.operator_id DESC, sr.band_id DESC"; // Prefer more specific rules

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("callDateTime", callDateTime);
        nativeQuery.setParameter("originIndicatorId", originIndicatorId);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("operatorId", operatorId == null ? 0L : operatorId); // Default to 0 if null
        nativeQuery.setParameter("bandId", bandId == null ? 0L : bandId); // Default to 0 if null

        List<Tuple> results = nativeQuery.getResultList();

        for (Tuple row : results) {
            String hoursSpec = row.get("hours_specification", String.class);
            if (isTimeApplicable(callTime, hoursSpec)) {
                SpecialRateInfo sri = new SpecialRateInfo();
                sri.rateValue = row.get("rate_value", BigDecimal.class);
                sri.includesVat = row.get("includes_vat", Boolean.class);
                sri.valueType = row.get("value_type", Integer.class);
                sri.vatRate = row.get("prefix_vat_rate", BigDecimal.class);
                log.debug("Applicable special rate found for call at {}", callDateTime);
                return Optional.of(sri);
            }
        }
        return Optional.empty();
    }

    private boolean isTimeApplicable(LocalTime callTime, String hoursSpecification) {
        // PHP's ArregloHoras logic
        if (hoursSpecification == null || hoursSpecification.trim().isEmpty()) {
            return true; // No hour restriction
        }
        // Example format: "0-6,18-23" or "8,9,10"
        List<Integer> applicableHours = Arrays.stream(hoursSpecification.split(","))
            .flatMap(part -> {
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    List<Integer> hoursInRange = new ArrayList<>();
                    for (int h = start; h <= end; h++) hoursInRange.add(h);
                    return hoursInRange.stream();
                } else {
                    return Arrays.asList(Integer.parseInt(part.trim())).stream();
                }
            })
            .collect(Collectors.toList());
        
        return applicableHours.contains(callTime.getHour());
    }
}