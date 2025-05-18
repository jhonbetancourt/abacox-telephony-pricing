package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Service
@Log4j2
public class TelephonyTypeLookupService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Long getInternalOperatorId(Long telephonyTypeId, Long originCountryId) {
        // PHP's operador_interno
        String queryStr = "SELECT o.id FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId " +
                "AND o.active = true AND p.active = true " +
                "LIMIT 1"; // Assuming one "internal" operator per type/country
        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return ((Number) query.getSingleResult()).longValue();
        } catch (NoResultException e) {
            return 0L; // Default or "unknown" operator
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal getVatForPrefix(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        // Used by TrunkRule logic to get VAT for the *new* type/operator
        String queryStr = "SELECT p.vat_value FROM prefix p " +
                "WHERE p.active = true AND p.telephone_type_id = :telephonyTypeId AND p.operator_id = :operatorId " +
                "AND EXISTS (SELECT 1 FROM operator o WHERE o.id = p.operator_id AND o.origin_country_id = :originCountryId AND o.active = true) " +
                "LIMIT 1";
        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("operatorId", operatorId);
        query.setParameter("originCountryId", originCountryId);
        try {
            return (BigDecimal) query.getSingleResult();
        } catch (NoResultException e) {
            return BigDecimal.ZERO;
        }
    }


    @Transactional(readOnly = true)
    public TariffValue getBaseTariffValue(Long prefixId, Long destinationIndicatorId,
                                                                   Long commLocationId, Long originIndicatorIdForBand) {
        // This is the core of PHP's buscarValor
        String prefixQueryStr = "SELECT p.base_value, p.vat_included, p.vat_value, p.band_ok, p.id as prefix_id, p.telephone_type_id " +
                "FROM prefix p WHERE p.id = :prefixId AND p.active = true";
        jakarta.persistence.Query prefixQuery = entityManager.createNativeQuery(prefixQueryStr, Tuple.class);
        prefixQuery.setParameter("prefixId", prefixId);

        try {
            Tuple pRes = (Tuple) prefixQuery.getSingleResult();
            BigDecimal baseValue = pRes.get("base_value", BigDecimal.class);
            boolean vatIncluded = pRes.get("vat_included", Boolean.class);
            BigDecimal vatValue = pRes.get("vat_value", BigDecimal.class);
            boolean bandOk = pRes.get("band_ok", Boolean.class);
            Long telephonyTypeId = pRes.get("telephone_type_id", Number.class).longValue();


            if (bandOk && (destinationIndicatorId != null && destinationIndicatorId > 0 || isLocalType(telephonyTypeId))) {
                String bandQueryStr = "SELECT b.value as band_value, b.vat_included as band_vat_included " +
                        "FROM band b ";
                if (!isLocalType(telephonyTypeId)) { // Non-local requires band_indicator join
                    bandQueryStr += "JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :destinationIndicatorId ";
                }
                bandQueryStr += "WHERE b.active = true AND b.prefix_id = :prefixId " +
                        "AND (b.origin_indicator_id = 0 OR b.origin_indicator_id = :originIndicatorIdForBand) ";
                bandQueryStr += "ORDER BY b.origin_indicator_id DESC LIMIT 1";

                jakarta.persistence.Query bandQuery = entityManager.createNativeQuery(bandQueryStr, Tuple.class);
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
                            vatValue // VAT rate is from prefix table
                    );
                }
            }
            return new TariffValue(baseValue, vatIncluded, vatValue);

        } catch (NoResultException e) {
            log.warn("No prefix found for ID: {}", prefixId);
            return new TariffValue(BigDecimal.ZERO, false, BigDecimal.ZERO);
        }
    }

    private boolean isLocalType(Long telephonyTypeId) {
        // PHP's _esLocal
        return telephonyTypeId != null &&
                (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) ||
                        telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
    }

    @Transactional(readOnly = true)
    public OperatorInfo getInternalOperatorInfo(Long telephonyTypeId, Long originCountryId) {
        // PHP's operador_interno
        String queryStr = "SELECT o.id, o.name FROM operator o JOIN prefix p ON p.operator_id = o.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId AND o.origin_country_id = :originCountryId " +
                "AND o.active = true AND p.active = true " +
                "LIMIT 1";
        jakarta.persistence.Query query = entityManager.createNativeQuery(queryStr, Tuple.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Tuple result = (Tuple) query.getSingleResult();
            return new OperatorInfo(result.get("id", Number.class).longValue(), result.get("name", String.class));
        } catch (NoResultException e) {
            return new OperatorInfo(0L, "UnknownInternal"); // Default
        }
    }

    @Transactional(readOnly = true)
    public List<Long> getInternalTelephonyTypeIds() {
        // PHP's _tipotele_Internas
        // This could be dynamic based on CallCategory or a flag on TelephonyType
        // For now, using the hardcoded list from AppConfigurationService via enum
        return Arrays.asList(
                TelephonyTypeEnum.INTERNAL_IP.getValue(),
                TelephonyTypeEnum.LOCAL_IP.getValue(),
                TelephonyTypeEnum.NATIONAL_IP.getValue(),
                TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()
        );
    }
}
