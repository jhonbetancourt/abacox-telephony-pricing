package com.infomedia.abacox.telephonypricing.model.report;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Enum representing the different grouping types for the Unassigned Call
 * Report.
 * Maps to the legacy 'extoclave' parameter modes.
 */
@Getter
@RequiredArgsConstructor
public enum UnassignedCallGroupingType {
    /**
     * Groups by internal extension. (Legacy: _EXTENSION)
     */
    EXTENSION("EXTENSION"),

    /**
     * Groups by employee authorization code. (Legacy: _CLAVE)
     */
    // AUTH_CODE("AUTH_CODE"),

    /**
     * Groups by destination phone number. (Legacy: _TELDESTINO)
     */
    DESTINATION_PHONE("DESTINATION_PHONE");

    /**
     * Unified view combining extension and destination. (Legacy: default/empty)
     */
    // CONCEPT("CONCEPT");

    private final String value;
}
