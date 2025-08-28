// File: com/infomedia/abacox/telephonypricing/cdr/DestinationInfo.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.math.BigInteger;

@AllArgsConstructor
@Data
@NoArgsConstructor
@Log4j2
public class DestinationInfo {
    public String matchedPhoneNumber; // The phone number part that was used for matching against series (NDC + subscriber)
    public Long indicatorId;
    public String ndc; // The NDC part from the database series record
    public String destinationDescription;
    public Long operatorId; // Operator ID from the Indicator record
    public Long prefixId; // Prefix ID from the calling function (PrefixInfo.prefixId)
    public Long bandId;
    public boolean isApproximateMatch;

    // Store the original series values for reference
    public Integer seriesInitial;
    public Integer seriesFinal;

    // Store the fully constructed and padded comparable values
    // These will be like "NDC" + "padded_series_initial_subscriber_part"
    private String comparableInitialValue;
    private String comparableFinalValue;

    public long getPaddedSeriesRangeSize() {
        if (comparableInitialValue == null || comparableFinalValue == null) {
            return Long.MAX_VALUE;
        }
        try {
            // Use BigInteger for safety with potentially long phone numbers
            // Ensure strings are purely numeric before BigInteger conversion
            if (!comparableInitialValue.matches("\\d*") || !comparableFinalValue.matches("\\d*")) {
                log.debug("Non-numeric comparableInitialValue ('{}') or comparableFinalValue ('{}') for range size calculation.", comparableInitialValue, comparableFinalValue);
                // Handle cases where NDC might be non-numeric (e.g. negative for approximate)
                // or padding resulted in non-numeric, though it shouldn't.
                // If they are not numeric, a simple length comparison or default max might be better.
                // For now, if non-numeric, assume largest range to push it down in sort.
                if (comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return 0; // Empty range
                return Long.MAX_VALUE;
            }
            if (comparableInitialValue.isEmpty() && !comparableFinalValue.isEmpty()) return Long.MAX_VALUE;
            if (!comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return Long.MAX_VALUE;
            if (comparableInitialValue.isEmpty() && comparableFinalValue.isEmpty()) return 0;


            BigInteger initial = new BigInteger(comparableInitialValue);
            BigInteger finalVal = new BigInteger(comparableFinalValue);
            return finalVal.subtract(initial).longValueExact();
        } catch (NumberFormatException e) {
            log.debug("NumberFormatException during getPaddedSeriesRangeSize for initial='{}', final='{}'. Returning MAX_VALUE.", comparableInitialValue, comparableFinalValue, e);
            return Long.MAX_VALUE;
        } catch (ArithmeticException e) {
            log.debug("ArithmeticException (likely longValueExact overflow) during getPaddedSeriesRangeSize for initial='{}', final='{}'. Returning MAX_VALUE.", comparableInitialValue, comparableFinalValue, e);
            return Long.MAX_VALUE;
        }
    }
}