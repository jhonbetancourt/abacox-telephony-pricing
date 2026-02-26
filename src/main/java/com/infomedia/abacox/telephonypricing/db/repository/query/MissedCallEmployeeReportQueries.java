package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class MissedCallEmployeeReportQueries {
  private MissedCallEmployeeReportQueries() {
  }

  public static final String QUERY = """
      WITH call_candidates AS (
          -- Query 1: Direct incoming calls to employee
          SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                 (cr.duration = 0 AND cr.is_incoming = true) as is_missed
          FROM call_record cr
          JOIN employee e ON cr.employee_id = e.id
          WHERE cr.service_date BETWEEN :startDate AND :endDate

          UNION ALL

          -- Query 2: Calls transferred to voicemail/employee
          SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                 (
                   (cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id <> 59)
                   OR
                   (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id = 59)
                 ) as is_missed
          FROM call_record cr
          JOIN employee e ON e.extension = cr.employee_transfer
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))

          UNION ALL

          -- Query 3: Internal calls or voicemail dials
          SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                 (
                   cr.duration = 0
                   OR
                   cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1)
                 ) as is_missed
          FROM call_record cr
          JOIN employee e ON e.extension = cr.dial
          WHERE cr.service_date BETWEEN :startDate AND :endDate
            AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
            AND cr.operator_id = 59
      )
      SELECT
          e.id as employeeId,
          e.name as employeeName,
          e.extension as employeeExtension,
          s.name as subdivisionName,
          ROUND(AVG(CASE WHEN cc.is_missed THEN cc.ring_count ELSE NULL END), 2) as averageRingTime,
          COUNT(CASE WHEN cc.is_missed THEN 1 ELSE NULL END) as missedCallCount,
          COUNT(cc.call_id) as totalCallCount,
          CASE WHEN COUNT(cc.call_id) > 0
               THEN ROUND((COUNT(CASE WHEN cc.is_missed THEN 1 ELSE NULL END) * 100.0 / COUNT(cc.call_id)), 2)
               ELSE 0
          END as missedCallPercentage
      FROM employee e
      LEFT JOIN subdivision s ON e.subdivision_id = s.id
      JOIN call_candidates cc ON cc.employee_id = e.id
      WHERE (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
        AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
      GROUP BY e.id, e.name, e.extension, s.name
      HAVING COUNT(CASE WHEN cc.is_missed THEN 1 ELSE NULL END) > 0
      ORDER BY missedCallCount DESC, employeeName ASC
      """;

  public static final String COUNT_QUERY = """
      WITH missed_call_employees AS (
          SELECT e.id
          FROM employee e
          JOIN (
              SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                   (cr.duration = 0 AND cr.is_incoming = true) as is_missed
            FROM call_record cr
            JOIN employee e ON cr.employee_id = e.id
            WHERE cr.service_date BETWEEN :startDate AND :endDate

            UNION ALL

            SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                   (
                     (cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id <> 59)
                     OR
                     (cr.dial IN (SELECT se.extension FROM special_extension se WHERE se.type = 1) AND cr.operator_id = 59)
                   ) as is_missed
            FROM call_record cr
            JOIN employee e ON e.extension = cr.employee_transfer
            WHERE cr.service_date BETWEEN :startDate AND :endDate
              AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))

            UNION ALL

            SELECT cr.id as call_id, e.id as employee_id, cr.ring_count,
                   (
                     cr.duration = 0
                     OR
                     cr.employee_transfer IN (SELECT se.extension FROM special_extension se WHERE se.type = 1)
                   ) as is_missed
            FROM call_record cr
            JOIN employee e ON e.extension = cr.dial
            WHERE cr.service_date BETWEEN :startDate AND :endDate
              AND (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
              AND cr.operator_id = 59
        ) cc ON cc.employee_id = e.id
        WHERE (e.history_control_id IS NULL OR e.id IN (SELECT hc.ref_id FROM history_control hc WHERE hc.ref_table = 1))
          AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%'))
          GROUP BY e.id
          HAVING COUNT(CASE WHEN cc.is_missed THEN 1 ELSE NULL END) > 0
      )
      SELECT COUNT(*) FROM missed_call_employees
      """;
}