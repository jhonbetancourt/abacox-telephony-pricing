package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class ConferenceCallsReportQueries {
  private ConferenceCallsReportQueries() {
  }

  /**
   * CTE that detects time gaps > 30 minutes between consecutive calls
   * in the same (employee_transfer, dial) group, and assigns a conference number.
   * This ensures that calls reusing the same transfer key hours apart
   * are treated as separate conferences.
   */
  private static final String CONFERENCE_CTE = """
      WITH with_prev AS (
          SELECT cr.*,
              LAG(cr.service_date) OVER (
                  PARTITION BY cr.employee_transfer, cr.dial ORDER BY cr.service_date
              ) AS prev_date
          FROM call_record cr
          WHERE cr.transfer_cause IN (10, 3)
            AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != ''
            AND cr.service_date BETWEEN :startDate AND :endDate
      ),
      conf_numbered AS (
          SELECT *,
              SUM(CASE WHEN prev_date IS NULL
                       OR EXTRACT(EPOCH FROM (service_date - prev_date)) > 1800
                       THEN 1 ELSE 0 END) OVER (
                  PARTITION BY employee_transfer, dial ORDER BY service_date
              ) AS conf_num
          FROM with_prev
      )
      """;

  public static final String GROUPS_QUERY = CONFERENCE_CTE
      + """
          SELECT
              cn.employee_transfer AS transferKey,
              cn.dial AS dialedNumber,
              cn.conf_num AS conferenceNumber,
              MIN(cn.service_date) AS conferenceServiceDate,
              COUNT(*) AS participantCount,
              COALESCE(SUM(cn.billed_amount), 0) AS totalBilled,
              org.id AS organizerId,
              org.name AS organizerName,
              org.subdivision_id AS organizerSubdivisionId,
              org_sub.name AS organizerSubdivisionName
          FROM conf_numbered cn
          LEFT JOIN employee org ON org.extension = cn.dial AND org.active = true
          LEFT JOIN subdivision org_sub ON org_sub.id = org.subdivision_id
          LEFT JOIN employee emp ON emp.id = cn.employee_id
          WHERE (:extension IS NULL OR cn.employee_extension ILIKE CONCAT('%', :extension, '%') OR cn.dial ILIKE CONCAT('%', :extension, '%'))
            AND (:employeeName IS NULL OR emp.name ILIKE CONCAT('%', :employeeName, '%') OR org.name ILIKE CONCAT('%', :employeeName, '%'))
          GROUP BY cn.employee_transfer, cn.dial, cn.conf_num, org.id, org.name, org.subdivision_id, org_sub.name
          ORDER BY MIN(cn.service_date) DESC
          """;

  public static final String GROUPS_COUNT_QUERY = CONFERENCE_CTE
      + """
          SELECT COUNT(*) FROM (
              SELECT 1
              FROM conf_numbered cn
              LEFT JOIN employee org ON org.extension = cn.dial AND org.active = true
              LEFT JOIN employee emp ON emp.id = cn.employee_id
              WHERE (:extension IS NULL OR cn.employee_extension ILIKE CONCAT('%', :extension, '%') OR cn.dial ILIKE CONCAT('%', :extension, '%'))
                AND (:employeeName IS NULL OR emp.name ILIKE CONCAT('%', :employeeName, '%') OR org.name ILIKE CONCAT('%', :employeeName, '%'))
              GROUP BY cn.employee_transfer, cn.dial, cn.conf_num
          ) AS groups
          """;

  public static final String PARTICIPANTS_QUERY = CONFERENCE_CTE + """
      SELECT
          cn.id AS callRecordId,
          cn.service_date AS serviceDate,
          cn.employee_extension AS employeeExtension,
          cn.duration,
          cn.is_incoming AS isIncoming,
          cn.dial AS dialedNumber,
          cn.billed_amount AS billedAmount,
          cn.employee_auth_code AS employeeAuthCode,
          cn.transfer_cause AS transferCause,
          cn.employee_transfer AS transferKey,
          cn.employee_id AS employeeId,
          e.name AS employeeName,
          e.subdivision_id AS subdivisionId,
          s.name AS subdivisionName,
          cn.operator_id AS operatorId,
          o.name AS operatorName,
          cn.telephony_type_id AS telephonyTypeId,
          tt.name AS telephonyTypeName,
          co.name AS companyName,
          c.contact_type AS contactType,
          c.name AS contactName,
          c.employee_id AS contactOwnerId,
          cn.conf_num AS conferenceNumber
      FROM conf_numbered cn
      INNER JOIN employee e ON e.id = cn.employee_id
      INNER JOIN telephony_type tt ON tt.id = cn.telephony_type_id
      LEFT JOIN subdivision s ON s.id = e.subdivision_id
      LEFT JOIN operator o ON o.id = cn.operator_id
      LEFT JOIN contact c ON cn.dial IS NOT NULL AND cn.dial != '' AND cn.dial = c.phone_number
      LEFT JOIN company co ON co.id = c.company_id
      WHERE 1=1
      """;
}
