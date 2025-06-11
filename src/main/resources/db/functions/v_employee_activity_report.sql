CREATE OR REPLACE FUNCTION get_employee_activity_report(
    start_date timestamp,
    end_date timestamp
)
RETURNS TABLE (
    employee_id BIGINT,
    employee_name VARCHAR,
    extension VARCHAR,
    cost_center_work_order VARCHAR,
    subdivision_name VARCHAR,
    office_location TEXT,
    outgoing_call_count BIGINT,
    incoming_call_count BIGINT,
    last_incoming_call_date TIMESTAMP,
    last_outgoing_call_date TIMESTAMP
) AS $$
BEGIN
RETURN QUERY
    WITH call_aggregates_in_range AS (
        -- Step 1: Aggregate call data ONLY within the specified date range.
        -- This is the key change.
        SELECT
            cr.employee_extension AS extension,
            COUNT(*) FILTER (WHERE cr.is_incoming = false) AS outgoing_calls,
            COUNT(*) FILTER (WHERE cr.is_incoming = true) AS incoming_calls,
            MAX(cr.service_date) FILTER (WHERE cr.is_incoming = false) AS last_outgoing,
            MAX(cr.service_date) FILTER (WHERE cr.is_incoming = true) AS last_incoming
        FROM call_record cr
        WHERE cr.service_date BETWEEN start_date AND end_date
        GROUP BY cr.employee_extension
    ),
    latest_employees AS (
        -- Step 2: Get the definitive list of employees.
        SELECT DISTINCT ON (e.extension) e.*
        FROM employee e
        WHERE e.active = true
        ORDER BY e.extension, e.id DESC
    )
-- Step 3: Join the employee list with the DYNAMIC aggregates.
SELECT
    f.id,
    f.name,
    f.extension,
    cc.work_order,
    sub.name,
    (
        SELECT i.department_country || ' / ' || i.city_name || '  /  ' || od.address
        FROM office_details od
                 JOIN indicator i ON od.indicator_id = i.id
        WHERE od.subdivision_id = f.subdivision_id
        LIMIT 1
    ) AS office_loc,
        COALESCE(agg.outgoing_calls, 0),
        COALESCE(agg.incoming_calls, 0),
        agg.last_incoming,
        agg.last_outgoing
FROM latest_employees f
    -- Use a LEFT JOIN to include employees with zero calls in the period.
    LEFT JOIN call_aggregates_in_range agg ON f.extension = agg.extension
    LEFT JOIN cost_center cc ON f.cost_center_id = cc.id
    LEFT JOIN subdivision sub ON f.subdivision_id = sub.id;
END;
$$ LANGUAGE plpgsql;