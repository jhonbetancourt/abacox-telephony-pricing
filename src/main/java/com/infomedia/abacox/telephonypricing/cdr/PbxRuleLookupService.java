// FILE: lookup/PbxRuleLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
// import org.springframework.cache.annotation.Cacheable; // Consider caching
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PbxRuleLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Finds the best matching PBX special rule based on the PHP logic.
     *
     * @param dialedNumber   The number dialed (potentially including PBX prefix).
     * @param commLocationId The ID of the communication location.
     * @param direction      The call direction (0=both, 1=incoming, 2=outgoing).
     * @return Optional containing the best matching rule.
     */
    public Optional<PbxSpecialRule> findPbxSpecialRule(String dialedNumber, Long commLocationId, int direction) {
        if (!StringUtils.hasText(dialedNumber) || commLocationId == null) {
            log.trace("findPbxSpecialRule - Invalid input: dialedNumber={}, commLocationId={}", dialedNumber, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding PBX special rule for number: {}, commLocationId: {}, direction: {}", dialedNumber, commLocationId, direction);

        // Fetch candidates ordered similarly to PHP's implicit logic (specific location first, then longest search pattern)
        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            String searchPattern = rule.getSearchPattern();
            int minLength = rule.getMinLength() != null ? rule.getMinLength() : 0;

            // Check basic match conditions (search pattern and minimum length)
            if (StringUtils.hasText(searchPattern) &&
                dialedNumber.startsWith(searchPattern) &&
                dialedNumber.length() >= minLength)
            {
                // Check ignore patterns
                boolean ignore = false;
                String ignorePatternString = rule.getIgnorePattern();
                if (StringUtils.hasText(ignorePatternString)) {
                    String[] ignorePatterns = ignorePatternString.split(",");
                    for (String ignorePat : ignorePatterns) {
                        String trimmedIgnore = ignorePat.trim();
                        if (!trimmedIgnore.isEmpty() && dialedNumber.startsWith(trimmedIgnore)) {
                            ignore = true;
                            log.trace("Rule {} ignored for number {} due to ignore pattern '{}'", rule.getId(), dialedNumber, trimmedIgnore);
                            break; // Stop checking ignore patterns for this rule
                        }
                    }
                }

                // If not ignored, this is the best match due to the query ordering
                if (!ignore) {
                    log.trace("Found matching PBX special rule {} for number {}", rule.getId(), dialedNumber);
                    return Optional.of(rule);
                }
            }
        }

        log.trace("No matching PBX special rule found for number {}", dialedNumber);
        return Optional.empty();
    }

    /**
     * Fetches potential candidate rules, ordered by specificity.
     * Rules specific to the communication location are prioritized,
     * followed by rules applicable to all locations (NULL comm_location_id).
     * Within those groups, longer search patterns are prioritized.
     *
     * @param commLocationId The communication location ID.
     * @param direction      The call direction (0=both, 1=incoming, 2=outgoing).
     * @return A list of candidate rules, ordered by priority.
     */
    // @Cacheable(value = "pbxRuleCandidates", key = "{#commLocationId, #direction}") // Consider caching
    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.debug("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);

        // SQL query mirroring the PHP logic's effective prioritization:
        // 1. Rules specific to the location (comm_location_id = :commLocationId)
        // 2. Rules applicable to all locations (comm_location_id IS NULL)
        // Within each group, prioritize by:
        // 3. Longer search_pattern (more specific match)
        // The direction filter is applied globally.
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL) " +
                "  AND p.direction IN (0, :direction) " + // 0 = both, 1 = incoming, 2 = outgoing
                "ORDER BY " +
                "  CASE WHEN p.comm_location_id IS NULL THEN 1 ELSE 0 END, " + // Specific location first (0), then NULL (1)
                "  LENGTH(p.search_pattern) DESC"; // Longer search pattern first

        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("direction", direction);
        try {
            List<PbxSpecialRule> rules = query.getResultList();
            log.trace("Found {} candidate PBX rules for location {}, direction {}", rules.size(), commLocationId, direction);
            return rules;
        } catch (Exception e) {
            log.error("Error finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction, e);
            return Collections.emptyList();
        }
    }
}