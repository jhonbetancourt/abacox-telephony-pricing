// File: com/infomedia/abacox/telephonypricing/cdr/TelephonyTypeLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Log4j2
public class TelephonyTypeLookupService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public String getTelephonyTypeName(Long telephonyTypeId) {
        if (telephonyTypeId == null) return TelephonyTypeEnum.UNKNOWN.getDefaultName();
        try {
            return entityManager.createQuery("SELECT tt.name FROM TelephonyType tt WHERE tt.id = :id AND tt.active = true", String.class)
                    .setParameter("id", telephonyTypeId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return TelephonyTypeEnum.fromId(telephonyTypeId).getDefaultName();
        }
    }

    /**
     * PHP equivalent: Part of buscarDestino when $tipotele_id == _TIPOTELE_LOCAL_EXT
     * and it needs to find the prefix info for LOCAL_EXTENDED.
     */
    @Transactional(readOnly = true)
    public PrefixInfo getPrefixInfoForLocalExtended(Long originCountryId) {
        // PHP: $_lista_Prefijos['local_ext'] and $_lista_Prefijos['datos'][$localext]
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

    /**
     * PHP equivalent: operador_interno
     */
    @Transactional(readOnly = true)
    public OperatorInfo getInternalOperatorInfo(Long telephonyTypeId, Long originCountryId) {
        // PHP: SELECT OPERADOR_ID, OPERADOR_NOMBRE FROM operador, prefijo WHERE PREFIJO_OPERADOR_ID = OPERADOR_ID AND PREFIJO_TIPOTELE_ID = $tipotele_id AND OPERADOR_MPORIGEN_ID = $mporigen_id
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

    /**
     * PHP equivalent: IVA_Troncal (indirectly, by getting prefix VAT)
     */
    @Transactional(readOnly = true)
    public BigDecimal getVatForPrefix(Long telephonyTypeId, Long operatorId, Long originCountryId) {
        // PHP: SELECT PREFIJO_TIPOTELE_ID, PREFIJO_OPERADOR_ID, PREFIJO_IVA FROM prefijo WHERE PREFIJO_TIPOTELE_ID in ($ltipoteles_ok)
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

    /**
     * PHP equivalent: buscarValor (core logic part)
     */
    @Transactional(readOnly = true)
    public TariffValue getBaseTariffValue(Long prefixId, Long destinationIndicatorId,
                                          Long commLocationId, Long originIndicatorIdForBand) {
        // PHP: SELECT PREFIJO_VALORBASE, PREFIJO_IVAINC, PREFIJO_BANDAOK, PREFIJO_IVA FROM prefijo WHERE PREFIJO_ID = $prefijo_id
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
                // PHP: SELECT BANDA_ID, BANDA_VALOR, BANDA_IVAINC, BANDA_NOMBRE $adcampo FROM comubicacion, banda $addtabla WHERE ...
                StringBuilder bandQueryBuilder = new StringBuilder(
                    "SELECT b.value as band_value, b.vat_included as band_vat_included " +
                    "FROM band b ");
                if (!isLocalType(telephonyTypeId)) { // PHP: if (!$es_local)
                    bandQueryBuilder.append("JOIN band_indicator bi ON b.id = bi.band_id AND bi.indicator_id = :destinationIndicatorId ");
                }
                bandQueryBuilder.append("WHERE b.active = true AND b.prefix_id = :prefixId ");
                // PHP: $sql_consulta_comid = "COMUBICACION_ID = $comubicacion_id AND BANDA_INDICAORIGEN_ID in (0, COMUBICACION_INDICATIVO_ID)";
                // PHP: if ($indicativo_origen_id > 0) { $sql_consulta_comid = "BANDA_INDICAORIGEN_ID in (0, $indicativo_origen_id)"; }
                // This means originIndicatorIdForBand is COMUBICACION_INDICATIVO_ID or the explicit $indicativo_origen_id
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
                            vatValue // VAT rate comes from the prefix
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
        // PHP: _esLocal($tipotele_id)
        return telephonyTypeId != null &&
                (telephonyTypeId.equals(TelephonyTypeEnum.LOCAL.getValue()) ||
                        telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
    }

    /**
     * PHP equivalent: TarifasInternas
     */
    @Transactional(readOnly = true)
    public TariffValue getInternalTariffValue(Long internalTelephonyTypeId, Long originCountryId) {
        // PHP: SELECT PREFIJO_TIPOTELE_ID, PREFIJO_VALORBASE, PREFIJO_IVAINC, TIPOTELE_NOMBRE, PREFIJO_IVA FROM PREFIJO, TIPOTELE WHERE PREFIJO_TIPOTELE_ID in ("._tipotele_Internas($link).") AND PREFIJO_TIPOTELE_ID = TIPOTELE_ID
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
        // PHP: _tipotele_Internas($link) includes these IP types
        if (telephonyTypeId == null) return false;
        return telephonyTypeId.equals(TelephonyTypeEnum.INTERNAL_SIMPLE.getValue()) || // PHP: _TIPOTELE_INTERNA_IP
                telephonyTypeId.equals(TelephonyTypeEnum.LOCAL_IP.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.NATIONAL_IP.getValue()) ||
                telephonyTypeId.equals(TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue()); // PHP: _TIPOTELE_INTERNAL_IP
    }

    public List<Long> getInternalTypeIds() {
        // PHP: _tipotele_Internas($link)
        return Arrays.asList(
                TelephonyTypeEnum.INTERNAL_SIMPLE.getValue(),
                TelephonyTypeEnum.LOCAL_IP.getValue(),
                TelephonyTypeEnum.NATIONAL_IP.getValue(),
                TelephonyTypeEnum.INTERNAL_INTERNATIONAL_IP.getValue()
        );
    }
}