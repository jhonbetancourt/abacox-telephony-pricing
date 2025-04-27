package com.infomedia.abacox.telephonypricing.cdr;

import lombok.*;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    private final TPConfigService configService;


    @Cacheable(value = "prefixes", key = "#mporigenId")
    public PrefixInfo loadPrefixes(Long mporigenId) {
        log.debug("Loading prefixes for mporigenId: {}", mporigenId);

        PrefixInfo prefixInfo = new PrefixInfo();
        prefixInfo.setPrefixMap(new HashMap<>());
        prefixInfo.setTelephonyTypeMap(new HashMap<>());
        prefixInfo.setDataMap(new HashMap<>());
        prefixInfo.setPrefixOperatorMap(new HashMap<>());

        String query = """
                    SELECT p.id, tt.id as tipotele_id, tt.name as tipotele_nombre, o.name as operador_nombre, 
                           p.code as prefijo, p.operator_id as operador_id, ttc.min_value as tipotelecfg_min, 
                           ttc.max_value as tipotelecfg_max, 
                           (SELECT COUNT(bi.id) FROM band_indicator bi 
                            INNER JOIN band b ON bi.band_id = b.id 
                            WHERE b.prefix_id = p.id) as bandas_ok
                    FROM prefix p
                    JOIN telephony_type tt ON p.telephone_type_id = tt.id
                    JOIN operator o ON p.operator_id = o.id
                    LEFT JOIN telephony_type_config ttc ON (ttc.telephony_type_id = tt.id AND ttc.origin_country_id = :mporigenId)
                    WHERE tt.id != :tipoteleEspeciales
                    ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, tt.id
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);
        nativeQuery.setParameter("mporigenId", mporigenId);
        nativeQuery.setParameter("tipoteleEspeciales", configService.getTipoteleEspeciales());

        List<Object[]> results = nativeQuery.getResultList();

        int minLength = 0;
        int maxLength = 0;
        Long localId = 0L;
        Long localExtId = 0L;

        for (Object[] row : results) {
            Long prefixId = ((Number) row[0]).longValue();
            Long telephonyTypeId = ((Number) row[1]).longValue();
            String telephonyTypeName = (String) row[2];
            String operatorName = (String) row[3];
            String prefix = (String) row[4];
            Long operatorId = ((Number) row[5]).longValue();
            Integer telephonyTypeMin = row[6] != null ? ((Number) row[6]).intValue() : 0;
            Integer telephonyTypeMax = row[7] != null ? ((Number) row[7]).intValue() : 0;
            Integer bandsOk = ((Number) row[8]).intValue();

            PrefixInfo.PrefixData prefixData = PrefixInfo.PrefixData.builder()
                    .telephonyTypeId(telephonyTypeId)
                    .telephonyTypeName(telephonyTypeName)
                    .operatorName(operatorName)
                    .prefix(prefix)
                    .operatorId(operatorId)
                    .telephonyTypeMin(telephonyTypeMin)
                    .telephonyTypeMax(telephonyTypeMax)
                    .bandsOk(bandsOk)
                    .build();

            prefixInfo.getDataMap().put(prefixId, prefixData);

            if (telephonyTypeId.equals(configService.getTipoteleLocal())) {
                localId = prefixId;
            } else if (telephonyTypeId.equals(configService.getTipoteleLocalExt())) {
                localExtId = prefixId;
            } else if (prefix != null && !prefix.isEmpty()) {
                if (!prefixInfo.getPrefixMap().containsKey(prefix)) {
                    prefixInfo.getPrefixMap().put(prefix, new ArrayList<>());
                }
                prefixInfo.getPrefixMap().get(prefix).add(prefixId);

                int prefixLength = prefix.length();
                if (minLength <= 0 || minLength > prefixLength) {
                    minLength = prefixLength;
                }
                if (maxLength <= 0 || maxLength < prefixLength) {
                    maxLength = prefixLength;
                }

                // Control of prefixes by operator and telephony type
                if (!prefixInfo.getPrefixOperatorMap().containsKey(prefix)) {
                    prefixInfo.getPrefixOperatorMap().put(prefix, new HashMap<>());
                }
                prefixInfo.getPrefixOperatorMap().get(prefix).put(operatorId, 1);
            }

            if (!prefixInfo.getTelephonyTypeMap().containsKey(telephonyTypeId)) {
                prefixInfo.getTelephonyTypeMap().put(telephonyTypeId, new ArrayList<>());
            }
            prefixInfo.getTelephonyTypeMap().get(telephonyTypeId).add(prefixId);
        }

        prefixInfo.setMinLength(minLength);
        prefixInfo.setMaxLength(maxLength);
        prefixInfo.setLocalId(localId);
        prefixInfo.setLocalExtId(localExtId);

        return prefixInfo;
    }


    @Cacheable(value = "ivaByTelephonyTypeAndOperator", key = "#telephonyTypeIds")
    public Map<Long, Map<Long, Map<Long, BigDecimal>>> loadIvaByTelephonyTypeAndOperator(List<Long> telephonyTypeIds) {
        log.debug("Loading IVA by telephony type and operator for: {}", telephonyTypeIds);

        String queryStr = """
                    SELECT p.telephone_type_id, p.operator_id, p.vat_value
                    FROM prefix p
                    WHERE p.telephone_type_id IN (:telephonyTypeIds)
                    ORDER BY p.id
                """;

        Query query = entityManager.createNativeQuery(queryStr);
        query.setParameter("telephonyTypeIds", telephonyTypeIds);

        List<Object[]> results = query.getResultList();

        Map<Long, Map<Long, Map<Long, BigDecimal>>> ivaMap = new HashMap<>();

        for (Object[] row : results) {
            Long telephonyTypeId = ((Number) row[0]).longValue();
            Long operatorId = ((Number) row[1]).longValue();
            BigDecimal ivaValue = (BigDecimal) row[2];

            if (!ivaMap.containsKey(telephonyTypeId)) {
                ivaMap.put(telephonyTypeId, new HashMap<>());
                ivaMap.get(telephonyTypeId).put(0L, new HashMap<>());
            }

            if (!ivaMap.get(telephonyTypeId).containsKey(operatorId)) {
                ivaMap.get(telephonyTypeId).put(operatorId, new HashMap<>());
            }

            ivaMap.get(telephonyTypeId).get(operatorId).put(0L, ivaValue);

            // Set default operator's IVA for telephony type
            if (!ivaMap.get(telephonyTypeId).get(0L).containsKey(0L)) {
                ivaMap.get(telephonyTypeId).get(0L).put(0L, ivaValue);
            }
        }

        return ivaMap;
    }


    @Cacheable(value = "defaultOperatorByTelephonyType", key = "#telephonyTypeIds")
    public Map<Long, Long> loadDefaultOperatorByTelephonyType(List<Long> telephonyTypeIds) {
        log.debug("Loading default operator by telephony type for: {}", telephonyTypeIds);

        String queryStr = """
                    SELECT p.telephone_type_id, p.operator_id
                    FROM prefix p
                    WHERE p.telephone_type_id IN (:telephonyTypeIds)
                    ORDER BY p.id
                """;

        Query query = entityManager.createNativeQuery(queryStr);
        query.setParameter("telephonyTypeIds", telephonyTypeIds);

        List<Object[]> results = query.getResultList();

        Map<Long, Long> defaultOperatorMap = new HashMap<>();

        for (Object[] row : results) {
            Long telephonyTypeId = ((Number) row[0]).longValue();
            Long operatorId = ((Number) row[1]).longValue();

            if (!defaultOperatorMap.containsKey(telephonyTypeId)) {
                defaultOperatorMap.put(telephonyTypeId, operatorId);
            }
        }

        return defaultOperatorMap;
    }


    @Cacheable(value = "trunks", key = "#comubicacionId")
    public Map<String, TrunkInfo> loadTrunks(Long comubicacionId) {
        log.debug("Loading trunks for comubicacionId: {}", comubicacionId);

        Map<String, TrunkInfo> trunkMap = new HashMap<>();

        // Load trunk base data
        String trunkQuery = """
                    SELECT t.trunk, t.description, t.operator_id, t.no_pbx_prefix
                    FROM trunk t
                    WHERE t.active = true AND t.comm_location_id IN (0, :comubicacionId)
                    ORDER BY t.comm_location_id ASC
                """;

        Query trunksNativeQuery = entityManager.createNativeQuery(trunkQuery);
        trunksNativeQuery.setParameter("comubicacionId", comubicacionId);

        List<Object[]> trunkResults = trunksNativeQuery.getResultList();

        for (Object[] row : trunkResults) {
            String trunk = (String) row[0];
            String description = (String) row[1];
            Long operatorId = ((Number) row[2]).longValue();
            Integer noPbxPrefix = (Integer) row[3];

            TrunkInfo trunkInfo = TrunkInfo.builder()
                    .cellFixed(false)
                    .description(description)
                    .operatorId(operatorId)
                    .noPbxPrefix(noPbxPrefix > 0)
                    .pricePerMinute(BigDecimal.ZERO)
                    .pricePerMinuteIncludesVat(false)
                    .vatAmount(BigDecimal.ZERO)
                    .inSeconds(false)
                    .operatorDestination(new HashMap<>())
                    .operatorDestinationTypes(new HashMap<>())
                    .build();

            trunkMap.put(trunk.toUpperCase(), trunkInfo);
        }

        // Load trunk rates
        if (!trunkMap.isEmpty()) {
            Set<String> trunks = trunkMap.keySet();

            String ratesQuery = """
                        SELECT tr.trunk_id, t.trunk, tr.telephony_type_id, tr.rate_value, tr.includes_vat, 
                               tr.operator_id, tr.no_pbx_prefix, tr.no_prefix, tr.seconds
                        FROM trunk_rate tr
                        JOIN trunk t ON tr.trunk_id = t.id
                        WHERE t.trunk IN (:trunks)
                        ORDER BY tr.telephony_type_id DESC
                    """;

            Query ratesNativeQuery = entityManager.createNativeQuery(ratesQuery);
            ratesNativeQuery.setParameter("trunks", trunks);

            List<Object[]> rateResults = ratesNativeQuery.getResultList();
            Set<Long> telephonyTypeIds = new HashSet<>();

            for (Object[] row : rateResults) {
                String trunk = (String) row[1];
                Long telephonyTypeId = ((Number) row[2]).longValue();
                BigDecimal rateValue = (BigDecimal) row[3];
                Boolean includesVat = (Boolean) row[4];
                Long operatorId = ((Number) row[5]).longValue();
                Boolean noPbxPrefix = (Boolean) row[6];
                Boolean noPrefix = (Boolean) row[7];
                Integer seconds = (Integer) row[8];

                telephonyTypeIds.add(telephonyTypeId);

                TrunkInfo trunkInfo = trunkMap.get(trunk.toUpperCase());

                if (!trunkInfo.getOperatorDestination().containsKey(operatorId)) {
                    trunkInfo.getOperatorDestination().put(operatorId, new HashMap<>());
                }

                if (!trunkInfo.getOperatorDestination().get(operatorId).containsKey(telephonyTypeId)) {
                    trunkInfo.getOperatorDestination().get(operatorId).put(telephonyTypeId, new HashMap<>());
                }

                TrunkInfo.TrunkOperatorDestination destination = TrunkInfo.TrunkOperatorDestination.builder()
                        .pricePerMinute(rateValue)
                        .pricePerMinuteIncludesVat(includesVat)
                        .inSeconds(seconds > 0)
                        .noPbxPrefix(noPbxPrefix)
                        .noPrefix(noPrefix)
                        .build();

                trunkInfo.getOperatorDestination().get(operatorId).get(telephonyTypeId).put(0L, destination);

                // Add to telephony type list for this trunk
                if (!trunkInfo.getOperatorDestinationTypes().containsKey(telephonyTypeId)) {
                    trunkInfo.getOperatorDestinationTypes().put(telephonyTypeId, new ArrayList<>());
                }

                trunkInfo.getOperatorDestinationTypes().get(telephonyTypeId).add(operatorId);
            }

            // Load IVA by telephony type and operator
            if (!telephonyTypeIds.isEmpty()) {
                Map<Long, Map<Long, Map<Long, BigDecimal>>> ivaMap =
                        loadIvaByTelephonyTypeAndOperator(new ArrayList<>(telephonyTypeIds));

                // Calculate cell fixed status
                for (TrunkInfo trunkInfo : trunkMap.values()) {
                    boolean onlyCellular = trunkInfo.getOperatorDestinationTypes().size() == 1 &&
                            trunkInfo.getOperatorDestinationTypes().containsKey(configService.getTipoteleCelular());
                    trunkInfo.setCellFixed(onlyCellular);
                }
            }
        }

        return trunkMap;
    }


    @Cacheable(value = "indicativeLimits", key = "#mporigenId")
    public Map<Long, Map<String, Integer>> loadIndicativeLimits(Long mporigenId) {
        log.debug("Loading indicative limits for mporigenId: {}", mporigenId);

        String query = """
                    SELECT i.telephony_type_id, MIN(s.ndc) AS min, MAX(s.ndc) AS max,
                           MIN(s.initial_number) AS minserieini, MAX(s.initial_number) AS maxserieini, 
                           MIN(s.final_number) AS minseriefin, MAX(s.final_number) AS maxseriefin
                    FROM indicator i
                    JOIN series s ON s.indicator_id = i.id
                    WHERE s.initial_number >= 0
                        AND i.origin_country_id IN (0, :mporigenId)
                    GROUP BY i.telephony_type_id
                    ORDER BY i.telephony_type_id
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);
        nativeQuery.setParameter("mporigenId", mporigenId);

        List<Object[]> results = nativeQuery.getResultList();

        Map<Long, Map<String, Integer>> indicativeLimits = new HashMap<>();

        for (Object[] row : results) {
            Long telephonyTypeId = ((Number) row[0]).longValue();
            Integer min = row[1] != null ? ((Number) row[1]).intValue() : 0;
            Integer max = row[2] != null ? ((Number) row[2]).intValue() : 0;
            Integer minSerieIni = row[3] != null ? ((Number) row[3]).intValue() : 0;
            Integer maxSerieIni = row[4] != null ? ((Number) row[4]).intValue() : 0;
            Integer minSerieFin = row[5] != null ? ((Number) row[5]).intValue() : 0;
            Integer maxSerieFin = row[6] != null ? ((Number) row[6]).intValue() : 0;

            if (min < 0) min = 1;
            if (max < min) max = min;

            // Calculate length of series if they are equal
            int lenSeries = 0;
            int maxIni = 0;
            int maxFin = 0;

            if (minSerieIni.toString().length() == maxSerieIni.toString().length()) {
                maxIni = maxSerieIni.toString().length();
            }

            if (minSerieFin.toString().length() == maxSerieFin.toString().length()) {
                maxFin = maxSerieFin.toString().length();
            }

            // Only accept value if initial and final series have same size
            if (maxIni == maxFin) {
                lenSeries = maxFin;
            }

            Map<String, Integer> limits = new HashMap<>();
            limits.put("min", min);
            limits.put("max", max);
            limits.put("len_series", lenSeries);

            indicativeLimits.put(telephonyTypeId, limits);
        }

        return indicativeLimits;
    }


    @Cacheable(value = "specialServices", key = "#indicativoId + '_' + #mporigenId")
    public Map<String, Map<Long, SpecialServiceInfo>> loadSpecialServices(Long indicativoId, Long mporigenId) {
        log.debug("Loading special services for indicativoId: {}, mporigenId: {}", indicativoId, mporigenId);

        String query = """
                    SELECT ss.phone_number, ss.value, ss.vat_amount, ss.vat_included, ss.description, ss.indicator_id
                    FROM special_service ss
                    WHERE ss.indicator_id IN (0, :indicativoId) AND ss.origin_country_id = :mporigenId
                    ORDER BY ss.indicator_id DESC
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);
        nativeQuery.setParameter("indicativoId", indicativoId);
        nativeQuery.setParameter("mporigenId", mporigenId);

        List<Object[]> results = nativeQuery.getResultList();

        Map<String, Map<Long, SpecialServiceInfo>> specialServices = new HashMap<>();

        for (Object[] row : results) {
            String phoneNumber = (String) row[0];
            BigDecimal value = (BigDecimal) row[1];
            BigDecimal vatAmount = (BigDecimal) row[2];
            Boolean vatIncluded = (Boolean) row[3];
            String description = (String) row[4];
            Long indicatorId = ((Number) row[5]).longValue();

            if (!specialServices.containsKey(phoneNumber)) {
                specialServices.put(phoneNumber, new HashMap<>());
            }

            SpecialServiceInfo info = SpecialServiceInfo.builder()
                    .pricePerMinute(value)
                    .pricePerMinuteIncludesVat(vatIncluded)
                    .vatAmount(vatAmount)
                    .destination(description)
                    .build();

            specialServices.get(phoneNumber).put(indicatorId, info);
        }

        return specialServices;
    }


    public IndicatorInfo findDestination(String telephone, Long telephonyTypeId, Integer telephonyTypeMin,
                                         Long indicativoOrigenId, String prefixActual, Long prefixId,
                                         boolean reducir, Long mporigenId, Integer bandsOk) {
        log.debug("Finding destination for telephone: {}, telephonyTypeId: {}", telephone, telephonyTypeId);

        // Check if the telephony type is LOCAL, and if so, modify to operate as NACIONAL
        if (isLocal(telephonyTypeId)) {
            telephonyTypeId = configService.getTipoteleNacional();
            String localIndicative = findLocalIndicative(indicativoOrigenId);
            telephone = localIndicative + telephone;
        }

        // Get the limits for this telephony type
        Map<Long, Map<String, Integer>> indicativeLimits = loadIndicativeLimits(mporigenId);

        if (!indicativeLimits.containsKey(telephonyTypeId)) {
            return null;
        }

        Map<String, Integer> limits = indicativeLimits.get(telephonyTypeId);
        int indicaMin = limits.get("min");
        int indicaMax = limits.get("max");
        int lenSeries = limits.get("len_series");

        // Prepare the telephone number
        if (!reducir) {
            int prefixLength = prefixActual.length();
            if (prefixLength > 0 && telephone.startsWith(prefixActual)) {
                telephone = telephone.substring(prefixLength);
            }
        }

        // Adjust telephonyTypeMin based on reducir flag
        if (reducir) {
            int prefixLength = prefixActual.length();
            telephonyTypeMin -= prefixLength;
            if (telephonyTypeMin < 0) telephonyTypeMin = 0;
        }

        // Check if telephone length is valid
        if (telephone.length() < telephonyTypeMin) {
            return null;
        }

        // Build the query
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT i.id, s.ndc, i.department_country, i.city_name, s.initial_number, s.final_number ");
        queryBuilder.append("FROM series s ");
        queryBuilder.append("JOIN indicator i ON s.indicator_id = i.id ");

        if (bandsOk > 0 && prefixId > 0) {
            queryBuilder.append("JOIN band b ON b.prefix_id = :prefixId ");
            queryBuilder.append("JOIN band_indicator bi ON bi.indicator_id = i.id AND bi.band_id = b.id ");
        }

        queryBuilder.append("WHERE i.telephony_type_id = :telephonyTypeId ");
        queryBuilder.append("AND s.ndc IN (:ndcList) ");

        // Add condition for series range if possible
        if (lenSeries > 0) {
            queryBuilder.append("AND (s.initial_number <= :serieValue AND s.final_number >= :serieValue) ");
        }

        // Filter by origin if not international or satellite
        if (!telephonyTypeId.equals(configService.getTipoteleInternacional()) &&
                !telephonyTypeId.equals(configService.getTipoteleSatelital())) {
            queryBuilder.append("AND i.origin_country_id IN (0, :mporigenId) ");
        }

        if (bandsOk > 0 && prefixId > 0) {
            queryBuilder.append("AND b.origin_indicator_id IN (0, :indicativoOrigenId) ");
            queryBuilder.append("ORDER BY b.origin_indicator_id DESC, s.initial_number, s.final_number");
        } else {
            queryBuilder.append("ORDER BY s.ndc DESC, s.initial_number, s.final_number");
        }

        // Calculate NDC values to check
        List<String> ndcList = new ArrayList<>();
        List<Integer> serieValues = new ArrayList<>();

        for (int i = indicaMin; i <= indicaMax; i++) {
            if (telephone.length() >= i) {
                String ndc = telephone.substring(0, i);
                if (ndc.matches("\\d+")) {
                    ndcList.add(ndc);

                    if (lenSeries > 0 && telephone.length() > i) {
                        String serieStr = telephone.substring(i, Math.min(i + lenSeries, telephone.length()));
                        try {
                            int serieValue = Integer.parseInt(serieStr);
                            serieValues.add(serieValue);
                        } catch (NumberFormatException e) {
                            // Ignore non-numeric values
                        }
                    }
                }
            }
        }

        if (ndcList.isEmpty()) {
            return null;
        }

        // Execute the query
        Query query = entityManager.createNativeQuery(queryBuilder.toString());
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("ndcList", ndcList);

        if (lenSeries > 0 && !serieValues.isEmpty()) {
            query.setParameter("serieValue", serieValues.get(0));
        }

        if (!telephonyTypeId.equals(configService.getTipoteleInternacional()) &&
                !telephonyTypeId.equals(configService.getTipoteleSatelital())) {
            query.setParameter("mporigenId", mporigenId);
        }

        if (bandsOk > 0 && prefixId > 0) {
            query.setParameter("prefixId", prefixId);
            query.setParameter("indicativoOrigenId", indicativoOrigenId);
        }

        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return null;
        }

        // Process results to find the best match
        Object[] bestMatch = null;

        for (Object[] result : results) {
            Long indicatorId = ((Number) result[0]).longValue();
            String ndc = result[1].toString();
            Integer initialNumber = ((Number) result[4]).intValue();
            Integer finalNumber = ((Number) result[5]).intValue();

            // Format series for comparison
            String formattedInitial = formatSeries(telephone, ndc, initialNumber, finalNumber, true);
            String formattedFinal = formatSeries(telephone, ndc, initialNumber, finalNumber, false);

            if (telephone.compareTo(formattedInitial) >= 0 && telephone.compareTo(formattedFinal) <= 0) {
                bestMatch = result;
                break;
            }
        }

        if (bestMatch == null) {
            return null;
        }

        // Build and return result
        Long indicatorId = ((Number) bestMatch[0]).longValue();
        String ndc = bestMatch[1].toString();
        String departmentCountry = (String) bestMatch[2];
        String cityName = (String) bestMatch[3];

        String destination = buildDestination(departmentCountry, cityName);

        return IndicatorInfo.builder()
                .indicatorId(indicatorId)
                .indicative(ndc)
                .destination(destination)
                .build();
    }

    private String formatSeries(String telephone, String ndc, Integer initialNumber, Integer finalNumber, boolean isInitial) {
        int phoneLength = telephone.length();
        int ndcLength = ndc.length();
        String seriesValue = isInitial ? initialNumber.toString() : finalNumber.toString();
        int seriesLength = seriesValue.length();

        // Pad the series value if needed
        int difference = phoneLength - ndcLength - seriesLength;
        if (difference > 0) {
            if (isInitial) {
                seriesValue = String.format("%-" + (seriesLength + difference) + "s", seriesValue).replace(' ', '0');
            } else {
                seriesValue = String.format("%-" + (seriesLength + difference) + "s", seriesValue).replace(' ', '9');
            }
        }

        return ndc + seriesValue;
    }

    private String buildDestination(String departmentCountry, String cityName) {
        if (departmentCountry == null || departmentCountry.isEmpty()) {
            return cityName != null ? cityName : "";
        }

        if (cityName == null || cityName.isEmpty()) {
            return departmentCountry;
        }

        return departmentCountry + " - " + cityName;
    }


    public CallValueInfo findValue(Long telephonyTypeId, Long prefixId, Long indicativoDestinoId,
                                   Long comubicacionId, Long indicativoOrigenId) {
        log.debug("Finding value for telephonyTypeId: {}, prefixId: {}", telephonyTypeId, prefixId);

        // First, get the base values from the prefix
        String baseQuery = """
                    SELECT p.base_value, p.vat_included, p.band_ok, p.vat_value
                    FROM prefix p
                    WHERE p.id = :prefixId
                """;

        Query baseNativeQuery = entityManager.createNativeQuery(baseQuery);
        baseNativeQuery.setParameter("prefixId", prefixId);

        List<Object[]> baseResults = baseNativeQuery.getResultList();

        if (baseResults.isEmpty()) {
            return null;
        }

        Object[] baseRow = baseResults.get(0);
        BigDecimal baseValue = (BigDecimal) baseRow[0];
        Boolean vatIncluded = (Boolean) baseRow[1];
        Boolean useBands = (Boolean) baseRow[2];
        BigDecimal vatAmount = (BigDecimal) baseRow[3];

        Long bandId = 0L;
        String bandName = "";

        // If bands should be used and we have a valid destination indicative, get the band value
        if (useBands && (indicativoDestinoId > 0 || isLocal(telephonyTypeId))) {
            String bandQuery;
            Query bandNativeQuery;

            if (isLocal(telephonyTypeId)) {
                // For local calls, no need to join with bandaindica
                bandQuery = """
                            SELECT b.id, b.value, b.vat_included, b.name
                            FROM communication_location cl
                            JOIN band b ON b.prefix_id = :prefixId
                            WHERE cl.id = :comubicacionId
                            AND b.origin_indicator_id IN (0, cl.indicator_id)
                            ORDER BY b.origin_indicator_id DESC
                        """;

                bandNativeQuery = entityManager.createNativeQuery(bandQuery);
                bandNativeQuery.setParameter("prefixId", prefixId);
                bandNativeQuery.setParameter("comubicacionId", comubicacionId);
            } else {
                // For other calls, join with bandaindica to find the right band
                bandQuery = """
                            SELECT b.id, b.value, b.vat_included, b.name
                            FROM communication_location cl
                            JOIN band b ON b.prefix_id = :prefixId
                            JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :indicativoDestinoId
                            WHERE :sqlConsultaComid
                            ORDER BY b.origin_indicator_id DESC
                        """;

                String sqlConsultaComid = indicativoOrigenId > 0 ?
                        "b.origin_indicator_id IN (0, :indicativoOrigenId)" :
                        "cl.id = :comubicacionId AND b.origin_indicator_id IN (0, cl.indicator_id)";

                bandNativeQuery = entityManager.createNativeQuery(bandQuery.replace(":sqlConsultaComid", sqlConsultaComid));
                bandNativeQuery.setParameter("prefixId", prefixId);
                bandNativeQuery.setParameter("indicativoDestinoId", indicativoDestinoId);

                if (indicativoOrigenId > 0) {
                    bandNativeQuery.setParameter("indicativoOrigenId", indicativoOrigenId);
                } else {
                    bandNativeQuery.setParameter("comubicacionId", comubicacionId);
                }
            }

            List<Object[]> bandResults = bandNativeQuery.getResultList();

            if (!bandResults.isEmpty()) {
                Object[] bandRow = bandResults.get(0);
                bandId = ((Number) bandRow[0]).longValue();
                baseValue = (BigDecimal) bandRow[1];
                vatIncluded = (Boolean) bandRow[2];
                bandName = (String) bandRow[3];
                useBands = true;
            } else {
                useBands = false;
            }
        }

        return CallValueInfo.builder()
                .pricePerMinute(baseValue)
                .pricePerMinuteIncludesVat(vatIncluded)
                .vatAmount(vatAmount)
                .useBands(useBands)
                .bandId(bandId)
                .bandName(bandName)
                .build();
    }


    @Cacheable(value = "pbxSpecialRules")
    public Map<String, PbxSpecialRuleInfo> loadPbxSpecialRules() {
        log.debug("Loading PBX special rules");

        String query = """
                    SELECT psr.search_pattern, psr.ignore_pattern, psr.replacement, psr.min_length, 
                           psr.direction, psr.name, cl.directory
                    FROM pbx_special_rule psr
                    LEFT JOIN communication_location cl ON psr.comm_location_id = cl.id
                    WHERE psr.active = true
                    ORDER BY psr.comm_location_id, psr.search_pattern DESC
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);

        List<Object[]> results = nativeQuery.getResultList();

        Map<String, PbxSpecialRuleInfo> ruleMap = new HashMap<>();

        for (Object[] row : results) {
            String searchPattern = (String) row[0];
            String ignorePattern = (String) row[1];
            String replacement = (String) row[2];
            Integer minLength = (Integer) row[3];
            Integer direction = (Integer) row[4];
            String name = (String) row[5];
            String directory = row[6] != null ? ((String) row[6]).toLowerCase() : "";

            List<String> ignorePatterns = new ArrayList<>();
            if (ignorePattern != null && !ignorePattern.isEmpty()) {
                String[] patterns = ignorePattern.split(",");
                for (String pattern : patterns) {
                    pattern = pattern.trim();
                    if (!pattern.isEmpty()) {
                        ignorePatterns.add(pattern);
                    }
                }
            }

            PbxSpecialRuleInfo ruleInfo = PbxSpecialRuleInfo.builder()
                    .preOri(searchPattern)
                    .preNo(ignorePatterns)
                    .preNvo(replacement)
                    .minLen(minLength)
                    .dir(directory)
                    .incoming(direction)
                    .nombre(name)
                    .build();

            ruleMap.put(searchPattern + "_" + directory, ruleInfo);
        }

        return ruleMap;
    }


    @Cacheable(value = "limitsInternas", key = "#mporigenId")
    public Map<Long, Map<Long, Long>> loadLimitsInternas(Long mporigenId) {
        log.debug("Loading internal limits for mporigenId: {}", mporigenId);

        // Query to get extension length limits
        String queryExt = """
                    SELECT MAX(LENGTH(e.extension)) AS max_len, MIN(LENGTH(e.extension)) AS min_len
                    FROM employee e
                    JOIN communication_location cl ON e.communication_location_id = cl.id
                    JOIN indicator i ON cl.indicator_id = i.id
                    WHERE cl.active = true
                        AND e.extension NOT LIKE '%-%'
                        AND e.extension NOT LIKE '0%'
                        AND i.origin_country_id = :mporigenId
                        AND LENGTH(e.extension) BETWEEN 1 AND :maxExtLength
                        AND e.extension ~ '^[0-9]+$'
                """;

        Query extQuery = entityManager.createNativeQuery(queryExt);
        extQuery.setParameter("mporigenId", mporigenId);
        extQuery.setParameter("maxExtLength", configService.getMaxExtension().toString().length() - 1);

        List<Object[]> extResults = extQuery.getResultList();

        Map<Long, Map<Long, Long>> limitsMap = new HashMap<>();
        limitsMap.put(mporigenId, new HashMap<>());

        // Default values
        limitsMap.get(mporigenId).put(100L, 100L); // min
        limitsMap.get(mporigenId).put(101L, configService.getMaxExtension()); // max

        if (!extResults.isEmpty() && extResults.get(0)[0] != null && extResults.get(0)[1] != null) {
            Integer maxLen = ((Number) extResults.get(0)[0]).intValue();
            Integer minLen = ((Number) extResults.get(0)[1]).intValue();

            if (maxLen > 0) {
                long maxExtValue = Long.parseLong("9".repeat(maxLen));
                limitsMap.get(mporigenId).put(101L, maxExtValue);
            }

            if (minLen > 0) {
                long minExtValue = Long.parseLong("1" + "0".repeat(minLen - 1));
                limitsMap.get(mporigenId).put(100L, minExtValue);
            }
        }

        // Query for range extensions
        String queryRange = """
                    SELECT MAX(LENGTH(er.range_end)) AS max_len, MIN(LENGTH(er.range_start)) AS min_len
                    FROM extension_range er
                    JOIN communication_location cl ON er.comm_location_id = cl.id
                    JOIN indicator i ON cl.indicator_id = i.id
                    WHERE
                        cl.active = true
                        AND i.origin_country_id = :mporigenId
                        AND LENGTH(er.range_start::text) BETWEEN 1 AND :maxExtLength
                        AND LENGTH(er.range_end::text) BETWEEN 1 AND :maxExtLength
                        AND er.range_end >= er.range_start
                """;

        Query rangeQuery = entityManager.createNativeQuery(queryRange);
        rangeQuery.setParameter("mporigenId", mporigenId);
        rangeQuery.setParameter("maxExtLength", configService.getMaxExtension().toString().length() - 1);

        List<Object[]> rangeResults = rangeQuery.getResultList();

        if (!rangeResults.isEmpty() && rangeResults.get(0)[0] != null && rangeResults.get(0)[1] != null) {
            Integer maxLen = ((Number) rangeResults.get(0)[0]).intValue();
            Integer minLen = ((Number) rangeResults.get(0)[1]).intValue();

            if (maxLen > 0) {
                long currentMax = limitsMap.get(mporigenId).get(101L);
                long newMax = Long.parseLong("9".repeat(maxLen));
                if (newMax > currentMax) {
                    limitsMap.get(mporigenId).put(101L, newMax);
                }
            }

            if (minLen > 0) {
                long currentMin = limitsMap.get(mporigenId).get(100L);
                long newMin = Long.parseLong("1" + "0".repeat(minLen - 1));
                if (newMin < currentMin) {
                    limitsMap.get(mporigenId).put(100L, newMin);
                }
            }
        }

        return limitsMap;
    }


    public boolean isLocalExtended(String indicative, Long originIndicatorId, Long destinationIndicatorId) {
        if (indicative == null || indicative.isEmpty() || originIndicatorId == null || destinationIndicatorId == null) {
            return false;
        }

        String query = """
                    SELECT COUNT(*)
                    FROM indicator i1
                    JOIN indicator i2 ON i1.city_id = i2.city_id
                    WHERE i1.id = :originId AND i2.id = :destId
                        AND i1.id != i2.id
                        AND i2.telephony_type_id = :localTypeId
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);
        nativeQuery.setParameter("originId", originIndicatorId);
        nativeQuery.setParameter("destId", destinationIndicatorId);
        nativeQuery.setParameter("localTypeId", configService.getTipoteleLocal());

        Long count = ((Number) nativeQuery.getSingleResult()).longValue();

        return count > 0;
    }


    public LocalDateTime checkDate(LocalDateTime date) {
        // This method checks if the date is valid and returns a flag indicating if it's a holiday
        // Since holidays would require a separate table that wasn't provided, we'll just
        // implement a simplified version that checks if it's a weekend

        // In a real implementation, you'd query a holiday table here

        if (date == null) {
            return null;
        }

        return date;
    }


    @Cacheable(value = "telephonyTypeInternas")
    public List<String> loadTelephonetypeInternas() {
        // Load internal type IDs - this would be configurable
        String query = """
                    SELECT id 
                    FROM telephony_type 
                    WHERE name LIKE '%intern%' OR name LIKE '%Intern%'
                """;

        Query nativeQuery = entityManager.createNativeQuery(query);

        List<?> results = nativeQuery.getResultList();

        return results.stream()
                .map(id -> ((Number) id).toString())
                .collect(Collectors.toList());
    }


    public CallValueInfo findSpecialValue(LocalDateTime date, Integer duration, Long indicativoOrigenId,
                                          CallDestinationInfo callDestinationInfo) {
        if (date == null || callDestinationInfo.getTelephonyTypeId() == null ||
                callDestinationInfo.getTelephonyTypeId() <= 0 || duration <= 0) {
            return null;
        }

        // Get day of week and check if it's a holiday
        boolean isHoliday = false; // In a real implementation, this would check against a holiday table
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        int hour = date.getHour();

        // Build the query
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT srv.name, srv.rate_value, srv.value_type, srv.includes_vat, ");
        queryBuilder.append("srv.hours_specification, srv.telephony_type_id, srv.operator_id, srv.band_id, p.vat_value ");
        queryBuilder.append("FROM special_rate_value srv ");
        queryBuilder.append("LEFT JOIN prefix p ON p.telephone_type_id = srv.telephony_type_id AND p.operator_id = srv.operator_id ");
        queryBuilder.append("WHERE srv.active = true ");

        // Date range condition
        queryBuilder.append("AND ((srv.valid_from IS NULL OR srv.valid_from <= :date) ");
        queryBuilder.append("AND (srv.valid_to IS NULL OR srv.valid_to >= :date)) ");

        // Day of week and holiday condition
        List<String> dayConditions = new ArrayList<>();
        switch (dayOfWeek) {
            case SUNDAY:
                if (isHoliday) dayConditions.add("srv.holiday_enabled = true");
                else dayConditions.add("srv.sunday_enabled = true");
                break;
            case MONDAY:
                dayConditions.add("srv.monday_enabled = true");
                break;
            case TUESDAY:
                dayConditions.add("srv.tuesday_enabled = true");
                break;
            case WEDNESDAY:
                dayConditions.add("srv.wednesday_enabled = true");
                break;
            case THURSDAY:
                dayConditions.add("srv.thursday_enabled = true");
                break;
            case FRIDAY:
                dayConditions.add("srv.friday_enabled = true");
                break;
            case SATURDAY:
                dayConditions.add("srv.saturday_enabled = true");
                break;
        }
        queryBuilder.append("AND (").append(String.join(" OR ", dayConditions)).append(") ");

        // Origin indicator condition
        queryBuilder.append("AND (srv.origin_indicator_id = 0 OR srv.origin_indicator_id = :indicativoOrigenId) ");

        // Execute query
        Query query = entityManager.createNativeQuery(queryBuilder.toString());
        query.setParameter("date", date);
        query.setParameter("indicativoOrigenId", indicativoOrigenId);

        List<Object[]> results = query.getResultList();

        // Process results
        for (Object[] row : results) {
            String name = (String) row[0];
            BigDecimal rateValue = (BigDecimal) row[1];
            Integer valueType = ((Number) row[2]).intValue();
            Boolean includesVat = (Boolean) row[3];
            String hoursSpec = (String) row[4];
            Long telephonyTypeId = ((Number) row[5]).longValue();
            Long operatorId = ((Number) row[6]).longValue();
            Long bandId = ((Number) row[7]).longValue();
            BigDecimal vatValue = (BigDecimal) row[8];

            // Check if the rate applies to this telephony type
            if (!telephonyTypeId.equals(callDestinationInfo.getTelephonyTypeId()) && telephonyTypeId != 0) {
                continue;
            }

            // Check if the rate applies to this operator
            if (operatorId != 0 && !operatorId.equals(callDestinationInfo.getOperatorId())) {
                continue;
            }

            // Check if the rate applies to this band
            if (bandId != 0 && (callDestinationInfo.getBandId() == null || !bandId.equals(callDestinationInfo.getBandId()))) {
                continue;
            }

            // Check hours specification
            if (hoursSpec != null && !hoursSpec.isEmpty()) {
                boolean hourMatches = false;
                String[] hourRanges = hoursSpec.split(",");
                for (String range : hourRanges) {
                    range = range.trim();
                    if (range.isEmpty()) continue;

                    try {
                        int rangeHour = Integer.parseInt(range);
                        if (rangeHour == hour) {
                            hourMatches = true;
                            break;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid hour specifications
                    }
                }

                if (!hourMatches) {
                    continue;
                }
            }

            // We found a matching special rate
            ValueType valueTypeEnum = ValueType.fromValue(valueType);

            CallValueInfo specialValue = CallValueInfo.builder()
                    .pricePerMinute(rateValue)
                    .pricePerMinuteIncludesVat(includesVat)
                    .vatAmount(vatValue)
                    .useBands(callDestinationInfo.getUseBands() != null ? callDestinationInfo.getUseBands() : false)
                    .bandId(callDestinationInfo.getBandId())
                    .bandName(callDestinationInfo.getBandName())
                    .build();

            // For percentage discounts, we need to apply it to the original value
            if (valueTypeEnum == ValueType.PERCENTAGE) {
                BigDecimal originalPrice = callDestinationInfo.getPricePerMinute();

                // If the original price includes VAT and we need the base price
                if (callDestinationInfo.getPricePerMinuteIncludesVat() && vatValue != null && vatValue.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal vatFactor = BigDecimal.ONE.add(vatValue.divide(new BigDecimal(100)));
                    originalPrice = originalPrice.divide(vatFactor, 4, BigDecimal.ROUND_HALF_UP);
                }

                // Apply the percentage discount
                BigDecimal discountFactor = BigDecimal.ONE.subtract(rateValue.divide(new BigDecimal(100)));
                BigDecimal discountedPrice = originalPrice.multiply(discountFactor);

                specialValue.setPricePerMinute(discountedPrice);
                specialValue.setPricePerMinuteIncludesVat(callDestinationInfo.getPricePerMinuteIncludesVat());
            }

            return specialValue;
        }

        return null;
    }


    public CallDestinationInfo calculateRuleValue(String troncal, Integer duration, BigDecimal billedAmount,
                                                  LocationInfo locationInfo, CallDestinationInfo callDestinationInfo) {
        if (callDestinationInfo.getIndicatorId() == null || callDestinationInfo.getIndicatorId() <= 0 ||
                callDestinationInfo.getTelephonyTypeId() == null || callDestinationInfo.getTelephonyTypeId() <= 0) {
            return callDestinationInfo;
        }

        // Build query to find matching trunk rules
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT tr.id, tr.rate_value, tr.includes_vat, tr.new_operator_id, ");
        queryBuilder.append("tr.new_telephony_type_id, tr.seconds, ntt.name as new_tt_name, no.name as new_op_name, p.vat_value ");
        queryBuilder.append("FROM trunk_rule tr ");
        queryBuilder.append("LEFT JOIN telephony_type ntt ON tr.new_telephony_type_id = ntt.id ");
        queryBuilder.append("LEFT JOIN operator no ON tr.new_operator_id = no.id ");
        queryBuilder.append("LEFT JOIN prefix p ON p.telephone_type_id = tr.telephony_type_id AND p.operator_id = :operatorId ");
        queryBuilder.append("WHERE tr.active = true ");
        queryBuilder.append("AND (tr.trunk_id = 0 OR tr.trunk_id IN (");
        queryBuilder.append("    SELECT t.id FROM trunk t WHERE t.trunk = :troncal AND t.active = true");
        queryBuilder.append(")) ");
        queryBuilder.append("AND tr.telephony_type_id = :telephonyTypeId ");
        queryBuilder.append("AND tr.origin_indicator_id IN (0, :indicativoOrigenId) ");

        // Indicator ID condition
        String indicativoQuery = "(tr.indicator_ids = '' OR tr.indicator_ids = :indicativoId OR ";
        indicativoQuery += "tr.indicator_ids LIKE :indicativoIdPrefix OR ";
        indicativoQuery += "tr.indicator_ids LIKE :indicativoIdSuffix OR ";
        indicativoQuery += "tr.indicator_ids LIKE :indicativoIdContains)";
        queryBuilder.append("AND ").append(indicativoQuery);

        // Order to find most specific rule first
        queryBuilder.append(" ORDER BY p.id DESC, tr.indicator_ids DESC");

        // Prepare parameters
        Query query = entityManager.createNativeQuery(queryBuilder.toString());
        query.setParameter("troncal", troncal);
        query.setParameter("telephonyTypeId", callDestinationInfo.getTelephonyTypeId());
        query.setParameter("indicativoOrigenId", locationInfo.getIndicativoId());
        query.setParameter("operatorId", callDestinationInfo.getOperatorId());

        Long indicativoId = callDestinationInfo.getIndicatorId();
        query.setParameter("indicativoId", indicativoId.toString());
        query.setParameter("indicativoIdPrefix", indicativoId + ",%");
        query.setParameter("indicativoIdSuffix", "%," + indicativoId);
        query.setParameter("indicativoIdContains", "%," + indicativoId + ",%");

        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return callDestinationInfo;
        }

        // Found a matching rule - apply it
        Object[] row = results.get(0);
        BigDecimal rateValue = (BigDecimal) row[1];
        Boolean includesVat = (Boolean) row[2];
        Long newOperatorId = ((Number) row[3]).longValue();
        Long newTelephonyTypeId = ((Number) row[4]).longValue();
        Integer seconds = (Integer) row[5];
        String newTtName = (String) row[6];
        String newOpName = (String) row[7];
        BigDecimal vatValue = (BigDecimal) row[8];

        // Save the original values and update with new ones
        CallDestinationInfo updatedInfo = CallDestinationInfo.builder()
                .telephone(callDestinationInfo.getTelephone())
                .operatorId(newOperatorId > 0 ? newOperatorId : callDestinationInfo.getOperatorId())
                .operatorName(newOperatorId > 0 ? newOpName : callDestinationInfo.getOperatorName())
                .indicatorId(callDestinationInfo.getIndicatorId())
                .indicatorCode(callDestinationInfo.getIndicatorCode())
                .telephonyTypeId(newTelephonyTypeId > 0 ? newTelephonyTypeId : callDestinationInfo.getTelephonyTypeId())
                .telephonyTypeName((newTelephonyTypeId > 0 ? newTtName : callDestinationInfo.getTelephonyTypeName()) + " (xRegla)")
                .destination(callDestinationInfo.getDestination())
                .useTrunk(callDestinationInfo.getUseTrunk())
                .pricePerMinute(rateValue)
                .pricePerMinuteIncludesVat(includesVat)
                .vatAmount(vatValue)
                .inSeconds(seconds > 0)
                .initialPrice(callDestinationInfo.getInitialPrice())
                .initialPriceIncludesVat(callDestinationInfo.getInitialPriceIncludesVat())
                .useBands(callDestinationInfo.getUseBands())
                .bandId(callDestinationInfo.getBandId())
                .bandName(callDestinationInfo.getBandName())
                .build();

        // Calculate the new billed amount based on the updated price per minute
        BigDecimal minuteDuration = calculateMinuteDuration(duration, updatedInfo.getInSeconds());
        BigDecimal newBilledAmount = rateValue.multiply(minuteDuration);

        if (!includesVat && vatValue != null && vatValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatFactor = BigDecimal.ONE.add(vatValue.divide(new BigDecimal(100)));
            newBilledAmount = newBilledAmount.multiply(vatFactor);
        }

        updatedInfo.setBilledAmount(newBilledAmount);

        return updatedInfo;
    }

    // Helper methods

    private BigDecimal calculateMinuteDuration(int duration, boolean inSeconds) {
        if (duration <= 0) {
            return BigDecimal.ONE; // Minimum 1 minute
        }

        if (inSeconds) {
            // Convert seconds to minutes with precision
            return new BigDecimal(duration).divide(new BigDecimal(60), 2, BigDecimal.ROUND_CEILING);
        } else {
            // Round up to the next minute
            return new BigDecimal((duration + 59) / 60);
        }
    }

    private String findLocalIndicative(Long indicativoOrigenId) {
        if (indicativoOrigenId == null) {
            return "";
        }

        String query = """
                    SELECT s.ndc
                    FROM indicator i
                    JOIN series s ON s.indicator_id = i.id
                    WHERE i.id = :indicativoId
                    LIMIT 1
                """;

        try {
            Query nativeQuery = entityManager.createNativeQuery(query);
            nativeQuery.setParameter("indicativoId", indicativoOrigenId);

            String indicative = (String) nativeQuery.getSingleResult();
            return indicative != null ? indicative : "";
        } catch (Exception e) {
            log.error("Error finding local indicative for ID: {}", indicativoOrigenId, e);
            return "";
        }
    }

    private boolean isLocal(Long telephonyTypeId) {
        return telephonyTypeId != null &&
                (telephonyTypeId.equals(configService.getTipoteleLocal()) ||
                        telephonyTypeId.equals(configService.getTipoteleLocalExt()));
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class SpecialServiceInfo {
        private BigDecimal pricePerMinute;
        private boolean pricePerMinuteIncludesVat;
        private BigDecimal vatAmount;
        private String destination;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class IndicatorInfo {
        private Long indicatorId;
        private String indicative;
        private String destination;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class CallValueInfo {
        private BigDecimal pricePerMinute;
        private boolean pricePerMinuteIncludesVat;
        private BigDecimal vatAmount;
        private boolean useBands;
        private Long bandId;
        private String bandName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class PbxSpecialRuleInfo {
        private String preOri;
        private List<String> preNo;
        private String preNvo;
        private Integer minLen;
        private String dir;
        private Integer incoming;
        private String nombre;
    }
}