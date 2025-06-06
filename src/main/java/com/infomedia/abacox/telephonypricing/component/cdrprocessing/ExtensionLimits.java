// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/ExtensionLimits.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ExtensionLimits {
    // Final numeric values
    private int minLength = 100;
    private int maxLength = CdrConfigService.MAX_EXTENSION_LENGTH_FOR_INTERNAL_CHECK;
    private List<String> specialFullExtensions = Collections.emptyList();

    // Internal trackers for length calculation
    private transient int minLengthTracker = Integer.MAX_VALUE;
    private transient int maxLengthTracker = 0;

    /**
     * Updates the internal length trackers with new min/max length values from a query.
     * @param min The minimum length found in a query group.
     * @param max The maximum length found in a query group.
     * @return The current object for chaining.
     */
    public ExtensionLimits updateLengths(int min, int max) {
        if (min > 0 && min < this.minLengthTracker) {
            this.minLengthTracker = min;
        }
        if (max > 0 && max > this.maxLengthTracker) {
            this.maxLengthTracker = max;
        }
        return this;
    }

    /**
     * Calculates the final numeric min/max values based on the tracked lengths.
     * This should be called after all length data has been processed.
     */
    public void calculateFinalMinMaxValues() {
        if (maxLengthTracker > 0) {
            this.maxLength = Integer.parseInt("9".repeat(maxLengthTracker));
        }
        if (minLengthTracker > 0 && minLengthTracker != Integer.MAX_VALUE) {
            this.minLength = Integer.parseInt("1" + "0".repeat(Math.max(0, minLengthTracker - 1)));
        }

        // PHP: if ($finalMinVal > $finalMaxVal && $finalMaxVal > 0 ...)
        // This ensures min is not greater than max if both were derived from data.
        if (this.minLength > this.maxLength && this.maxLength > 0 && this.minLengthTracker > 0 && this.maxLengthTracker > 0) {
            this.minLength = this.maxLength;
        }
    }
}