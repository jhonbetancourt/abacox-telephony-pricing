package com.infomedia.abacox.telephonypricing.db.repository.query;

public final class ConferenceCallsReportQueries {
  private ConferenceCallsReportQueries() {
  }

  public static final String CONFERENCE_CANDIDATES_QUERY = """
      SELECT
          cr.id AS callRecordId,
          cr.service_date AS serviceDate,
          cr.employee_extension AS employeeExtension,
          cr.duration,
          cr.dial AS dialedNumber,
          cr.billed_amount AS billedAmount,
          cr.employee_auth_code AS employeeAuthCode,
          cr.employee_id AS employeeId,
          e.name AS employeeName,
          e.subdivision_id AS subdivisionId,
          s.name AS subdivisionName,
          cr.operator_id AS operatorId,
          o.name AS operatorName,
          cr.telephony_type_id AS telephonyTypeId,
          tt.name AS telephonyTypeName,
          co.name AS companyName,
          c.contact_type AS contactType,
          c.name AS contactName,
          c.employee_id AS contactOwnerId,
          cr.employee_transfer AS transferKey,
          org.id AS organizerId,
          org.name AS organizerName,
          org.subdivision_id AS organizerSubdivisionId,
          s_org.name AS organizerSubdivisionName,
          cr.transfer_cause AS transferCause
      FROM call_record cr
      INNER JOIN employee e ON e.id = cr.employee_id
      INNER JOIN telephony_type tt ON tt.id = cr.telephony_type_id
      LEFT JOIN subdivision s ON s.id = e.subdivision_id
      LEFT JOIN operator o ON o.id = cr.operator_id
      LEFT JOIN contact c ON cr.dial IS NOT NULL AND cr.dial != '' AND cr.dial = c.phone_number
      LEFT JOIN company co ON co.id = c.company_id
      LEFT JOIN employee org ON org.extension = cr.dial AND org.active = true
      LEFT JOIN subdivision s_org ON s_org.id = org.subdivision_id
      WHERE cr.transfer_cause IN (10, 3)
        AND cr.employee_transfer IS NOT NULL AND cr.employee_transfer != ''
        AND cr.service_date BETWEEN :startDate AND :endDate
        AND (:extension IS NULL OR cr.employee_extension ILIKE CONCAT('%', :extension, '%') OR cr.dial ILIKE CONCAT('%', :extension, '%'))
        AND (:employeeName IS NULL OR e.name ILIKE CONCAT('%', :employeeName, '%') OR org.name ILIKE CONCAT('%', :employeeName, '%'))
      ORDER BY cr.employee_transfer, cr.service_date, cr.transfer_cause
      """;
}
