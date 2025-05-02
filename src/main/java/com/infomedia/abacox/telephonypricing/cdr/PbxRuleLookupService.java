// FILE: lookup/PbxRuleLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
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

    @Cacheable(value = "pbxSpecialRuleLookup", key = "{#dialedNumber, #commLocationId, #direction}")
    public Optional<PbxSpecialRule> findPbxSpecialRule(String dialedNumber, Long commLocationId, int direction) {
        if (!StringUtils.hasText(dialedNumber) || commLocationId == null) {
            log.trace("findPbxSpecialRule - Invalid input: dialedNumber={}, commLocationId={}", dialedNumber, commLocationId);
            return Optional.empty();
        }
        log.debug("Finding PBX special rule for number: {}, commLocationId: {}, direction: {}", dialedNumber, commLocationId, direction);

        List<PbxSpecialRule> candidates = findPbxSpecialRuleCandidates(commLocationId, direction);

        for (PbxSpecialRule rule : candidates) {
            boolean match = false;
            String searchPattern = rule.getSearchPattern();

            if (StringUtils.hasText(searchPattern) && dialedNumber.startsWith(searchPattern)) {
                match = true;
                String ignorePatternString = rule.getIgnorePattern();
                if (match && StringUtils.hasText(ignorePatternString)) {
                    String[] ignorePatterns = ignorePatternString.split(",");
                    for (String ignore : ignorePatterns) {
                        String trimmedIgnore = ignore.trim();
                        if (!trimmedIgnore.isEmpty() && dialedNumber.startsWith(trimmedIgnore)) {
                            match = false;
                            log.trace("Rule {} ignored for number {} due to ignore pattern '{}'", rule.getId(), dialedNumber, trimmedIgnore);
                            break;
                        }
                    }
                }
                if (match && rule.getMinLength() != null && dialedNumber.length() < rule.getMinLength()) {
                    match = false;
                    log.trace("Rule {} ignored for number {} due to minLength ({} < {})", rule.getId(), dialedNumber, dialedNumber.length(), rule.getMinLength());
                }
            }

            if (match) {
                log.trace("Found matching PBX special rule {} for number {}", rule.getId(), dialedNumber);
                return Optional.of(rule);
            }
        }

        log.trace("No matching PBX special rule found for number {}", dialedNumber);
        return Optional.empty();
    }

    @Cacheable(value = "pbxSpecialRuleCandidates", key = "{#commLocationId, #direction}")
    public List<PbxSpecialRule> findPbxSpecialRuleCandidates(Long commLocationId, int direction) {
        if (commLocationId == null) return Collections.emptyList();
        log.debug("Finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction);
        String sql = "SELECT p.* FROM pbx_special_rule p " +
                "WHERE p.active = true " +
                "  AND (p.comm_location_id = :commLocationId OR p.comm_location_id IS NULL) " +
                "  AND p.direction IN (0, :direction) " +
                "ORDER BY p.comm_location_id DESC NULLS LAST, LENGTH(p.search_pattern) DESC";
        Query query = entityManager.createNativeQuery(sql, PbxSpecialRule.class);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("direction", direction);
        try {
            return query.getResultList();
        } catch (Exception e) {
            log.error("Error finding PBX special rule candidates for commLocationId: {}, direction: {}", commLocationId, direction, e);
            return Collections.emptyList();
        }
    }
}