package com.infomedia.abacox.telephonypricing.db.projection;

/**
 * Projection for the Processing Failure diagnostic report.
 * <p>
 * This interface maps to a native SQL query that aggregates failed call records
 * to identify the most common types of errors. It groups failures by the error type
 * and the trusted configuration of the Communication Location where the failure occurred.
 */
public interface ProcessingFailureReport {

    /**
     * @return The type of error that occurred (e.g., "PARSING_ERROR", "ENRICHMENT_ERROR").
     */
    String getErrorType();

    /**
     * @return The ID of the communication location where the failure occurred.
     */
    Long getCommLocationId();

    /**
     * @return The directory name of the communication location.
     */
    String getCommLocationDirectory();

    /**
     * @return The ID of the plant type configured for this location.
     */
    Long getPlantTypeId();

    /**
     * @return The name of the plant type, which represents the trusted configuration.
     */
    String getPlantTypeName();

    /**
     * @return The total count of identical failures for this group.
     */
    Long getFailureCount();
}