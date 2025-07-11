CREATE OR REPLACE VIEW v_corporate_report AS
SELECT
    -- Primary Keys and IDs from the new schema
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

    -- Call Details from the 'call_record' table
    cr.dial,
    cr.destination_phone,
    cr.employee_extension,
    cr.service_date,
    cr.is_incoming,
    cr.duration,
    cr.ring_count,
    cr.trunk,
    cr.initial_trunk,
    cr.employee_transfer,
    cr.assignment_cause,
    cr.transfer_cause,

    -- Pricing Details from the 'call_record' table
    cr.billed_amount,
    cr.price_per_minute,

    -- Joined Data (Names and Descriptions from related tables)
    emp.name AS employee_name,
    dest_emp.name AS destination_employee_name,
    tt.name AS telephony_type_name,
    op.name AS operator_name,
    cl.directory AS comm_location_directory,
    ind_origin.department_country AS origin_country,
    ind_origin.city_name AS origin_city,
    ind_dest.department_country AS destination_country,
    ind_dest.city_name AS destination_city,
    cc.name AS cost_center_name,
    cc.work_order,
    ct.contact_type,
    ct.name AS contact_name,
    c.name AS company_name

FROM call_record cr
         -- Required Joins (INNER JOINs ensure data integrity)
         JOIN employee emp ON emp.id = cr.employee_id
         JOIN communication_location cl ON cl.id = cr.comm_location_id
         JOIN telephony_type tt ON tt.id = cr.telephony_type_id

    -- Optional Joins (LEFT JOINs handle cases where the relationship might be null)
         LEFT JOIN employee dest_emp ON dest_emp.id = cr.destination_employee_id
         LEFT JOIN operator op ON op.id = cr.operator_id
         LEFT JOIN cost_center cc ON cc.id = emp.cost_center_id
         LEFT JOIN indicator ind_origin ON ind_origin.id = cl.indicator_id
         LEFT JOIN indicator ind_dest ON ind_dest.id = cr.indicator_id

    -- Dynamic Join (LEFT JOIN on a non-key, data-driven field)
         LEFT JOIN contact ct ON cr.dial = ct.phone_number
         LEFT JOIN company c ON c.id = ct.company_id;