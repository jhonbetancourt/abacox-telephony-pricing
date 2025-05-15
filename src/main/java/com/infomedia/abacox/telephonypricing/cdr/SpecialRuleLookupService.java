package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule;
import com.infomedia.abacox.telephonypricing.entity.SpecialService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Log4j2
@Transactional(readOnly = true)
public class SpecialRuleLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public SpecialRuleLookupService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<PbxSpecialRule> findPbxSpecialRule(String dialedNumber, Long commLocationId, int direction) {
        if (!StringUtils.hasText(dialedNumber) || commLocationId == null) {
            log.info("findPbxSpecialRule - Invalid input: dialedNumber={}, commLocationId={}", dialedNumber, commLocationId);
            return Optional.empty();
        }
        log.info("Finding PBX special rule for number: {}, commLocationId: {}, direction: {}", dialedNumber, commLocationId, direction);

        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            boolean match = false;
            String searchPattern = rule.getSearchPattern();

            if (StringUtils.hasText(searchPattern) && dialedNumber.startsWith(searchPattern)) {
                match = true;
                String ignorePatternString = rule.getIgnorePattern();
                if (match && StringUtils.hasText(ignorePatternString)) {
                    String[] ignorePatterns = ignorePatternString.split(",");
                    for (String ignore : ignorePatterns) {
                        String trimmedIgnore = ignore.trim();
                        if (!trimmedIgnore.isEmpty() && dialedNumber.startsWith(trimmedIgnore)) {
                            match = false;
                            log.info("Rule {} ignored for number {} due to ignore pattern '{}'", rule.getId(), dialedNumber, trimmedIgnore);
                            break;
                        }
                    }
                }
                if (match && rule.getMinLength() != null && dialedNumber.length() < rule.getMinLength()) {
                    match = false;
                    log.info("Rule {} ignored for number {} due to minLength ({} < {})", rule.getId(), dialedNumber, dialedNumber.length(), rule.getMinLength());
                }
            }

            if (match) {
                log.info("Found matching PBX special rule {} for number {}", rule.getId(), dialedNumber);
                return Optional.of(rule);
            }
        }

        log.info("No matching PBX special rule found for number {}", dialedNumber);
        return Optional.empty();
    }

    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.info("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL OR p.comm_location_id = 0) " +
                "  AND p.direction IN (0, :direction) " +
                "ORDER BY CASE WHEN p.comm_location_id IS NOT NULL AND p.comm_location_id != 0 THEN 0 ELSE 1 END ASC, LENGTH(p.search_pattern) DESC";
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("direction", direction);
        try {
            return query.getResultList();
        } catch (Exception e) {
            log.info("Error finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction, e);
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> findSpecialRateValues(Long telephonyTypeId, Long operatorId, Long bandId, Long originCommLocationIndicatorId, LocalDateTime callDateTime) {
        if (callDateTime == null) return Collections.emptyList();
        Long effectiveOriginIndicatorId = originCommLocationIndicatorId != null ? originCommLocationIndicatorId : 0L;
        Long effectiveTelephonyTypeId = telephonyTypeId != null ? telephonyTypeId : 0L;
        Long effectiveOperatorId = operatorId != null ? operatorId : 0L;
        Long effectiveBandId = bandId != null ? bandId : 0L;

        log.info("Finding special rate values for ttId: {}, opId: {}, bandId: {}, effectiveOriginIndId: {}, dateTime: {}",
                effectiveTelephonyTypeId, effectiveOperatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime);

        int dayOfWeek = callDateTime.getDayOfWeek().getValue();
        boolean isHoliday = false; // Holiday logic omitted

        String dayColumn;
        switch (dayOfWeek) {
            case 1: dayColumn = "srv.monday_enabled"; break;
            case 2: dayColumn = "srv.tuesday_enabled"; break;
            case 3: dayColumn = "srv.wednesday_enabled"; break;
            case 4: dayColumn = "srv.thursday_enabled"; break;
            case 5: dayColumn = "srv.friday_enabled"; break;
            case 6: dayColumn = "srv.saturday_enabled"; break;
            case 7: dayColumn = "srv.sunday_enabled"; break;
            default:
                log.info("Invalid day of week: {}", dayOfWeek);
                return Collections.emptyList();
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT srv.id, srv.name, srv.rate_value, srv.value_type, ");
        sqlBuilder.append("       srv.includes_vat, srv.hours_specification, ");
        sqlBuilder.append("       COALESCE(p.vat_value, 0) as prefix_vat_value ");
        sqlBuilder.append("FROM special_rate_value srv ");
        sqlBuilder.append("LEFT JOIN prefix p ON srv.telephony_type_id = p.telephone_type_id AND srv.operator_id = p.operator_id AND p.active = true ");
        sqlBuilder.append("WHERE srv.active = true ");
        sqlBuilder.append("  AND (srv.telephony_type_id = :telephonyTypeId OR srv.telephony_type_id IS NULL OR srv.telephony_type_id = 0) ");
        sqlBuilder.append("  AND (srv.operator_id = :operatorId OR srv.operator_id IS NULL OR srv.operator_id = 0) ");
        sqlBuilder.append("  AND (srv.band_id = :bandId OR srv.band_id IS NULL OR srv.band_id = 0) ");
        sqlBuilder.append("  AND (srv.origin_indicator_id = :originIndicatorId OR srv.origin_indicator_id IS NULL OR srv.origin_indicator_id = 0) ");
        sqlBuilder.append("  AND (srv.valid_from IS NULL OR srv.valid_from <= :callDateTime) ");
        sqlBuilder.append("  AND (srv.valid_to IS NULL OR srv.valid_to >= :callDateTime) ");
        sqlBuilder.append("  AND (").append(dayColumn).append(" = true ");
        if (isHoliday) {
            sqlBuilder.append("OR srv.holiday_enabled = true");
        }
        sqlBuilder.append(") ");
        sqlBuilder.append("ORDER BY CASE WHEN srv.origin_indicator_id IS NOT NULL AND srv.origin_indicator_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.telephony_type_id IS NOT NULL AND srv.telephony_type_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.operator_id IS NOT NULL AND srv.operator_id != 0 THEN 0 ELSE 1 END ASC, ");
        sqlBuilder.append("         CASE WHEN srv.band_id IS NOT NULL AND srv.band_id != 0 THEN 0 ELSE 1 END ASC");


        Query query = entityManager.createNativeQuery(sqlBuilder.toString());
        query.setParameter("telephonyTypeId", effectiveTelephonyTypeId);
        query.setParameter("operatorId", effectiveOperatorId);
        query.setParameter("bandId", effectiveBandId);
        query.setParameter("originIndicatorId", effectiveOriginIndicatorId);
        query.setParameter("callDateTime", callDateTime);

        try {
            List<Object[]> results = query.getResultList();
            List<Map<String, Object>> mappedResults = new ArrayList<>();
            for (Object[] row : results) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", row[0]);
                map.put("name", row[1]);
                map.put("rate_value", row[2]);
                map.put("value_type", row[3]);
                map.put("includes_vat", row[4]);
                map.put("hours_specification", row[5]);
                map.put("prefix_vat_value", row[6]);
                mappedResults.add(map);
            }
            log.info("Found {} special rate candidates", mappedResults.size());
            return mappedResults;
        } catch (Exception e) {
            log.info("Error finding special rate values for ttId: {}, opId: {}, bandId: {}, originIndId: {}, dateTime: {}",
                    effectiveTelephonyTypeId, effectiveOperatorId, effectiveBandId, effectiveOriginIndicatorId, callDateTime, e);
            return Collections.emptyList();
        }
    }

    public Optional<SpecialService> findSpecialService(String phoneNumber, Long indicatorId, Long originCountryId) {
        if (!StringUtils.hasText(phoneNumber) || originCountryId == null) {
            log.info("findSpecialService - Invalid input: phone={}, indId={}, ocId={}", phoneNumber, indicatorId, originCountryId);
            return Optional.empty();
        }
        Long effectiveIndicatorId = indicatorId != null ? indicatorId : 0L;
        log.info("Finding special service for number: {}, effectiveIndicatorId: {}, originCountryId: {}", phoneNumber, effectiveIndicatorId, originCountryId);

        String sql = "SELECT ss.* FROM special_service ss " +
                "WHERE ss.active = true " +
                "  AND ss.phone_number = :phoneNumber " +
                "  AND (ss.indicator_id = :indicatorId OR ss.indicator_id = 0 OR ss.indicator_id IS NULL) " +
                "  AND ss.origin_country_id = :originCountryId " +
                "ORDER BY CASE WHEN ss.indicator_id IS NOT NULL AND ss.indicator_id != 0 THEN 0 ELSE 1 END ASC, ss.id DESC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, SpecialService.class);
        query.setParameter("phoneNumber", phoneNumber);
        query.setParameter("indicatorId", effectiveIndicatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            SpecialService service = (SpecialService) query.getSingleResult();
            return Optional.of(service);
        } catch (NoResultException e) {
            log.info("No active special service found for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Error finding special service for number: {}, indId: {}, originId: {}", phoneNumber, effectiveIndicatorId, originCountryId, e);
            return Optional.empty();
        }
    }
}