package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MissedCallEmployeeReportQueries {
  private MissedCallEmployeeReportQueries() {
  }

  public static final String QUERY = """
      WITH 
      -- Get internal operator IDs from prefix table (matching PHP _operador_Internas)
      internal_operators AS (
          SELECT DISTINCT o.id as operator_id
          FROM operator o
          JOIN prefix p ON p.operator_id = o.id
          WHERE p.telephony_type_id IN (7, 8, 9, 14)
            AND o.active = true
            AND p.active = true
      ),
      -- Total incoming calls per employee (no ring filter, matching PHP subtotal_in)
      -- Includes all reference table joins that PHP uses
      total_calls AS (
          -- Query 1: Direct incoming calls to employee
          SELECT e.id as employee_id, SUM(CASE WHEN cr.is_incoming THEN 1 ELSE 0 END) as total_call_count
          FROM call_record cr
          JOIN employee e ON cr.employee_id = e.id
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND cr.is_incoming = true
          GROUP BY e.id

          UNION ALL

          -- Query 2: Calls transferred to employee extension
          SELECT e.id as employee_id, COUNT(*) as total_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.employee_transfer
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
          GROUP BY e.id

          UNION ALL

          -- Query 3: Internal calls to employee dial
          SELECT e.id as employee_id, COUNT(*) as total_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.dial
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.operator_id IN (SELECT operator_id FROM internal_operators)
          GROUP BY e.id
      ),
      -- Missed calls per employee (with ring filter, matching PHP default case)
      missed_calls AS (
          -- Query 1: Direct incoming calls with duration=0 and ring filter
          SELECT 
              e.id as employee_id,
              COUNT(*) as missed_call_count,
              SUM(cr.ring_count) as total_ring_count
          FROM call_record cr
          JOIN employee e ON cr.employee_id = e.id
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND cr.duration = 0
            AND cr.is_incoming = true
            AND cr.ring_count >= :minRingCount
          GROUP BY e.id

          UNION ALL

          -- Query 2: Calls transferred to voicemail/employee with ring filter
          SELECT 
              e.id as employee_id,
              COUNT(*) as missed_call_count,
              SUM(cr.ring_count) as total_ring_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.employee_transfer
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.ring_count >= :minRingCount
            AND (
              (cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id NOT IN (SELECT operator_id FROM internal_operators))
              OR
              (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id IN (SELECT operator_id FROM internal_operators))
            )
          GROUP BY e.id

          UNION ALL

          -- Query 3: Internal calls or voicemail dials with ring filter
          SELECT 
              e.id as employee_id,
              COUNT(*) as missed_call_count,
              SUM(cr.ring_count) as total_ring_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.dial
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.operator_id IN (SELECT operator_id FROM internal_operators)
            AND cr.ring_count >= :minRingCount
            AND (
              cr.duration = 0
              OR
              cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1)
            )
          GROUP BY e.id
      ),
      -- Aggregate totals per employee
      total_agg AS (
          SELECT employee_id, SUM(total_call_count) as total_calls
          FROM total_calls
          GROUP BY employee_id
      ),
      -- Aggregate missed per employee
      missed_agg AS (
          SELECT employee_id, SUM(missed_call_count) as missed_calls, SUM(total_ring_count) as total_rings
          FROM missed_calls
          GROUP BY employee_id
      )
      SELECT
          e.id as employeeId,
          e.name as employeeName,
          e.extension as employeeExtension,
          s.name as subdivisionName,
          ROUND(COALESCE(ma.total_rings, 0) / NULLIF(ma.missed_calls, 0), 2) as averageRingTime,
          COALESCE(ma.missed_calls, 0) as missedCallCount,
          COALESCE(ta.total_calls, 0) as totalCallCount,
          CASE WHEN COALESCE(ta.total_calls, 0) > 0
               THEN ROUND((COALESCE(ma.missed_calls, 0) * 100.0 / ta.total_calls), 2)
               ELSE 0
          END as missedCallPercentage
      FROM employee e
      LEFT JOIN subdivision s ON e.subdivision_id = s.id
      LEFT JOIN total_agg ta ON ta.employee_id = e.id
      LEFT JOIN missed_agg ma ON ma.employee_id = e.id
      WHERE (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
        AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
        AND COALESCE(ma.missed_calls, 0) > 0
      ORDER BY missedCallCount DESC
      """;

  public static final String COUNT_QUERY = """
      WITH 
      internal_operators AS (
          SELECT DISTINCT o.id as operator_id
          FROM operator o
          JOIN prefix p ON p.operator_id = o.id
          WHERE p.telephony_type_id IN (7, 8, 9, 14)
            AND o.active = true
            AND p.active = true
      ),
      total_calls AS (
          SELECT e.id as employee_id, SUM(CASE WHEN cr.is_incoming THEN 1 ELSE 0 END) as total_call_count
          FROM call_record cr
          JOIN employee e ON cr.employee_id = e.id
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND cr.is_incoming = true
          GROUP BY e.id

          UNION ALL

          SELECT e.id as employee_id, COUNT(*) as total_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.employee_transfer
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
          GROUP BY e.id

          UNION ALL

          SELECT e.id as employee_id, COUNT(*) as total_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.dial
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.operator_id IN (SELECT operator_id FROM internal_operators)
          GROUP BY e.id
      ),
      missed_calls AS (
          SELECT e.id as employee_id, COUNT(*) as missed_call_count
          FROM call_record cr
          JOIN employee e ON cr.employee_id = e.id
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND cr.duration = 0
            AND cr.is_incoming = true
            AND cr.ring_count >= :minRingCount
          GROUP BY e.id

          UNION ALL

          SELECT e.id as employee_id, COUNT(*) as missed_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.employee_transfer
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.ring_count >= :minRingCount
            AND (
              (cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id NOT IN (SELECT operator_id FROM internal_operators))
              OR
              (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id IN (SELECT operator_id FROM internal_operators))
            )
          GROUP BY e.id

          UNION ALL

          SELECT e.id as employee_id, COUNT(*) as missed_call_count
          FROM call_record cr
          JOIN employee e ON e.extension = cr.dial
          JOIN telephony_type tt ON cr.telephony_type_id = tt.id
          JOIN communication_location cl ON cr.comm_location_id = cl.id
          JOIN indicator i ON cr.indicator_id = i.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.operator_id IN (SELECT operator_id FROM internal_operators)
            AND cr.ring_count >= :minRingCount
            AND (
              cr.duration = 0
              OR
              cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1)
            )
          GROUP BY e.id
      ),
      total_agg AS (
          SELECT employee_id, SUM(total_call_count) as total_calls
          FROM total_calls
          GROUP BY employee_id
      ),
      missed_agg AS (
          SELECT employee_id, SUM(missed_call_count) as missed_calls
          FROM missed_calls
          GROUP BY employee_id
      ),
      missed_call_employees AS (
          SELECT e.id
          FROM employee e
          LEFT JOIN missed_agg ma ON ma.employee_id = e.id
          WHERE (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
            AND COALESCE(ma.missed_calls, 0) > 0
      )
      SELECT COUNT(*) FROM missed_call_employees
      """;
}
