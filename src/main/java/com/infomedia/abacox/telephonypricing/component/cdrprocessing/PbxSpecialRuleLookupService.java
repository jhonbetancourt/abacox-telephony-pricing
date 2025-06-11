package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.PbxSpecialRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Log4j2
public class PbxSpecialRuleLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<String> applyPbxSpecialRule(String dialedNumber, String commDirectory, int callDirectionType) {
        // PHP's evaluarPBXEspecial
        // The clientBdName part is tricky as it's not directly in PbxSpecialRule.
        // Assuming comm_location_id on PbxSpecialRule links to a CommunicationLocation which then links to a Client.
        // For now, we'll use commDirectory if PbxSpecialRule.comm_location_id is set.

        String queryStr = "SELECT p.* FROM pbx_special_rule p " +
                          "LEFT JOIN communication_location cl ON p.comm_location_id = cl.id " +
                          "WHERE p.active = true " +
                          "AND (p.comm_location_id IS NULL OR cl.directory = :commDirectory) " + // Applies to all or specific directory
                          "AND (p.direction = 0 OR p.direction = :callDirectionType) " + // 0=both, 1=in, 2=out, 3=internal
                          "ORDER BY p.comm_location_id DESC NULLS LAST, LENGTH(p.search_pattern) DESC"; // Prefer specific, then longer patterns

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, PbxSpecialRule.class);
        nativeQuery.setParameter("commDirectory", commDirectory);
        nativeQuery.setParameter("callDirectionType", callDirectionType);

        List<PbxSpecialRule> rules = nativeQuery.getResultList();

        for (PbxSpecialRule rule : rules) {
            if (dialedNumber.startsWith(rule.getSearchPattern())) {
                if (rule.getMinLength() != null && dialedNumber.length() < rule.getMinLength()) {
                    continue;
                }
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatterns = rule.getIgnorePattern().split(",");
                    for (String ignorePat : ignorePatterns) {
                        if (dialedNumber.startsWith(ignorePat.trim())) {
                            ignore = true;
                            break;
                        }
                    }
                }
                if (!ignore) {
                    String transformedNumber = rule.getReplacement() + dialedNumber.substring(rule.getSearchPattern().length());
                    log.debug("Applied PBX rule '{}': {} -> {}", rule.getName(), dialedNumber, transformedNumber);
                    return Optional.of(transformedNumber);
                }
            }
        }
        return Optional.empty();
    }
}