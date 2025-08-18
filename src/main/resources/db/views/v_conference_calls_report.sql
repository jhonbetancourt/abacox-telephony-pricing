CREATE OR REPLACE VIEW v_conference_calls_report AS
SELECT
    -- Call Record Details
    cr.id AS call_record_id,
    cr.service_date,
    cr.employee_extension,
    cr.duration,
    cr.is_incoming,
    cr.dial AS dialed_number,
    cr.billed_amount,
    cr.employee_auth_code,
    cr.transfer_cause,
    cr.employee_transfer AS transfer_key,

    -- Employee Details
    cr.employee_id,

    -- Operator Details
    cr.operator_id,
    o.name AS operator_name,

    -- Telephony Type Details
    cr.telephony_type_id,
    tt.name AS telephony_type_name,

    -- Enriched Contact/Company Details
    co.name AS company_name,
    c.contact_type,
    c.name AS contact_name,
    c.employee_id AS contact_owner_id

FROM
    call_record cr
-- Joins for core information
        INNER JOIN
    employee e1 ON e1.id = cr.employee_id
        INNER JOIN
    telephony_type tt ON tt.id = cr.telephony_type_id
-- Left Joins for optional/enrichment data
        LEFT JOIN
    operator o ON o.id = cr.operator_id
        LEFT JOIN
    contact c ON cr.dial IS NOT NULL AND cr.dial != '' AND cr.dial = c.phone_number
LEFT JOIN
    company co ON co.id = c.company_id
WHERE
-- This is the defining filter for the view
    cr.transfer_cause IN (10, 3);