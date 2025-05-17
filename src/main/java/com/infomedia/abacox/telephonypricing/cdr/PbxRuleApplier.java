package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Log4j2
@RequiredArgsConstructor
public class PbxRuleApplier {

    private final PricingRuleLookupService pricingRuleLookupService;

    public String applyPbxRules(String dialedNumber, CommunicationLocation commLocation, boolean isIncoming) {
        List<PbxSpecialRule> rules = pricingRuleLookupService.findPbxSpecialRules(commLocation.getId());
        String currentNumber = dialedNumber;

        for (PbxSpecialRule rule : rules) {
            PbxSpecialRuleDirection ruleDirection = PbxSpecialRuleDirection.fromValue(rule.getDirection());
            boolean directionMatch = (ruleDirection == PbxSpecialRuleDirection.BOTH) ||
                    (isIncoming && ruleDirection == PbxSpecialRuleDirection.INCOMING) ||
                    (!isIncoming && ruleDirection == PbxSpecialRuleDirection.OUTGOING);

            if (!directionMatch) continue;

            if (currentNumber == null || currentNumber.length() < rule.getMinLength()) continue;

            Pattern searchPattern;
            try {
                searchPattern = Pattern.compile("^" + Pattern.quote(rule.getSearchPattern()));
            } catch (Exception e) {
                log.warn("Invalid search pattern in PbxSpecialRule ID {}: {}", rule.getId(), rule.getSearchPattern(), e);
                continue;
            }
            Matcher matcher = searchPattern.matcher(currentNumber);

            if (matcher.find()) {
                boolean ignore = false;
                if (rule.getIgnorePattern() != null && !rule.getIgnorePattern().isEmpty()) {
                    String[] ignorePatternsText = rule.getIgnorePattern().split(",");
                    for (String ignorePatStr : ignorePatternsText) {
                        if (ignorePatStr.trim().isEmpty()) continue;
                        try {
                            Pattern ignorePat = Pattern.compile(Pattern.quote(ignorePatStr.trim()));
                            if (ignorePat.matcher(dialedNumber).find()) {
                                ignore = true;
                                break;
                            }
                        } catch (Exception e) {
                            log.warn("Invalid ignore pattern in PbxSpecialRule ID {}: {}", rule.getId(), ignorePatStr.trim(), e);
                        }
                    }
                }
                if (ignore) continue;

                String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
                currentNumber = replacement + currentNumber.substring(matcher.end());
                log.debug("Applied PBX rule ID {}: {} -> {}", rule.getId(), dialedNumber, currentNumber);
                return currentNumber;
            }
        }
        return currentNumber;
    }
}
