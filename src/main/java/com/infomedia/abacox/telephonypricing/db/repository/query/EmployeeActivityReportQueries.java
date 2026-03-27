package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class EmployeeActivityReportQueries {
    private EmployeeActivityReportQueries() {
    } // Private constructor to prevent instantiation

    public static final String QUERY = """
            WITH RECURSIVE top_cost_centers AS (
                SELECT id, work_order AS top_work_order
                FROM cost_center
                WHERE parent_cost_center_id IS NULL
                UNION ALL
                SELECT cc_child.id, tcc.top_work_order
                FROM cost_center cc_child
                JOIN top_cost_centers tcc ON cc_child.parent_cost_center_id = tcc.id
            ),
            call_aggregates_in_range AS (
                SELECT
                    cr.employee_extension AS extension,
                    COUNT(*) FILTER (WHERE NOT cr.is_incoming) AS outgoing_calls,
                    COUNT(*) FILTER (WHERE cr.is_incoming) AS incoming_calls,
                    MAX(cr.service_date) FILTER (WHERE NOT cr.is_incoming) AS last_outgoing,
                    MAX(cr.service_date) FILTER (WHERE cr.is_incoming) AS last_incoming,
                    COALESCE(SUM(cr.duration) FILTER (WHERE cr.is_incoming), 0) AS incoming_duration,
                    COALESCE(SUM(cr.duration) FILTER (WHERE NOT cr.is_incoming), 0) AS outgoing_duration,
                    COALESCE(SUM(cr.ring_count) FILTER (WHERE cr.is_incoming), 0) AS incoming_ring,
                    COALESCE(SUM(cr.ring_count) FILTER (WHERE NOT cr.is_incoming), 0) AS outgoing_ring,
                    COUNT(*) FILTER (WHERE cr.transfer_cause IN (1, 2, 6, 7) AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != '') AS transfer_count,
                    COUNT(*) FILTER (WHERE cr.transfer_cause IN (3, 10) AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != '') AS conference_count
                FROM call_record cr
                WHERE cr.service_date BETWEEN :startDate AND :endDate
                GROUP BY cr.employee_extension
            ),
            latest_employees AS (
                SELECT DISTINCT ON (e.extension)
                    e.id, e.name, e.extension, e.cost_center_id, e.subdivision_id
                FROM employee e
                ORDER BY e.extension, e.id DESC
            ),
            inv_by_employee AS (
                SELECT employee_id, MAX(id) AS inv_id
                FROM inventory
                WHERE employee_id IS NOT NULL
                GROUP BY employee_id
            ),
            inv_by_subdivision AS (
                SELECT subdivision_id, MAX(id) AS inv_id
                FROM inventory
                WHERE subdivision_id IS NOT NULL
                GROUP BY subdivision_id
            ),
            inv_by_cost_center AS (
                SELECT emp.cost_center_id, MAX(inv.id) AS inv_id
                FROM inventory inv
                JOIN employee emp ON emp.id = inv.employee_id
                WHERE emp.cost_center_id IS NOT NULL
                GROUP BY emp.cost_center_id
            )
            SELECT
                f.id AS employeeId,
                f.name AS employeeName,
                f.extension AS extension,
                f.cost_center_id AS costCenterId,
                cc.name AS costCenterName,
                cc.work_order AS costCenterWorkOrder,
                tcc.top_work_order AS nit,
                f.subdivision_id AS subdivisionId,
                sub.name AS subdivisionName,
                office_info.department_country AS departmentCountry,
                office_info.city_name AS cityName,
                office_info.full_location AS officeLocation,
                COALESCE(agg.outgoing_calls, 0) AS outgoingCallCount,
                COALESCE(agg.incoming_calls, 0) AS incomingCallCount,
                COALESCE(agg.outgoing_calls, 0) + COALESCE(agg.incoming_calls, 0) AS totalCallCount,
                agg.last_incoming AS lastIncomingCallDate,
                agg.last_outgoing AS lastOutgoingCallDate,
                (agg.last_incoming IS NOT NULL OR agg.last_outgoing IS NOT NULL) AS isUsed,
                COALESCE(agg.incoming_duration, 0) AS incomingDuration,
                COALESCE(agg.outgoing_duration, 0) AS outgoingDuration,
                COALESCE(agg.incoming_ring, 0) AS incomingRingDuration,
                COALESCE(agg.outgoing_ring, 0) AS outgoingRingDuration,
                COALESCE(agg.transfer_count, 0) AS transferCount,
                COALESCE(agg.conference_count, 0) AS conferenceCount,
                inv.installation_date AS installationDate,
                et.name AS equipmentTypeName,
                ie.name AS equipmentModelName
            FROM latest_employees f
            LEFT JOIN call_aggregates_in_range agg ON f.extension = agg.extension
            LEFT JOIN cost_center cc ON f.cost_center_id = cc.id
            LEFT JOIN top_cost_centers tcc ON tcc.id = f.cost_center_id
            LEFT JOIN subdivision sub ON f.subdivision_id = sub.id
            LEFT JOIN LATERAL (
                SELECT i.department_country, i.city_name,
                       i.department_country || ' / ' || i.city_name || '  /  ' || od.address AS full_location
                FROM office_details od
                JOIN indicator i ON od.indicator_id = i.id
                WHERE od.subdivision_id = f.subdivision_id
                LIMIT 1
            ) office_info ON true
            LEFT JOIN inv_by_employee ibe ON ibe.employee_id = f.id
            LEFT JOIN inv_by_subdivision ibs ON ibs.subdivision_id = f.subdivision_id
            LEFT JOIN inv_by_cost_center ibc ON ibc.cost_center_id = f.cost_center_id
            LEFT JOIN inventory inv ON inv.id = COALESCE(ibe.inv_id, ibs.inv_id, ibc.inv_id)
            LEFT JOIN equipment_type et ON et.id = inv.equipment_type_id
            LEFT JOIN inventory_equipment ie ON ie.id = inv.inventory_equipment_id
            WHERE
                (:employeeName IS NULL OR f.name ILIKE CONCAT('%', :employeeName, '%'))
            AND
                (:extension IS NULL OR f.extension = :extension)
            AND
                (:subdivisionId IS NULL OR f.subdivision_id = :subdivisionId)
            AND
                (:costCenterId IS NULL OR f.cost_center_id = :costCenterId)
            """;
}