package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Indicator; // Assuming you have this
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
public class IndicatorLookupService {

    @PersistenceContext
    private EntityManager entityManager;


    @Transactional(readOnly = true)
    public IndicatorConfig getIndicatorConfigForTelephonyType(Long telephonyTypeId, Long originCountryId) {
        String queryStr = "SELECT " +
                          "COALESCE(MIN(LENGTH(s.ndc::text)), 0) as min_ndc_len, " +
                          "COALESCE(MAX(LENGTH(s.ndc::text)), 0) as max_ndc_len, " +
                          "(SELECT LENGTH(s2.initial_number::text) FROM series s2 JOIN indicator i2 ON s2.indicator_id = i2.id WHERE i2.telephony_type_id = :telephonyTypeId AND i2.origin_country_id = :originCountryId AND s2.active = true AND i2.active = true AND s2.initial_number >= 0 GROUP BY LENGTH(s2.initial_number::text) ORDER BY COUNT(*) DESC LIMIT 1) as common_series_len " +
                          "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                          "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                          "AND s.active = true AND i.active = true AND s.initial_number >= 0"; // Ensure initial_number is non-negative

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        nativeQuery.setParameter("originCountryId", originCountryId);

        IndicatorConfig config = new IndicatorConfig();
        try {
            Tuple result = (Tuple) nativeQuery.getSingleResult();
            config.minNdcLength = result.get("min_ndc_len", Number.class).intValue();
            config.maxNdcLength = result.get("max_ndc_len", Number.class).intValue();
            Number commonSeriesLenNum = result.get("common_series_len", Number.class);
            config.seriesNumberLength = (commonSeriesLenNum != null) ? commonSeriesLenNum.intValue() : 0;

        } catch (NoResultException e) {
            log.warn("No indicator configuration found for telephonyTypeId {} and originCountryId {}", telephonyTypeId, originCountryId);
        }
        return config;
    }

    @Transactional(readOnly = true)
    public Optional<DestinationInfo> findDestinationIndicator(
            String phoneNumber, Long telephonyTypeId, int minPhoneNumberLengthAfterNdc, // Renamed from minPhoneNumberLengthAfterPrefix
            Long originIndicatorIdForBandContext, Long prefixId, Long originCountryId, boolean useBandSpecificLookup) {

        if (phoneNumber == null || phoneNumber.isEmpty()) return Optional.empty();

        IndicatorConfig config = getIndicatorConfigForTelephonyType(telephonyTypeId, originCountryId);
        if (config.maxNdcLength == 0 && telephonyTypeId != TelephonyTypeEnum.LOCAL.getValue() && telephonyTypeId != TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) { // Local might not have NDC
            log.debug("No NDC length configuration for telephony type {}, cannot find destination.", telephonyTypeId);
            return Optional.empty();
        }

        List<String> ndcCandidates = new ArrayList<>();
        int phoneLen = phoneNumber.length();

        // For local types, NDC might be empty or not applicable in the same way
        if (telephonyTypeId == TelephonyTypeEnum.LOCAL.getValue() || telephonyTypeId == TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) {
            // PHP's buscarDestino for local calls effectively uses an empty NDC and matches the full number against series.
            // We can add an empty string candidate to simulate this, or handle it in the query.
            // For now, let's assume local series have NDC = '0' or a specific local area code.
            // If local numbers are passed *without* their NDC, this logic needs adjustment.
            // The current PHP logic for local calls in buscarDestino:
            // $telefono = $indicativo_origen.$telefono; (adds local NDC)
            // So, phoneNumber here for local calls should already include its NDC.
        }

        for (int i = config.minNdcLength; i <= config.maxNdcLength; i++) {
            if (i > 0 && phoneLen >= i) { // NDC length must be positive
                if ((phoneLen - i) >= minPhoneNumberLengthAfterNdc) {
                    String candidateNdc = phoneNumber.substring(0, i);
                    if (candidateNdc.matches("\\d+")) {
                        ndcCandidates.add(candidateNdc);
                    }
                }
            }
        }
        if (config.minNdcLength == 0 && config.maxNdcLength == 0 && phoneLen >= minPhoneNumberLengthAfterNdc) {
             // Case where no specific NDC length is defined (e.g. some local calls might not have explicit NDC in series table)
             // or for types that don't use NDC in series (e.g. special numbers directly matched)
             // PHP's buscarDestino for local calls effectively uses an empty NDC if the series.ndc is 0 or matches local area code.
             // Here, if phoneNumber is the full local number, we might need to match against series where NDC is the local area code.
             // This part is complex due to how PHP handles local call NDC.
             // For now, if no NDC candidates from length, and it's a local type, we might try matching full number later.
        }
        if (ndcCandidates.isEmpty() && !(telephonyTypeId == TelephonyTypeEnum.LOCAL.getValue() || telephonyTypeId == TelephonyTypeEnum.LOCAL_EXTENDED.getValue())) {
             log.debug("No NDC candidates generated for phone {} and type {}", phoneNumber, telephonyTypeId);
             return Optional.empty();
        }


        StringBuilder queryBuilder = new StringBuilder(
            "SELECT i.id as indicator_id, i.operator_id as operator_id, s.ndc as ndc_val, i.department_country, i.city_name, s.initial_number, s.final_number, b.id as band_id " +
            "FROM series s JOIN indicator i ON s.indicator_id = i.id ");

        if (useBandSpecificLookup && prefixId != null) {
            queryBuilder.append("LEFT JOIN band b ON b.prefix_id = :prefixId AND b.active = true "); // LEFT JOIN for band
            queryBuilder.append("LEFT JOIN band_indicator bi ON bi.band_id = b.id AND bi.indicator_id = i.id ");
            queryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBandContext) ");
        } else {
            queryBuilder.append("LEFT JOIN band b ON 1=0 "); // Dummy join for band_id column if not using bands
            if (prefixId != null) { // Filter by operator of the prefix if not using bands
                 queryBuilder.append("AND (i.operator_id = 0 OR EXISTS (SELECT 1 FROM prefix p WHERE p.id = :prefixId AND p.operator_id = i.operator_id AND p.active = true)) ");
            }
        }

        queryBuilder.append("WHERE i.active = true AND s.active = true AND i.telephony_type_id = :telephonyTypeId ");
        if (!ndcCandidates.isEmpty()) {
            queryBuilder.append("AND s.ndc IN (:ndcCandidates) ");
        } else if (telephonyTypeId == TelephonyTypeEnum.LOCAL.getValue() || telephonyTypeId == TelephonyTypeEnum.LOCAL_EXTENDED.getValue()) {
            // For local, if no NDC candidates, it implies we might be matching the full number against series where NDC is implicit (e.g. 0 or local area code)
            // This case needs careful handling based on how series for local numbers are stored.
            // If local numbers are passed with their NDC, ndcCandidates should not be empty.
            // If passed without, then series.ndc might be '0' or the actual local area code.
            // For now, if ndcCandidates is empty for local, we assume the query won't match unless series.ndc is also empty/0.
            // This part might need refinement based on actual data structure for local series.
        }


        if (telephonyTypeId != TelephonyTypeEnum.INTERNATIONAL.getValue() && telephonyTypeId != TelephonyTypeEnum.SATELLITE.getValue()) {
            queryBuilder.append("AND i.origin_country_id = :originCountryId ");
        }
        
        if (useBandSpecificLookup) {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, b.origin_indicator_id DESC NULLS LAST, s.initial_number, s.final_number");
        } else {
            queryBuilder.append("ORDER BY LENGTH(s.ndc::text) DESC, s.initial_number, s.final_number");
        }

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryBuilder.toString(), Tuple.class);
        nativeQuery.setParameter("telephonyTypeId", telephonyTypeId);
        if (!ndcCandidates.isEmpty()) {
            nativeQuery.setParameter("ndcCandidates", ndcCandidates);
        }
        if (useBandSpecificLookup && prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
            nativeQuery.setParameter("originIndicatorIdForBandContext", originIndicatorIdForBandContext);
        } else if (prefixId != null) {
            nativeQuery.setParameter("prefixId", prefixId);
        }
        if (telephonyTypeId != TelephonyTypeEnum.INTERNATIONAL.getValue() && telephonyTypeId != TelephonyTypeEnum.SATELLITE.getValue()) {
            nativeQuery.setParameter("originCountryId", originCountryId);
        }

        List<Tuple> results = nativeQuery.getResultList();
        List<DestinationInfo> validMatches = new ArrayList<>();
        DestinationInfo approximateMatch = null;


        for (Tuple row : results) {
            String currentNdcStr = String.valueOf(row.get("ndc_val", Number.class).intValue()); // Series.ndc is Integer
            String numberAfterNdcStr = phoneNumber;
            if (phoneNumber.startsWith(currentNdcStr)) {
                 numberAfterNdcStr = phoneNumber.substring(currentNdcStr.length());
            } else if (currentNdcStr.equals("0") && (telephonyTypeId == TelephonyTypeEnum.LOCAL.getValue() || telephonyTypeId == TelephonyTypeEnum.LOCAL_EXTENDED.getValue())) {
                // For local calls where series.ndc might be 0, numberAfterNdcStr is the full local number
                // This case assumes phoneNumber is the subscriber part for local if ndc is 0.
            } else {
                continue; // NDC doesn't match start of phone number
            }


            Integer seriesInitial = row.get("initial_number", Integer.class);
            Integer seriesFinal = row.get("final_number", Integer.class);
            boolean isCurrentApproximate = false;

            // PHP's rellenaSerie logic
            String seriesInitialPaddedStr = String.valueOf(seriesInitial);
            String seriesFinalPaddedStr = String.valueOf(seriesFinal);
            int targetSeriesLength = numberAfterNdcStr.length();

            if (config.seriesNumberLength > 0) { // If a common series length is defined
                targetSeriesLength = config.seriesNumberLength;
            }
            
            // Pad initial_number with leading zeros if shorter
            if (seriesInitialPaddedStr.length() < targetSeriesLength) {
                seriesInitialPaddedStr = String.format("%0" + targetSeriesLength + "d", seriesInitial);
            } else if (seriesInitialPaddedStr.length() > targetSeriesLength) {
                 seriesInitialPaddedStr = seriesInitialPaddedStr.substring(0, targetSeriesLength); // Truncate if longer
            }

            // Pad final_number with trailing nines if shorter
            if (seriesFinalPaddedStr.length() < targetSeriesLength) {
                seriesFinalPaddedStr = String.format("%-" + targetSeriesLength + "s", seriesFinalPaddedStr).replace(' ', '9');
            } else if (seriesFinalPaddedStr.length() > targetSeriesLength) {
                seriesFinalPaddedStr = seriesFinalPaddedStr.substring(0, targetSeriesLength); // Truncate if longer
            }
            
            String numberToCompareInSeriesStr = numberAfterNdcStr;
            if (numberAfterNdcStr.length() > targetSeriesLength) {
                numberToCompareInSeriesStr = numberAfterNdcStr.substring(0, targetSeriesLength);
            } else if (numberAfterNdcStr.length() < targetSeriesLength) {
                 // If number is shorter than padded series, it can't be a precise match within padded range
                 // This case might indicate an approximate match or a misconfiguration.
                 // PHP's rellenaSerie pads the number itself if it's shorter than series, which is complex.
                 // Here, we compare the (potentially truncated) number against padded series.
                 // If numberAfterNdcStr is shorter than targetSeriesLength, it might be an "approximate" match.
                 // For now, let's assume if numberAfterNdcStr is shorter, it won't match a padded series of targetSeriesLength.
                 // This needs careful review against PHP's exact padding behavior of the number itself.
                 // The PHP logic: $serie_inicial = str_pad($serie_inicial, $diferencia, '0');
                 // $diferencia = ($longitud - $longitud_indicativo); (longitud of full number, longitud_indicativo of NDC)
                 // This means targetSeriesLength is (strlen(phoneNumber) - strlen(currentNdcStr)).
                 // Let's re-evaluate targetSeriesLength based on this.
                 targetSeriesLength = phoneLen - currentNdcStr.length();
                 if (targetSeriesLength <=0 && phoneLen > 0) targetSeriesLength = phoneLen; // For local calls with NDC "0"

                 seriesInitialPaddedStr = String.valueOf(seriesInitial);
                 seriesFinalPaddedStr = String.valueOf(seriesFinal);

                 if (seriesInitialPaddedStr.length() < targetSeriesLength) seriesInitialPaddedStr = String.format("%0" + targetSeriesLength + "d", seriesInitial);
                 else if (seriesInitialPaddedStr.length() > targetSeriesLength) seriesInitialPaddedStr = seriesInitialPaddedStr.substring(0, targetSeriesLength);

                 if (seriesFinalPaddedStr.length() < targetSeriesLength) seriesFinalPaddedStr = String.format("%-" + targetSeriesLength + "s", seriesFinalPaddedStr).replace(' ', '9');
                 else if (seriesFinalPaddedStr.length() > targetSeriesLength) seriesFinalPaddedStr = seriesFinalPaddedStr.substring(0, targetSeriesLength);
                 
                 numberToCompareInSeriesStr = numberAfterNdcStr;
                 if (numberAfterNdcStr.length() > targetSeriesLength) numberToCompareInSeriesStr = numberAfterNdcStr.substring(0, targetSeriesLength);
                 // If numberAfterNdcStr is shorter than targetSeriesLength, it can't match unless series are also short.
                 // The padding should make them comparable.
            }


            if (numberToCompareInSeriesStr.compareTo(seriesInitialPaddedStr) >= 0 &&
                numberToCompareInSeriesStr.compareTo(seriesFinalPaddedStr) <= 0) {
                addMatch(validMatches, row, currentNdcStr, phoneNumber, prefixId, false);
            } else if (Integer.parseInt(currentNdcStr) < 0) { // PHP: if (1 * $info_indica['SERIE_NDC'] < 0)
                // Approximate match for negative NDC (special case in PHP)
                isCurrentApproximate = true;
                if (approximateMatch == null) { // Only take the first approximate match
                    approximateMatch = new DestinationInfo();
                    fillDestinationInfo(approximateMatch, row, currentNdcStr, phoneNumber, prefixId, true);
                }
            }
        }
        
        if (!validMatches.isEmpty()) {
            // PHP: ksort($series_ok); $info_indica = array_shift($series_ok);
            // Sort by tightness of range (final-initial), then by NDC length (desc), then by initial (asc)
            validMatches.sort(Comparator
                .comparing((DestinationInfo di) -> { // Tightness of range
                    Optional<Tuple> rowOpt = results.stream().filter(r -> r.get("indicator_id", Number.class).longValue() == di.getIndicatorId() && String.valueOf(r.get("ndc_val", Number.class).intValue()).equals(di.getNdc())).findFirst();
                    if(rowOpt.isPresent()){
                        Integer sInit = rowOpt.get().get("initial_number", Integer.class);
                        Integer sFin = rowOpt.get().get("final_number", Integer.class);
                        if(sInit != null && sFin != null) return sFin - sInit;
                    }
                    return Integer.MAX_VALUE;
                })
                .thenComparing(di -> di.getNdc().length(), Comparator.reverseOrder())
                .thenComparing(di -> {
                     Optional<Tuple> rowOpt = results.stream().filter(r -> r.get("indicator_id", Number.class).longValue() == di.getIndicatorId() && String.valueOf(r.get("ndc_val", Number.class).intValue()).equals(di.getNdc())).findFirst();
                     return rowOpt.map(tuple -> tuple.get("initial_number", Integer.class)).orElse(Integer.MAX_VALUE);
                })
            );
            return Optional.of(validMatches.get(0));
        } else if (approximateMatch != null) {
            return Optional.of(approximateMatch);
        }
        
        return Optional.empty();
    }

    private void addMatch(List<DestinationInfo> matches, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox) {
        DestinationInfo di = new DestinationInfo();
        fillDestinationInfo(di, row, ndc, originalPhoneNumber, prefixId, isApprox);
        matches.add(di);
    }
    
    private void fillDestinationInfo(DestinationInfo di, Tuple row, String ndc, String originalPhoneNumber, Long prefixId, boolean isApprox) {
        di.setIndicatorId(row.get("indicator_id", Number.class).longValue());
        Number opIdNum = row.get("operator_id", Number.class);
        di.setOperatorId(opIdNum != null ? opIdNum.longValue() : null);
        di.setNdc(ndc);
        di.setDestinationDescription(formatDestinationDescription(row.get("city_name", String.class), row.get("department_country", String.class)));
        di.setMatchedPhoneNumber(originalPhoneNumber);
        di.setPrefixIdUsed(prefixId);
        Number bandIdNum = row.get("band_id", Number.class);
        di.setBandId(bandIdNum != null ? bandIdNum.longValue() : null);
        di.setApproximateMatch(isApprox);
        if (isApprox) {
            di.setDestinationDescription(di.getDestinationDescription() + " (aprox)");
        }
    }


    private String formatDestinationDescription(String city, String deptCountry) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isEmpty()) sb.append(city);
        if (deptCountry != null && !deptCountry.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(deptCountry);
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String findLocalNdcForIndicator(Long indicatorId) {
        // PHP: SELECT SERIE_NDC, count(*) as N FROM serie WHERE SERIE_INDICATIVO_ID = $indicativo_id GROUP BY SERIE_NDC ORDER BY N DESC, SERIE_NDC
        String queryStr = "SELECT s.ndc FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true " +
                          "GROUP BY s.ndc ORDER BY COUNT(*) DESC, s.ndc ASC LIMIT 1";
        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr); // Returns String directly
        nativeQuery.setParameter("indicatorId", indicatorId);
        try {
            Object result = nativeQuery.getSingleResult(); // NDC is Integer in Series entity
            return result != null ? String.valueOf(result) : "";
        } catch (NoResultException e) {
            return "";
        }
    }

    @Transactional(readOnly = true)
    public boolean isLocalExtended(String destinationNdc, Long localOriginIndicatorId, Long destinationIndicatorId) {
        // PHP: if ($indicativolocal_id != $indicativo_id && $indicativo_id > 0 && $indicativolocal_id > 0)
        if (Objects.equals(localOriginIndicatorId, destinationIndicatorId) || destinationIndicatorId == null || localOriginIndicatorId == null || destinationIndicatorId <= 0 || localOriginIndicatorId <= 0) {
            return false;
        }
        
        // PHP: SELECT distinct(SERIE_NDC) as SERIE_NDC FROM serie WHERE SERIE_INDICATIVO_ID = $indicativolocal_id
        String ndcsForLocalOriginQuery = "SELECT DISTINCT s.ndc FROM series s WHERE s.indicator_id = :localOriginIndicatorId AND s.active = true";
        List<Integer> localNdcsInt = entityManager.createNativeQuery(ndcsForLocalOriginQuery, Integer.class)
                                    .setParameter("localOriginIndicatorId", localOriginIndicatorId)
                                    .getResultList();
        List<String> localNdcs = localNdcsInt.stream().map(String::valueOf).collect(Collectors.toList());
        
        return localNdcs.contains(destinationNdc);
    }
}