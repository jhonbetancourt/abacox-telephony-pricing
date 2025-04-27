package com.infomedia.abacox.telephonypricing.cdr;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Log4j2
@RequiredArgsConstructor
public class TelephonyPricingServiceImpl implements TelephonyPricingService {

    private final LookupService lookupService;
    private final TPConfigService configService;
    
    // Cache for PBX special rules - now cached in lookupService
    private Map<String, Map<Long, LookupService.SpecialServiceInfo>> servEspeciales = null;
    
    @Override
    public CallDestinationInfo evaluateDestination(Object link, String destinationNumber, String trunkLine,
                                                 Integer duration, LocationInfo locationInfo,
                                                 boolean forWebQuery, boolean pbxSpecial) {
        
        log.debug("Evaluating destination: {}, trunkLine: {}, duration: {}", destinationNumber, trunkLine, duration);
        
        if (locationInfo == null || locationInfo.getComubicacionId() == null || locationInfo.getComubicacionId() <= 0) {
            return createInvalidDestinationInfo(destinationNumber, "INVALID LOCATION");
        }
        
        // Check trunk information
        TrunkInfo trunkInfo = null;
        boolean hasTrunk = trunkLine != null && !trunkLine.isEmpty();
        
        if (hasTrunk) {
            Map<String, TrunkInfo> trunks = lookupService.loadTrunks(locationInfo.getComubicacionId());
            if (trunks.containsKey(trunkLine.toUpperCase())) {
                trunkInfo = trunks.get(trunkLine.toUpperCase());
            }
        }
        
        // Get prefix salida PBX
        String prefixSalidaPbx = "";
        if ((trunkInfo == null && (!forWebQuery || pbxSpecial)) || trunkInfo != null) {
            prefixSalidaPbx = locationInfo.getComubicacionPrefijopbx();
        }
        
        // Apply PBX special rule if applicable
        if (pbxSpecial) {
            String directory = locationInfo.getComubicacionDirectorio().toLowerCase();
            String specialNumber = evaluatePbxSpecial(destinationNumber, directory, null, TrunkDirection.BOTH);
            if (specialNumber != null && !specialNumber.isEmpty()) {
                destinationNumber = specialNumber;
            }
        }
        
        // Clean the destination number
        String cleanDestination = cleanNumber(destinationNumber, prefixSalidaPbx, true);
        
        if (cleanDestination.isEmpty() || !isValidPhoneNumber(cleanDestination)) {
            return createInvalidDestinationInfo(destinationNumber, "INVALID PHONE NUMBER");
        }
        
        // Check if it's a special service
        LookupService.SpecialServiceInfo specialService = findSpecialService(cleanDestination, locationInfo.getIndicativoId(), locationInfo.getMporigenId());
        if (specialService != null) {
            return createSpecialServiceInfo(cleanDestination, specialService, locationInfo);
        }
        
        // Process normal call
        return evaluateDestinationPos(trunkInfo, cleanDestination, duration, locationInfo);
    }

    @Override
    public BigDecimal calculateValue(Integer duration, CallDestinationInfo callDestinationInfo) {
        if (callDestinationInfo == null || duration == null || duration <= 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal minuteDuration = calculateMinuteDuration(duration, callDestinationInfo.getInSeconds());
        BigDecimal basePrice = callDestinationInfo.getPricePerMinute();
        BigDecimal vatAmount = callDestinationInfo.getVatAmount();
        
        // Calculate price
        BigDecimal callPrice = basePrice.multiply(minuteDuration);
        
        // Apply VAT if not included
        if (!callDestinationInfo.getPricePerMinuteIncludesVat() && vatAmount != null && vatAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatFactor = BigDecimal.ONE.add(vatAmount.divide(new BigDecimal(100)));
            callPrice = callPrice.multiply(vatFactor);
        }
        
        return callPrice;
    }

    @Override
    public String cleanNumber(String number, String prefixesPbx, boolean safeMode) {
        String cleanedNumber = number.trim();
        
        // If prefixes are defined, try to remove them
        if (prefixesPbx != null && !prefixesPbx.isEmpty()) {
            String[] prefixes = prefixesPbx.split(",");
            int maxCharsToExtract = -1;
            
            // Find the longest matching prefix
            for (String prefix : prefixes) {
                prefix = prefix.trim();
                if (!prefix.isEmpty()) {
                    int prefixLength = prefix.length();
                    if (cleanedNumber.length() >= prefixLength && 
                        cleanedNumber.substring(0, prefixLength).equals(prefix)) {
                        maxCharsToExtract = prefixLength;
                        break;
                    }
                }
            }
            
            // Apply prefix removal
            if (maxCharsToExtract > 0) {
                cleanedNumber = cleanedNumber.substring(maxCharsToExtract);
            } else if (maxCharsToExtract == 0 && !safeMode) {
                // Found prefix but not in number
                cleanedNumber = "";
            }
        }
        
        // Clean the number to keep only valid characters
        if (!cleanedNumber.isEmpty()) {
            // Remove non-numeric characters after the first one
            String firstChar = cleanedNumber.substring(0, 1);
            String rest = cleanedNumber.substring(1);
            
            // Clean rest of the string
            if (!rest.isEmpty() && !isNumeric(rest)) {
                rest = rest.replaceAll("[^0-9]", "");
            }
            
            cleanedNumber = firstChar + rest;
            
            // Remove "+" at the beginning (Nov/2017)
            if (cleanedNumber.startsWith("+")) {
                cleanedNumber = cleanedNumber.substring(1);
            }
        }
        
        return cleanedNumber;
    }

    @Override
    public String evaluatePbxSpecial(String number, String directory, String client, TrunkDirection direction) {
        if (number == null || number.isEmpty()) {
            return null;
        }
        
        directory = directory != null ? directory.toLowerCase() : "";
        client = client != null ? client.toLowerCase() : "";
        
        Map<String, LookupService.PbxSpecialRuleInfo> pbxSpecialRules = lookupService.loadPbxSpecialRules();
        
        for (LookupService.PbxSpecialRuleInfo rule : pbxSpecialRules.values()) {
            String preOri = rule.getPreOri();
            List<String> preNo = rule.getPreNo();
            String preNvo = rule.getPreNvo();
            String ruleDir = rule.getDir();
            Integer minLen = rule.getMinLen();
            Integer incomingValue = rule.getIncoming();
            String nombre = rule.getNombre();
            
            int lenOri = preOri.length();
            int lenNo = preNo.size();
            
            // Check if the rule is applicable
            boolean control = lenOri > 0 && 
                            (ruleDir.isEmpty() || ruleDir.equals(directory)) && 
                            (incomingValue == direction.getValue() || incomingValue == TrunkDirection.BOTH.getValue() || direction == TrunkDirection.BOTH);
            
            // Check if the number matches the prefix
            if (control) {
                control = number.length() >= lenOri && number.substring(0, lenOri).equals(preOri);
            }
            
            // Check if the number should be ignored
            if (control && lenNo > 0) {
                for (String ignorePrefix : preNo) {
                    int lenIgnore = ignorePrefix.length();
                    if (number.length() >= lenIgnore && number.substring(0, lenIgnore).equals(ignorePrefix)) {
                        control = false;
                        break;
                    }
                }
            }
            
            // Check minimum length
            if (control && minLen > 0) {
                control = number.length() >= minLen;
            }
            
            // Apply the rule if all conditions are met
            if (control) {
                String modifiedNumber = preNvo + number.substring(lenOri);
                log.debug("PBX Special Rule applied: {} -> {} ({})", number, modifiedNumber, nombre);
                return modifiedNumber;
            }
        }
        
        return null;
    }

    // Helper methods
    
    private CallDestinationInfo evaluateDestinationPos(TrunkInfo trunkInfo, String destinationNumber, 
                                                    Integer duration, LocationInfo locationInfo) {
        Long mporigenId = locationInfo.getMporigenId();
        Long indicativoOrigenId = locationInfo.getIndicativoId();
        
        // Get prefixes information
        PrefixInfo prefixInfo = lookupService.loadPrefixes(mporigenId);
        
        // Find matching prefixes for this number
        List<Map.Entry<String, PrefixInfo.PrefixData>> matchingPrefixes = findMatchingPrefixes(destinationNumber, trunkInfo, prefixInfo);
        
        if (matchingPrefixes.isEmpty()) {
            return createInvalidDestinationInfo(destinationNumber, "NO MATCHING PREFIX");
        }
        
        // Try to find destination for each prefix
        LookupService.IndicatorInfo destinationInfo = null;
        PrefixInfo.PrefixData matchedPrefix = null;
        Long prefixId = 0L;
        
        for (Map.Entry<String, PrefixInfo.PrefixData> entry : matchingPrefixes) {
            String prefix = entry.getKey();
            PrefixInfo.PrefixData prefixData = entry.getValue();
            
            // Get all prefixIds for this prefix
            List<Long> prefixIds = prefixInfo.getPrefixMap().get(prefix);
            
            // For each prefixId, try to find destination
            for (Long pId : prefixIds) {
                PrefixInfo.PrefixData data = prefixInfo.getDataMap().get(pId);
                
                if (data == null) continue;
                
                // Check if we should reduce the number or not
                boolean reducir = false;
                if (trunkInfo != null) {
                    // Find the trunk operator destination
                    Long operatorId = data.getOperatorId();
                    Long telephonyTypeId = data.getTelephonyTypeId();
                    
                    // Check if this trunk has rates for this telephony type
                    if (trunkInfo.getOperatorDestinationTypes().containsKey(telephonyTypeId)) {
                        List<Long> operatorIds = trunkInfo.getOperatorDestinationTypes().get(telephonyTypeId);
                        if (operatorIds.contains(operatorId) || operatorIds.contains(0L)) {
                            // Find which operatorId to use
                            Long operatorTroncal = -1L;
                            
                            for (Long opId : new Long[]{operatorId, 0L}) {
                                if (operatorIds.contains(opId) && 
                                    trunkInfo.getOperatorDestination().containsKey(opId) &&
                                    trunkInfo.getOperatorDestination().get(opId).containsKey(telephonyTypeId)) {
                                    
                                    operatorTroncal = opId;
                                    break;
                                }
                            }
                            
                            if (operatorTroncal >= 0) {
                                Map<Long, TrunkInfo.TrunkOperatorDestination> destMap = 
                                    trunkInfo.getOperatorDestination().get(operatorTroncal).get(telephonyTypeId);
                                
                                if (destMap.containsKey(0L)) {
                                    reducir = destMap.get(0L).isNoPrefix();
                                }
                            }
                        }
                    }
                }
                
                // Try to find destination
                LookupService.IndicatorInfo foundDestination = lookupService.findDestination(
                    destinationNumber,
                    data.getTelephonyTypeId(),
                    data.getTelephonyTypeMin(),
                    indicativoOrigenId,
                    prefix,
                    pId,
                    reducir,
                    mporigenId,
                    data.getBandsOk()
                );
                
                if (foundDestination != null) {
                    destinationInfo = foundDestination;
                    matchedPrefix = data;
                    prefixId = pId;
                    break;
                }
            }
            
            if (destinationInfo != null) {
                break;
            }
        }
        
        // If no destination found, but we have a single telephony type, assume it
        if (destinationInfo == null && matchingPrefixes.size() == 1) {
            PrefixInfo.PrefixData onlyPrefix = matchingPrefixes.get(0).getValue();
            
            if (destinationNumber.length() == onlyPrefix.getTelephonyTypeMax()) {
                // Create an assumed destination
                matchedPrefix = onlyPrefix;
                prefixId = prefixInfo.getTelephonyTypeMap().get(onlyPrefix.getTelephonyTypeId()).get(0);
                
                String destination = onlyPrefix.getTelephonyTypeName() + " (" + configService.getAsumido() + ")";
                
                destinationInfo = LookupService.IndicatorInfo.builder()
                    .indicatorId(0L)
                    .indicative("")
                    .destination(destination)
                    .build();
            }
        }
        
        // If still no destination found, handle error case
        if (destinationInfo == null || matchedPrefix == null) {
            return createInvalidDestinationInfo(destinationNumber, "DESTINATION NOT FOUND");
        }
        
        // Check if it's extended local call
        if (matchedPrefix.getTelephonyTypeId().equals(configService.getTipoteleLocal()) &&
            lookupService.isLocalExtended(destinationInfo.getIndicative(), indicativoOrigenId, destinationInfo.getIndicatorId())) {
            matchedPrefix = prefixInfo.getDataMap().get(prefixInfo.getLocalExtId());
        }
        
        // Find value for this destination
        LookupService.CallValueInfo valueInfo = lookupService.findValue(
            matchedPrefix.getTelephonyTypeId(),
            prefixId,
            destinationInfo.getIndicatorId(),
            locationInfo.getComubicacionId(),
            indicativoOrigenId
        );
        
        if (valueInfo == null) {
            valueInfo = LookupService.CallValueInfo.builder()
                .pricePerMinute(BigDecimal.ZERO)
                .pricePerMinuteIncludesVat(false)
                .vatAmount(BigDecimal.ZERO)
                .useBands(false)
                .bandId(0L)
                .bandName("")
                .build();
        }
        
        // Create the destination info object
        CallDestinationInfo resultInfo = CallDestinationInfo.builder()
            .telephone(destinationNumber)
            .operatorId(matchedPrefix.getOperatorId())
            .operatorName(matchedPrefix.getOperatorName())
            .indicatorId(destinationInfo.getIndicatorId())
            .indicatorCode(destinationInfo.getIndicative())
            .telephonyTypeId(matchedPrefix.getTelephonyTypeId())
            .telephonyTypeName(matchedPrefix.getTelephonyTypeName())
            .destination(destinationInfo.getDestination())
            .useTrunk(trunkInfo != null)
            .pricePerMinute(valueInfo.getPricePerMinute())
            .pricePerMinuteIncludesVat(valueInfo.isPricePerMinuteIncludesVat())
            .vatAmount(valueInfo.getVatAmount())
            .inSeconds(false)
            .useBands(valueInfo.isUseBands())
            .bandId(valueInfo.getBandId())
            .bandName(valueInfo.getBandName())
            .build();
        
        // Apply trunk-specific pricing if available
        if (trunkInfo != null) {
            // Check if we need to normalize for cell-fixed trunks
            if (trunkInfo.isCellFixed() && matchedPrefix.getTelephonyTypeId().equals(configService.getTipoteleCelular())) {
                resultInfo.setTelephonyTypeId(configService.getTipoteleCelufijo());
                resultInfo.setTelephonyTypeName("Celufijo (xTroncal " + trunkInfo.getDescription() + ")");
            } else {
                resultInfo.setTelephonyTypeName(resultInfo.getTelephonyTypeName() + " (xTroncal " + trunkInfo.getDescription() + ")");
            }
            
            // Look up trunk rates
            if (trunkInfo.getOperatorDestinationTypes().containsKey(resultInfo.getTelephonyTypeId())) {
                List<Long> operatorIds = trunkInfo.getOperatorDestinationTypes().get(resultInfo.getTelephonyTypeId());
                
                // Check for specific operator or default (0)
                Long operatorTroncal = -1L;
                for (Long opId : Arrays.asList(resultInfo.getOperatorId(), 0L)) {
                    if (operatorIds.contains(opId)) {
                        operatorTroncal = opId;
                        break;
                    }
                }
                
                if (operatorTroncal >= 0) {
                    Map<Long, TrunkInfo.TrunkOperatorDestination> destMap = 
                        trunkInfo.getOperatorDestination().get(operatorTroncal).get(resultInfo.getTelephonyTypeId());
                    
                    if (destMap.containsKey(0L)) {
                        TrunkInfo.TrunkOperatorDestination dest = destMap.get(0L);
                        
                        // Store original values
                        BigDecimal initialPrice = resultInfo.getPricePerMinute();
                        Boolean initialPriceIncludesVat = resultInfo.getPricePerMinuteIncludesVat();
                        
                        // Update with trunk values
                        resultInfo.setInitialPrice(initialPrice);
                        resultInfo.setInitialPriceIncludesVat(initialPriceIncludesVat);
                        resultInfo.setPricePerMinute(dest.getPricePerMinute());
                        resultInfo.setPricePerMinuteIncludesVat(dest.isPricePerMinuteIncludesVat());
                        resultInfo.setInSeconds(dest.isInSeconds());
                    }
                }
            }
        }
        
        // Calculate billed amount
        if (duration != null && duration > 0) {
            BigDecimal billedAmount = calculateValue(duration, resultInfo);
            resultInfo.setBilledAmount(billedAmount);
            
            // Apply special rates if applicable
            LocalDateTime now = LocalDateTime.now();
            LookupService.CallValueInfo specialValue = lookupService.findSpecialValue(now, duration, indicativoOrigenId, resultInfo);
            
            if (specialValue != null) {
                // Store original values if not already stored
                if (resultInfo.getInitialPrice() == null) {
                    resultInfo.setInitialPrice(resultInfo.getPricePerMinute());
                    resultInfo.setInitialPriceIncludesVat(resultInfo.getPricePerMinuteIncludesVat());
                }
                
                // Apply special value
                resultInfo.setPricePerMinute(specialValue.getPricePerMinute());
                resultInfo.setPricePerMinuteIncludesVat(specialValue.isPricePerMinuteIncludesVat());
                resultInfo.setVatAmount(specialValue.getVatAmount());
                resultInfo.setTelephonyTypeName(resultInfo.getTelephonyTypeName() + " (xTarifaEsp)");
                
                // Recalculate billed amount
                billedAmount = calculateValue(duration, resultInfo);
                resultInfo.setBilledAmount(billedAmount);
            }
            
            // Apply trunk rules if applicable
            if (trunkInfo != null) {
                CallDestinationInfo ruleInfo = lookupService.calculateRuleValue(
                    trunkInfo.getDescription(), 
                    duration, 
                    billedAmount, 
                    locationInfo, 
                    resultInfo
                );
                
                if (ruleInfo != null) {
                    resultInfo = ruleInfo;
                }
            }
        }
        
        return resultInfo;
    }
    
    private List<Map.Entry<String, PrefixInfo.PrefixData>> findMatchingPrefixes(String number, TrunkInfo trunkInfo, PrefixInfo prefixInfo) {
        List<Map.Entry<String, PrefixInfo.PrefixData>> matchingPrefixes = new ArrayList<>();
        
        // If we have a trunk with specific telephony types
        if (trunkInfo != null && !trunkInfo.getOperatorDestinationTypes().isEmpty()) {
            // For each telephony type defined for this trunk
            for (Long telephonyTypeId : trunkInfo.getOperatorDestinationTypes().keySet()) {
                if (prefixInfo.getTelephonyTypeMap().containsKey(telephonyTypeId)) {
                    List<Long> prefixIds = prefixInfo.getTelephonyTypeMap().get(telephonyTypeId);
                    
                    for (Long prefixId : prefixIds) {
                        PrefixInfo.PrefixData data = prefixInfo.getDataMap().get(prefixId);
                        String prefix = data.getPrefix();
                        
                        if (prefix == null || prefix.isEmpty() || 
                            (number.length() >= prefix.length() && number.startsWith(prefix))) {
                            matchingPrefixes.add(new AbstractMap.SimpleEntry<>(prefix, data));
                        }
                    }
                }
            }
        } else {
            // No trunk or trunk without specific types, check all prefixes
            for (Map.Entry<String, List<Long>> entry : prefixInfo.getPrefixMap().entrySet()) {
                String prefix = entry.getKey();
                
                if (number.length() >= prefix.length() && number.startsWith(prefix)) {
                    for (Long prefixId : entry.getValue()) {
                        PrefixInfo.PrefixData data = prefixInfo.getDataMap().get(prefixId);
                        matchingPrefixes.add(new AbstractMap.SimpleEntry<>(prefix, data));
                    }
                }
            }
            
            // Sort by prefix length (descending) to match longest prefix first
            matchingPrefixes.sort((o1, o2) -> o2.getKey().length() - o1.getKey().length());
        }
        
        // If no prefix found but we have a number long enough to be local, check local type
        if (matchingPrefixes.isEmpty() && prefixInfo.getLocalId() > 0) {
            PrefixInfo.PrefixData localData = prefixInfo.getDataMap().get(prefixInfo.getLocalId());
            if (localData != null && number.length() >= localData.getTelephonyTypeMin()) {
                matchingPrefixes.add(new AbstractMap.SimpleEntry<>("", localData));
            }
        }
        
        return matchingPrefixes;
    }
    
    private LookupService.SpecialServiceInfo findSpecialService(String number, Long indicativoId, Long mporigenId) {
        // Load special services if not already loaded
        if (servEspeciales == null) {
            servEspeciales = lookupService.loadSpecialServices(indicativoId, mporigenId);
        }
        
        if (servEspeciales.containsKey(number)) {
            Map<Long, LookupService.SpecialServiceInfo> services = servEspeciales.get(number);
            
            // First check if there's a service for this specific indicator
            if (services.containsKey(indicativoId)) {
                return services.get(indicativoId);
            }
            
            // If not, use the default (0)
            if (services.containsKey(0L)) {
                return services.get(0L);
            }
        }
        
        return null;
    }
    
    private CallDestinationInfo createInvalidDestinationInfo(String number, String reason) {
        return CallDestinationInfo.builder()
            .telephone(number)
            .operatorId(0L)
            .indicatorId(0L)
            .telephonyTypeId(0L)
            .telephonyTypeName(reason)
            .pricePerMinute(BigDecimal.ZERO)
            .build();
    }
    
    private CallDestinationInfo createSpecialServiceInfo(String number, LookupService.SpecialServiceInfo specialService, LocationInfo locationInfo) {
        // For special services, get the operator that handles them
        Long operatorId = getSpecialServicesOperator(locationInfo);
        String operatorName = getSpecialServicesOperatorName(locationInfo);
        
        return CallDestinationInfo.builder()
            .telephone(number)
            .operatorId(operatorId)
            .operatorName(operatorName)
            .indicatorId(0L)
            .telephonyTypeId(configService.getTipoteleEspeciales())
            .telephonyTypeName("SERVICIO ESPECIAL")
            .destination(specialService.getDestination())
            .pricePerMinute(specialService.getPricePerMinute())
            .pricePerMinuteIncludesVat(specialService.isPricePerMinuteIncludesVat())
            .vatAmount(specialService.getVatAmount())
            .build();
    }
    
    private Long getSpecialServicesOperator(LocationInfo locationInfo) {
        // In a real implementation, this would query the database to find the operator
        // for special services based on the location
        return 1L; // Default operator ID
    }
    
    private String getSpecialServicesOperatorName(LocationInfo locationInfo) {
        // In a real implementation, this would look up the operator name
        return "Servicios Especiales"; // Default operator name
    }
    
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
    
    private boolean isValidPhoneNumber(String number) {
        // Check if first character is numeric
        if (number.isEmpty() || !Character.isDigit(number.charAt(0))) {
            return false;
        }
        
        // For "anonymous" numbers (special case)
        if (number.toUpperCase().equals("ANONYMOUS")) {
            return true;
        }
        
        // Check if the number contains only valid characters (numeric, #, *, +)
        return number.matches("^[0-9#*+]+$");
    }
    
    private boolean isNumeric(String str) {
        return Pattern.matches("^[0-9]+$", str);
    }
}