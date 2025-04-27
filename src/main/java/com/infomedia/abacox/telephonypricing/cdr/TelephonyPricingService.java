package com.infomedia.abacox.telephonypricing.cdr;


import java.math.BigDecimal;

public interface TelephonyPricingService {
    /**
     * Evaluates a destination phone number to calculate pricing
     * 
     * @param link Not used in this implementation, kept for compatibility
     * @param destinationNumber The destination phone number
     * @param trunkLine The trunk line used (if any)
     * @param duration Call duration in seconds
     * @param locationInfo Location information
     * @param forWebQuery Whether this is a web query or for capture
     * @param pbxSpecial Whether to apply PBX special rules
     * @return Information about the call destination, pricing, etc.
     */
    CallDestinationInfo evaluateDestination(Object link, String destinationNumber, String trunkLine, 
                                           Integer duration, LocationInfo locationInfo, 
                                           boolean forWebQuery, boolean pbxSpecial);
    
    /**
     * Calculates the call value based on duration and pricing information
     * 
     * @param duration Call duration in seconds
     * @param callDestinationInfo Call destination and pricing information
     * @return The calculated call value
     */
    BigDecimal calculateValue(Integer duration, CallDestinationInfo callDestinationInfo);
    
    /**
     * Cleans a phone number by removing non-numeric characters and handling prefixes
     * 
     * @param number The phone number to clean
     * @param prefixesPbx PBX prefixes to remove
     * @param safeMode If true, returns the original number if no prefix is found
     * @return The cleaned phone number
     */
    String cleanNumber(String number, String prefixesPbx, boolean safeMode);
    
    /**
     * Evaluates PBX special rules for a number
     * 
     * @param number The phone number to evaluate
     * @param directory The directory/location
     * @param client The client name
     * @param direction Incoming/outgoing/both
     * @return The modified number if a rule applies, or null
     */
    String evaluatePbxSpecial(String number, String directory, String client, TrunkDirection direction);
}