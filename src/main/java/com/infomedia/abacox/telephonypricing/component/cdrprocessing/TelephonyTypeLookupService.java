// File: com/infomedia/abacox/telephonypricing/cdr/TelephonyTypeLookupService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@Log4j2
public class TelephonyTypeLookupService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public String getTelephonyTypeName(Long telephonyTypeId) {
        if (telephonyTypeId == null) return TelephonyTypeEnum.ERRORS.getDefaultName();
        try {
            return entityManager.createQuery("SELECT tt.name FROM TelephonyType tt WHERE tt.id = :id AND tt.active = true", String.class)
                    .setParameter("id", telephonyTypeId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return TelephonyTypeEnum.fromId(telephonyTypeId).getDefaultName();
        }
    }

    @Transactional(readOnly = true)
    public PrefixInfo getPrefixInfoForLocalExtended(Long originCountryId) {
        String queryStr = "SELECT p.*, ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephony_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephony_type_id = :localExtendedTypeId LIMIT 1";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("localExtendedTypeId", TelephonyTypeEnum.LOCAL_EXTENDED.getValue());

        try {
            Tuple tuple = (Tuple) nativeQuery.getSingleResult();
            Prefix p = entityManager.find(Prefix.class, tuple.get("id", Number.class).longValue());
            TelephonyTypeConfig cfg = new TelephonyTypeConfig();
            cfg.setMinValue(tuple.get("ttc_min", Number.class) != null ? tuple.get("ttc_min", Number.class).intValue() : 0);
            cfg.setMaxValue(tuple.get("ttc_max", Number.class) != null ? tuple.get("ttc_max", Number.class).intValue() : 99);
            int bandsCount = tuple.get("bands_count", Number.class).intValue();
            return new PrefixInfo(p, cfg, bandsCount);
        } catch (NoResultException e) {
            log.warn("No prefix definition found for LOCAL_EXTENDED type in country {}", originCountryId);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public OperatorInfo getInternalOperatorInfo(Long telephonyTypeId, Long originCountryId) {
        String queryStr = "SELECT o.id, o.name FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                "WHERE p.telephony_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId " +
                "AND o.active = true AND p.active = true " +
                "LIMIT 1";
        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr, Tuple.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Tuple result = (Tuple) query.getSingleResult();
            return new OperatorInfo(result.get("id", Number.class).longValue(), result.get("name", String.class));
        } catch (NoResultException e) {
            log.warn("No internal operator found for telephony type {} and country {}", telephonyTypeId, originCountryId);
            return new OperatorInfo(CdrConfigService.DEFAULT_OPERATOR_ID_FOR_INTERNAL, "UnknownInternalOperator");
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal getVatForPrefix(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        String queryStr = "SELECT p.vat_value FROM prefix p " +
                "WHERE p.active = true AND p.telephony_type_id = :telephonyTypeId AND p.operator_id = :operatorId " +
                "AND EXISTS (SELECT 1 FROM operator o WHERE o.id = p.operator_id AND o.origin_country_id = :originCountryId AND o.active = true) " +
                "LIMIT 1";
        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Object result = query.getSingleResult();
            return result != null ? (BigDecimal) result : BigDecimal.ZERO;
        } catch (NoResultException e) {
            log.warn("No VAT rate found for prefix with type {}, operator {}, country {}", telephonyTypeId, operatorId, originCountryId);
            return BigDecimal.ZERO;
        }
    }

    @Transactional(readOnly = true)
    public TariffValue getBaseTariffValue(Long prefixId, Long destinationIndicatorId,
                                          Long commLocationId, Long originIndicatorIdForBand) {
        String prefixQueryStr = "SELECT p.base_value, p.vat_included, p.vat_value, p.band_ok, p.id as prefix_id, p.telephony_type_id " +
                "FROM prefix p WHERE p.id = :prefixId AND p.active = true";
        jakarta.persistence.Query prefixQuery = entityManager.createNativeQuery(prefixQueryStr, Tuple.class);
        prefixQuery.setParameter("prefixId", prefixId);

        try {
            Tuple pRes = (Tuple) prefixQuery.getSingleResult();
            BigDecimal baseValue = pRes.get("base_value", BigDecimal.class);
            boolean vatIncluded = pRes.get("vat_included", Boolean.class);
            BigDecimal vatValue = pRes.get("vat_value", BigDecimal.class);
            boolean bandOk = pRes.get("band_ok", Boolean.class);
            Long telephonyTypeId = pRes.get("telephony_type_id", Number.class).longValue();


            if (bandOk && (destinationIndicatorId != null && destinationIndicatorId > 0 || isLocalType(telephonyTypeId))) {
                StringBuilder bandQueryBuilder = new StringBuilder(
                    "SELECT b.value as band_value, b.vat_included as band_vat_included " +
                    "FROM band b ");
                if (!isLocalType(telephonyTypeId)) {
                    bandQueryBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :destinationIndicatorId ");
                }
                bandQueryBuilder.append("WHERE b.active = true AND b.prefix_id = :prefixId ");
                bandQueryBuilder.append("AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBand) ");
                bandQueryBuilder.append("ORDER BY b.origin_indicator_id DESC NULLS LAST LIMIT 1");

                jakarta.persistence.Query bandQuery = entityManager.createNativeQuery(bandQueryBuilder.toString(), Tuple.class);
                bandQuery.setParameter("prefixId", prefixId);
                bandQuery.setParameter("originIndicatorIdForBand", originIndicatorIdForBand);
                if (!isLocalType(telephonyTypeId)) {
                    bandQuery.setParameter("destinationIndicatorId", destinationIndicatorId);
                }

                List<Tuple> bandResults = bandQuery.getResultList();
                if (!bandResults.isEmpty()) {
                    Tuple bRes = bandResults.get(0);
                    log.debug("Found band-specific rate for prefixId {}", prefixId);
                    return new TariffValue(
                            bRes.get("band_value", BigDecimal.class),
                            bRes.get("band_vat_included", Boolean.class),
                            vatValue // VAT rate is from prefix, not band
                    );
                }
            }
            return new TariffValue(baseValue, vatIncluded, vatValue);

        } catch (NoResultException e) {
            log.warn("No prefix found for ID: {}", prefixId);
            return new TariffValue(BigDecimal.ZERO, false, BigDecimal.ZERO);
        }
    }

    public boolean isLocalType(Long telephonyTypeId) {
        return telephonyTypeId != null &&
                (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) ||
                        telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
    }

    @Transactional(readOnly = true)
    public TariffValue getInternalTariffValue(Long internalTelephonyTypeId, Long originCountryId) {
        String queryStr = "SELECT p.base_value, p.vat_included, p.vat_value " +
                          "FROM prefix p JOIN operator o ON p.operator_id = o.id " +
                          "WHERE p.active = true AND o.active = true " +
                          "AND p.telephony_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId " +
                          "LIMIT 1";

        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr, Tuple.class);
        query.setParameter("telephonyTypeId", internalTelephonyTypeId);
        query.setParameter("originCountryId", originCountryId);

        try {
            Tuple result = (Tuple) query.getSingleResult();
            return new TariffValue(
                result.get("base_value", BigDecimal.class),
                result.get("vat_included", Boolean.class),
                result.get("vat_value", BigDecimal.class)
            );
        } catch (NoResultException e) {
            log.warn("No internal tariff found for type {} and country {}", internalTelephonyTypeId, originCountryId);
            return new TariffValue(BigDecimal.ZERO, false, BigDecimal.ZERO);
        }
    }


    public boolean isInternalIpType(Long telephonyTypeId) {
        if (telephonyTypeId == null) return false;
        return telephonyTypeId.equals(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_IP.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.NATIONAL_IP.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue());
    }

    public List<Long> getInternalTypeIds() {
        return new ArrayList<>(Arrays.asList(
                TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(),
                TelephonyTypeEnum.LOCAL_IP.getValue(),
                TelephonyTypeEnum.NATIONAL_IP.getValue(),
                TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue()
        ));
    }

    @Transactional(readOnly = true)
    public List<IncomingTelephonyTypePriority> getIncomingTelephonyTypePriorities(Long originCountryId) {
        // PHP's prefijos_OrdenarEntrantes
        // It iterates prefixes, then groups by telephony_type_id, calculating min/max subscriber lengths.
        // Then adds LOCAL if not present. Finally sorts by min_subscriber_length (derived from total_length) descending.

        String queryStr = "SELECT DISTINCT " +
                "p.telephony_type_id, tt.name as telephony_type_name, p.code as prefix_code, " +
                "ttc.min_value as cfg_min_len, ttc.max_value as cfg_max_len " +
                "FROM prefix p " +
                "JOIN telephony_type tt ON p.telephony_type_id = tt.id " +
                "JOIN operator o ON p.operator_id = o.id " +
                "LEFT JOIN telephony_type_config ttc ON p.telephony_type_id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND tt.active = true AND o.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephony_type_id NOT IN (:excludedTypeIds) " +
                "AND p.code IS NOT NULL AND p.code != '' "; // Only consider types that have operator prefixes defined for this initial gathering

        List<Long> excludedTypeIds = new ArrayList<>(getInternalTypeIds());
        excludedTypeIds.add(TelephonyTypeEnum.CELUFIJO.getValue());
        excludedTypeIds.add(TelephonyTypeEnum.SPECIAL_SERVICES.getValue());

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("excludedTypeIds", excludedTypeIds);

        List<Tuple> results = nativeQuery.getResultList();
        Map<Long, IncomingTelephonyTypePriority> typeMap = new HashMap<>();

        for (Tuple row : results) {
            Long ttId = row.get("telephony_type_id", Number.class).longValue();
            String ttName = row.get("telephony_type_name", String.class);
            String prefixCode = row.get("prefix_code", String.class);
            Integer cfgMin = row.get("cfg_min_len", Number.class) != null ? row.get("cfg_min_len", Number.class).intValue() : 0;
            Integer cfgMax = row.get("cfg_max_len", Number.class) != null ? row.get("cfg_max_len", Number.class).intValue() : 99;

            int prefixLen = (prefixCode != null) ? prefixCode.length() : 0;
            int subscriberMinLen = Math.max(0, cfgMin - prefixLen);
            int subscriberMaxLen = Math.max(0, cfgMax - prefixLen);

            IncomingTelephonyTypePriority current = typeMap.get(ttId);
            if (current == null) {
                current = new IncomingTelephonyTypePriority(ttId, ttName, subscriberMinLen, subscriberMaxLen, cfgMin, cfgMax, "");
                typeMap.put(ttId, current);
            } else {
                // If a type is associated with multiple prefixes, take the most lenient subscriber lengths
                // and the original total lengths (cfgMin/Max should be consistent for a type/country).
                // Take the HIGHEST of the minimums.
                current.setMinSubscriberLength(Math.max(current.getMinSubscriberLength(), subscriberMinLen));
                // Take the LOWEST of the maximums.
                current.setMaxSubscriberLength(Math.min(current.getMaxSubscriberLength(), subscriberMaxLen));
            }
        }
        
        // Add LOCAL type explicitly if not covered by prefixes, as PHP does
        if (!typeMap.containsKey(TelephonyTypeEnum.LOCAL.getValue())) {
            TelephonyTypeConfig localCfg = getTelephonyTypeConfig(TelephonyTypeEnum.LOCAL.getValue(), originCountryId);
            int localMin = localCfg != null ? localCfg.getMinValue() : 0;
            int localMax = localCfg != null ? localCfg.getMaxValue() : 99;
            typeMap.put(TelephonyTypeEnum.LOCAL.getValue(), new IncomingTelephonyTypePriority(
                TelephonyTypeEnum.LOCAL.getValue(),
                getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()),
                localMin, // For LOCAL, subscriber length is total length as no operator prefix is stripped
                localMax,
                localMin, // Total min length
                localMax, // Total max length
                ""
            ));
        }

        List<IncomingTelephonyTypePriority> sortedList = new ArrayList<>(typeMap.values());
        for (IncomingTelephonyTypePriority item : sortedList) {
            // This now correctly mimics the PHP logic of sorting by the length of the
            // subscriber number part, not the total number length.
            item.setOrderKey(String.format("%02d", item.getMinSubscriberLength()));
        }

        // The reverse sort on this new key will now produce the correct priority order.
        sortedList.sort(Comparator.comparing(IncomingTelephonyTypePriority::getOrderKey, Comparator.reverseOrder()));
        log.debug("Sorted incoming telephony type priorities for country {}: {}", originCountryId, sortedList);
        return sortedList;
    }

    @Transactional(readOnly = true)
    public TelephonyTypeConfig getTelephonyTypeConfig(Long telephonyTypeId, Long originCountryId) {
        try {
            return entityManager.createQuery(
                            "SELECT ttc FROM TelephonyTypeConfig ttc " +
                                    "WHERE ttc.telephonyTypeId = :telephonyTypeId AND ttc.originCountryId = :originCountryId AND ttc.active = true",
                            TelephonyTypeConfig.class)
                    .setParameter("telephonyTypeId", telephonyTypeId)
                    .setParameter("originCountryId", originCountryId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}