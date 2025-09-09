CREATE OR REPLACE VIEW v_corporate_report AS
SELECT
    cr.id AS call_record_id,
    cr.employee_id,
    cr.destination_employee_id,
    cr.telephony_type_id,
    cr.operator_id,
    cr.indicator_id,
    cr.comm_location_id,
    emp.subdivision_id,
    ct.employee_id AS contact_employee_id,
    ind_origin.origin_country_id,
    CASE
        WHEN cr.is_incoming = true THEN cr.dial
        ELSE cr.employee_extension
        END AS origin_phone,
    CASE
        WHEN cr.is_incoming = true THEN cr.employee_extension
        ELSE cr.dial
        END AS destination_phone,
    cr.service_date,
    cr.is_incoming,
    cr.duration,
    cr.ring_count,
    cr.trunk,
    cr.initial_trunk,
    cr.employee_transfer,
    cr.assignment_cause,
    cr.transfer_cause,
    cr.billed_amount,
    cr.price_per_minute,
    emp.name AS employee_name,
    dest_emp.name AS destination_employee_name,
    tt.name AS telephony_type_name,
    op.name AS operator_name,
    cl.directory AS comm_location_directory,
    s.name AS subdivision_name,
    oc.name AS origin_country_name,
    CASE
        WHEN cc.id IS NOT NULL THEN (cc.work_order || ' - ' || cc.name)
        ELSE NULL
        END AS cost_center,
    CASE
        WHEN ind_origin.id IS NOT NULL THEN (ind_origin.city_name || ' - ' || ind_origin.department_country)
        ELSE NULL
        END AS origin_location,
    CASE
        WHEN ind_dest.id IS NOT NULL THEN (ind_dest.city_name || ' - ' || ind_dest.department_country)
        ELSE NULL
        END AS destination_location,
    ct.contact_type,
    ct.name AS contact_name,
    c.name AS company_name
FROM call_record cr
         JOIN employee emp ON emp.id = cr.employee_id
         JOIN communication_location cl ON cl.id = cr.comm_location_id
         JOIN telephony_type tt ON tt.id = cr.telephony_type_id
         LEFT JOIN subdivision s ON s.id = emp.subdivision_id
         LEFT JOIN employee dest_emp ON dest_emp.id = cr.destination_employee_id
         LEFT JOIN operator op ON op.id = cr.operator_id
         LEFT JOIN cost_center cc ON cc.id = emp.cost_center_id
         LEFT JOIN indicator ind_origin ON ind_origin.id = cl.indicator_id
         LEFT JOIN indicator ind_dest ON ind_dest.id = cr.indicator_id
         LEFT JOIN contact ct ON cr.dial = ct.phone_number
         LEFT JOIN company c ON c.id = ct.company_id
         LEFT JOIN origin_country oc ON oc.id = ind_origin.origin_country_id