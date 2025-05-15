package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Operator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@Transactional(readOnly = true)
public class ConfigurationLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public ConfigurationLookupService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<String> findPbxPrefixByCommLocationId(Long commLocationId) {
        if (commLocationId == null) return Optional.empty();
        log.debug("Finding PBX prefix for commLocationId: {}", commLocationId);
        String sql = "SELECT pbx_prefix FROM communication_location WHERE id = :id AND active = true";
        Query query = entityManager.createNativeQuery(sql, String.class);
        query.setParameter("id", commLocationId);
        try {
            return Optional.ofNullable((String) query.getSingleResult());
        } catch (NoResultException e) {
            log.trace("No active CommunicationLocation found for ID: {}", commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding PBX prefix for commLocationId: {}", commLocationId, e);
            return Optional.empty();
        }
    }

    public Map<String, Integer> findTelephonyTypeMinMaxConfig(Long telephonyTypeId, Long originCountryId) {
        Map<String, Integer> config = new HashMap<>();
        config.put("min", 0); config.put("max", 0);
        if (telephonyTypeId == null || originCountryId == null) return config;

        log.debug("Finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT min_value, max_value FROM telephony_type_config " +
                "WHERE telephony_type_id = :telephonyTypeId AND origin_country_id = :originCountryId AND active = true " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            config.put("min", result[0] != null ? ((Number) result[0]).intValue() : 0);
            config.put("max", result[1] != null ? ((Number) result[1]).intValue() : 0);
        } catch (NoResultException e) {
            log.trace("No active TelephonyTypeConfig found for type {}, country {}. Using defaults 0,0.", telephonyTypeId, originCountryId);
        } catch (Exception e) {
            log.error("Error finding min/max config for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
        }
        return config;
    }

    public Optional<Operator> findOperatorByTelephonyTypeAndOrigin(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return Optional.empty();
        log.debug("Finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId);
        String sql = "SELECT op.* FROM operator op " +
                "JOIN prefix p ON p.operator_id = op.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND op.origin_country_id = :originCountryId " +
                "  AND op.active = true " +
                "  AND p.active = true " +
                "ORDER BY op.id ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql, Operator.class);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        query.setParameter("originCountryId", originCountryId);
        try {
            Operator operator = (Operator) query.getSingleResult();
            return Optional.of(operator);
        } catch (NoResultException e) {
            log.trace("No active operator found linked to telephony type {} for origin {}", telephonyTypeId, originCountryId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding operator for telephonyTypeId: {}, originCountryId: {}", telephonyTypeId, originCountryId, e);
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> findInternalTariff(Long telephonyTypeId) {
        if (telephonyTypeId == null) return Optional.empty();
        log.debug("Finding internal tariff for telephonyTypeId: {}", telephonyTypeId);
        String sql = "SELECT p.base_value, p.vat_included, p.vat_value, tt.name as telephony_type_name " +
                "FROM prefix p " +
                "JOIN telephony_type tt ON p.telephone_type_id = tt.id " +
                "WHERE p.telephone_type_id = :telephonyTypeId " +
                "  AND p.active = true AND tt.active = true " +
                "ORDER BY p.id ASC " +
                "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("telephonyTypeId", telephonyTypeId);
        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("valor_minuto", result[0] != null ? result[0] : BigDecimal.ZERO);
            map.put("valor_minuto_iva", result[1] != null ? result[1] : false);
            map.put("iva", result[2] != null ? result[2] : BigDecimal.ZERO);
            map.put("tipotele_nombre", result[3]);
            map.put("ensegundos", false);
            map.put("valor_inicial", BigDecimal.ZERO);
            map.put("valor_inicial_iva", false);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.warn("No active prefix found defining internal tariff for telephonyTypeId: {}", telephonyTypeId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding internal tariff for telephonyTypeId: {}", telephonyTypeId, e);
            return Optional.empty();
        }
    }
}