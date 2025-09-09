CREATE OR REPLACE VIEW v_conference_calls_report AS
SELECT
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

    cr.employee_id,
    e1.name AS employee_name,
    e1.subdivision_id,
    s.name AS subdivision_name,

    cr.operator_id,
    o.name AS operator_name,

    cr.telephony_type_id,
    tt.name AS telephony_type_name,

    co.name AS company_name,
    c.contact_type,
    c.name AS contact_name,
    c.employee_id AS contact_owner_id

FROM
    call_record cr
        INNER JOIN
    employee e1 ON e1.id = cr.employee_id
        INNER JOIN
    telephony_type tt ON tt.id = cr.telephony_type_id
        LEFT JOIN
    subdivision s ON s.id = e1.subdivision_id
        LEFT JOIN
    operator o ON o.id = cr.operator_id
        LEFT JOIN
    contact c ON cr.dial IS NOT NULL AND cr.dial != '' AND cr.dial = c.phone_number
LEFT JOIN
    company co ON co.id = c.company_id
WHERE
    cr.transfer_cause IN (10, 3)