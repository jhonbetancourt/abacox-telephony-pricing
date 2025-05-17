package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NumberRoutingLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CoreLookupService coreLookupService;

    @SuppressWarnings("unchecked")
    public List<Prefix> findMatchingPrefixes(String dialedNumber, Long originCountryId, boolean forTrunk) {
        String sql = "SELECT p.*, LENGTH(p.code) as code_len " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "WHERE o.origin_country_id = :originCountryId " +
                "  AND p.active = true AND o.active = true AND tt.active = true " +
                "  AND tt.id != :specialServiceTypeId " +
                "ORDER BY code_len DESC, tt.id";

        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("originCountryId", originCountryId);
        query.setParameter("specialServiceTypeId", TelephonyTypeConstants.NUMEROS_ESPECIALES);

        List<Prefix> allRetrievedPrefixes = query.getResultList();
        List<Prefix> matchingPrefixes = new ArrayList<>();

        if (!forTrunk) {
            for (Prefix p : allRetrievedPrefixes) {
                if (p.getCode() != null && !p.getCode().isEmpty() && dialedNumber.startsWith(p.getCode())) {
                    matchingPrefixes.add(p);
                }
            }
            if (matchingPrefixes.isEmpty()) {
                Optional<TelephonyType> localTypeOpt = coreLookupService.findTelephonyTypeById(TelephonyTypeConstants.LOCAL);
                if (localTypeOpt.isPresent()) {
                    TelephonyType localType = localTypeOpt.get();
                    Prefix localPrefix = new Prefix();
                    localPrefix.setCode("");
                    localPrefix.setTelephonyType(localType);
                    localPrefix.setTelephoneTypeId(localType.getId());
                    coreLookupService.findInternalOperatorByTelephonyType(TelephonyTypeConstants.LOCAL, originCountryId).ifPresent(op -> {
                        localPrefix.setOperator(op);
                        localPrefix.setOperatorId(op.getId());
                    });
                    localPrefix.setBaseValue(BigDecimal.ZERO);
                    localPrefix.setVatIncluded(false);
                    localPrefix.setVatValue(BigDecimal.ZERO);
                    localPrefix.setBandOk(true);
                    matchingPrefixes.add(localPrefix);
                }
            }
        } else {
            matchingPrefixes.addAll(allRetrievedPrefixes);
        }
        return matchingPrefixes;
    }

    public Optional<Prefix> findInternalPrefixForType(Long telephonyTypeId, Long originCountryId) {
        String sql = "SELECT p.* FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "AND o.origin_country_id = :originCountryId " +
                "AND p.active = true AND o.active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Prefix.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return Optional.ofNullable((Prefix) query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public PrefixNdcLimitsDto getPrefixNdcLimits(Long telephonyTypeId, Long originCountryId) {
        String sql = "SELECT MIN(LENGTH(CAST(s.ndc AS TEXT))) AS min_len, MAX(LENGTH(CAST(s.ndc AS TEXT))) AS max_len, " +
                "  MIN(LENGTH(CAST(s.initial_number AS TEXT))) as min_series_len, MAX(LENGTH(CAST(s.initial_number AS TEXT))) as max_series_len " +
                "FROM series s JOIN indicator i ON s.indicator_id = i.id " +
                "WHERE i.telephony_type_id = :telephonyTypeId AND i.origin_country_id = :originCountryId " +
                "AND i.active = true AND s.active = true AND s.ndc IS NOT NULL AND s.ndc > 0 AND s.initial_number >= 0";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        Object[] result = null;
        try {
            result = (Object[]) query.getSingleResult();
        } catch (NoResultException e) {
            // No series found
        }

        int minNdcLen = (result != null && result[0] != null) ? ((Number) result[0]).intValue() : 0;
        int maxNdcLen = (result != null && result[1] != null) ? ((Number) result[1]).intValue() : 0;
        int minSeriesLen = (result != null && result[2] != null) ? ((Number) result[2]).intValue() : 0;
        int maxSeriesLen = (result != null && result[3] != null) ? ((Number) result[3]).intValue() : 0;

        int seriesNumLen = (minSeriesLen == maxSeriesLen && minSeriesLen > 0) ? minSeriesLen : 0;

        return PrefixNdcLimitsDto.builder()
                .minNdcLength(minNdcLen)
                .maxNdcLength(maxNdcLen)
                .seriesNumberLength(seriesNumLen)
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<SeriesMatchDto> findIndicatorsByNumberAndType(String numberToMatch, Long telephonyTypeId, Long originCountryId, CommunicationLocation commLocation, Long prefixIdForBandOk, boolean bandOkForPrefix) {
        String cleanedNumber = CdrHelper.cleanPhoneNumber(numberToMatch);
        if (cleanedNumber.isEmpty()) return Collections.emptyList();

        PrefixNdcLimitsDto ndcLimits = getPrefixNdcLimits(telephonyTypeId, originCountryId);
        if (ndcLimits.getMaxNdcLength() == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
            return Collections.emptyList();
        }

        List<SeriesMatchDto> seriesMatches = new ArrayList<>();

        for (int ndcLen = ndcLimits.getMaxNdcLength(); ndcLen >= ndcLimits.getMinNdcLength(); ndcLen--) {
            if (ndcLen == 0 && !Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) continue;
            if (cleanedNumber.length() < ndcLen) continue;

            String currentNdcStr = (ndcLen > 0) ? cleanedNumber.substring(0, ndcLen) : "";
            String remainingNumberStr = (ndcLen > 0) ? cleanedNumber.substring(ndcLen) : cleanedNumber;

            if (!CdrHelper.isNumeric(currentNdcStr) && ndcLen > 0) continue;
            if (!remainingNumberStr.isEmpty() && !CdrHelper.isNumeric(remainingNumberStr)) continue;

            Integer currentNdc = (ndcLen > 0) ? Integer.parseInt(currentNdcStr) : null;

            String sqlBase = "SELECT i.*, s.ndc as series_ndc, s.initial_number as series_initial_num, s.final_number as series_final_num, " +
                    "i_orig.department_country as origin_dept_country, i_orig.city_name as origin_city_name ";
            String sqlFrom = "FROM indicator i JOIN series s ON i.id = s.indicator_id " +
                    "LEFT JOIN indicator i_orig ON i.origin_country_id = i_orig.origin_country_id AND i_orig.id = :originCommIndicatorId ";
            String sqlWhere = "WHERE i.telephony_type_id = :telephonyTypeId " +
                    "  AND i.origin_country_id = :originCountryId " +
                    (currentNdc != null ? "  AND s.ndc = :currentNdc " : " AND (s.ndc IS NULL OR s.ndc = 0) ") +
                    "  AND i.active = true AND s.active = true ";
            String sqlOrder = "ORDER BY s.ndc DESC, (s.final_number - s.initial_number) ASC, i.id";

            if (bandOkForPrefix && prefixIdForBandOk != null) {
                sqlFrom += ", band b, band_indicator bi ";
                sqlWhere += " AND b.prefix_id = :prefixIdForBandOk " +
                        " AND bi.indicator_id = i.id AND bi.band_id = b.id " +
                        " AND (b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 OR b.origin_indicator_id = :commIndicatorId) ";
                sqlOrder = "ORDER BY CASE WHEN b.origin_indicator_id IS NULL OR b.origin_indicator_id = 0 THEN 1 ELSE 0 END DESC, " + sqlOrder;
            } else if (prefixIdForBandOk != null) {
                sqlWhere += " AND (i.operator_id IS NULL OR i.operator_id = 0 OR i.operator_id IN (SELECT p_op.operator_id FROM prefix p_op WHERE p_op.id = :prefixIdForBandOk)) ";
            }

            String finalSql = sqlBase + sqlFrom + sqlWhere + sqlOrder;
            Query query = entityManager.createNativeQuery(finalSql, Tuple.class);

            query.setParameter("telephonyTypeId", telephonyTypeId);
            query.setParameter("originCountryId", originCountryId);
            if (currentNdc != null) query.setParameter("currentNdc", currentNdc);
            query.setParameter("originCommIndicatorId", commLocation.getIndicatorId());
            if (bandOkForPrefix && prefixIdForBandOk != null) {
                query.setParameter("prefixIdForBandOk", prefixIdForBandOk);
                query.setParameter("commIndicatorId", commLocation.getIndicatorId());
            } else if (prefixIdForBandOk != null) {
                query.setParameter("prefixIdForBandOk", prefixIdForBandOk);
            }

            List<Tuple> results = query.getResultList();
            List<SeriesMatchDto> currentNdcMatches = new ArrayList<>();

            for (Tuple row : results) {
                Indicator indicator = mapTupleToIndicator(row);
                Integer seriesInitialNum = row.get("series_initial_num", Integer.class);
                Integer seriesFinalNum = row.get("series_final_num", Integer.class);

                PaddedSeriesDto padded = CdrHelper.rellenaSerie(cleanedNumber, currentNdcStr, String.valueOf(seriesInitialNum), String.valueOf(seriesFinalNum));

                if (cleanedNumber.compareTo(padded.getPaddedInitialNumber()) >= 0 &&
                        cleanedNumber.compareTo(padded.getPaddedFinalNumber()) <= 0) {

                    currentNdcMatches.add(SeriesMatchDto.builder()
                            .indicator(indicator)
                            .ndc(currentNdcStr)
                            .destinationDescription(buildDestinationDescription(indicator, commLocation.getIndicator()))
                            .build());
                }
            }

            if (!currentNdcMatches.isEmpty()) {
                seriesMatches.add(currentNdcMatches.get(0));
                if (ndcLen > 0) break;
            }
        }

        if (seriesMatches.isEmpty() && Long.valueOf(TelephonyTypeConstants.LOCAL).equals(telephonyTypeId)) {
            if (commLocation.getIndicator() != null) {
                seriesMatches.add(SeriesMatchDto.builder()
                        .indicator(commLocation.getIndicator())
                        .ndc(findLocalAreaCodeForIndicator(commLocation.getIndicatorId()))
                        .destinationDescription(buildDestinationDescription(commLocation.getIndicator(), commLocation.getIndicator()))
                        .isApproximate(true)
                        .build());
            }
        }
        seriesMatches.sort(Comparator.comparing((SeriesMatchDto sm) -> sm.getNdc() != null ? sm.getNdc().length() : 0).reversed());

        return seriesMatches;
    }

    private Indicator mapTupleToIndicator(Tuple tuple) {
        Indicator ind = new Indicator();
        ind.setId(tuple.get("id", Long.class));
        ind.setTelephonyTypeId(tuple.get("telephony_type_id", Long.class));
        ind.setDepartmentCountry(tuple.get("department_country", String.class));
        ind.setCityName(tuple.get("city_name", String.class));
        ind.setOperatorId(tuple.get("operator_id", Long.class));
        ind.setOriginCountryId(tuple.get("origin_country_id", Long.class));
        ind.setActive(tuple.get("active", Boolean.class));
        // Set other AuditedEntity fields if needed from tuple
        return ind;
    }

    public String buildDestinationDescription(Indicator matchedIndicator, Indicator originCommIndicator) {
        if (matchedIndicator == null) return "Unknown";
        String city = matchedIndicator.getCityName();
        String deptCountry = matchedIndicator.getDepartmentCountry();
        if (city != null && !city.isEmpty()) {
            if (deptCountry != null && !deptCountry.isEmpty() && !city.equalsIgnoreCase(deptCountry)) {
                if (originCommIndicator != null && Objects.equals(originCommIndicator.getDepartmentCountry(), deptCountry)) {
                    if (isLocalExtended(originCommIndicator, matchedIndicator)) {
                        return city + " (" + deptCountry + " - Local Ext.)";
                    }
                }
                return city + " (" + deptCountry + ")";
            }
            return city;
        }
        return deptCountry != null ? deptCountry : "N/A";
    }

    public String findLocalAreaCodeForIndicator(Long indicatorId) {
        if (indicatorId == null) return "";
        String sql = "SELECT CAST(s.ndc AS TEXT) FROM series s WHERE s.indicator_id = :indicatorId AND s.active = true ORDER BY LENGTH(CAST(s.ndc AS TEXT)) DESC, s.ndc LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("indicatorId", indicatorId);
        try {
            return query.getSingleResult().toString();
        } catch (NoResultException e) {
            return "";
        }
    }

    public boolean isLocalExtended(Indicator originIndicator, Indicator destinationIndicator) {
        if (originIndicator == null || destinationIndicator == null ||
                Objects.equals(originIndicator.getId(), destinationIndicator.getId())) {
            return false;
        }
        String originNdc = findLocalAreaCodeForIndicator(originIndicator.getId());
        String destNdc = findLocalAreaCodeForIndicator(destinationIndicator.getId());

        return !originNdc.isEmpty() && originNdc.equals(destNdc);
    }
}
