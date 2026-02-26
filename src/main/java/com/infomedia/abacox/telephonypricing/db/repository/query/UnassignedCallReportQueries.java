package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class UnassignedCallReportQueries {
    private UnassignedCallReportQueries() {
    }

    public static final String QUERY = """
            WITH filtered_data AS (
                SELECT
                    cr.*,
                    CONCAT(cl.directory, ' - ', pt.name) as plant,
                    CASE
                        WHEN :groupingType = 'EXTENSION' THEN cr.employee_extension
                        ELSE COALESCE(NULLIF(cr.dial, ''), cr.destination_phone)
                    END as concept_val
                FROM
                    call_record cr
                    JOIN communication_location cl ON cr.comm_location_id = cl.id
                    JOIN plant_type pt ON cl.plant_type_id = pt.id
                WHERE
                    cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.duration > 0
                    AND (
                        (:groupingType = 'EXTENSION' AND (
                            (cr.employee_id IS NULL OR cr.assignment_cause = 5)
                            AND LENGTH(cr.employee_extension) BETWEEN :minLength AND :maxLength
                        ))
                        OR
                        (:groupingType = 'DESTINATION_PHONE' AND
                            cr.is_incoming = false AND
                            (
                                cr.employee_id IS NULL
                                OR (cr.employee_id IS NOT NULL AND (cr.telephony_type_id IS NULL OR cr.operator_id IS NULL))
                            )
                        )
                    )
                    AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
            )
            SELECT
                concept_val as concept,
                plant,
                comm_location_id as commLocationId,
                SUM(billed_amount) as totalCost,
                SUM(duration) as totalDuration,
                COUNT(id) as callCount,
                MAX(service_date) as lastCallDate
            FROM
                filtered_data
            GROUP BY
                concept_val, plant, comm_location_id
            ORDER BY totalCost DESC, callCount DESC, lastCallDate DESC
            """;

    public static final String COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM
                    call_record cr
                    JOIN communication_location cl ON cr.comm_location_id = cl.id
                    JOIN plant_type pt ON cl.plant_type_id = pt.id
                WHERE
                    cr.service_date BETWEEN :startDate AND :endDate
                    AND cr.duration > 0
                    AND (
                        (:groupingType = 'EXTENSION' AND (
                            (cr.employee_id IS NULL OR cr.assignment_cause = 5)
                            AND LENGTH(cr.employee_extension) BETWEEN :minLength AND :maxLength
                        ))
                        OR
                        (:groupingType = 'DESTINATION_PHONE' AND
                            cr.is_incoming = false AND
                            (
                                cr.employee_id IS NULL
                                OR (cr.employee_id IS NOT NULL AND (cr.telephony_type_id IS NULL OR cr.operator_id IS NULL))
                            )
                        )
                    )
                    AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%'))
                GROUP BY
                    CASE
                        WHEN :groupingType = 'EXTENSION' THEN cr.employee_extension
                        ELSE COALESCE(NULLIF(cr.dial, ''), cr.destination_phone)
                    END,
                    CONCAT(cl.directory, ' - ', pt.name), cr.comm_location_id
            ) AS count_subquery
            """;
}
