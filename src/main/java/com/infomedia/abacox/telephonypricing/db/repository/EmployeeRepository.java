package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    /**
     * Deactivates duplicate active employees for each extension, leaving only one active.
     * This method identifies all extensions that have more than one active employee. For each of these groups,
     * it selects one employee to remain active based on the following criteria:
     * 1.  An employee with a "valid" name (not null, not an empty string, and not 'libre' case-insensitively) is preferred.
     * 2.  If multiple employees have valid names, the one with the highest ID is chosen.
     * 3.  If all employees in the group have "invalid" names, the one with the highest ID is chosen.
     * All other employees in the group are deactivated by setting their 'active' flag to false.
     *
     * This is a data integrity and cleanup operation.
     *
     * @return The number of employee records that were deactivated.
     */
    @Modifying
    @Transactional
    @Query(value = """
        WITH DuplicatesToDeactivate AS (
            SELECT
                id,
                ROW_NUMBER() OVER (
                    PARTITION BY extension
                    ORDER BY
                        CASE
                            WHEN name IS NULL OR name = '' OR LOWER(name) = 'libre' THEN 1
                            ELSE 0
                        END ASC,
                        id DESC
                ) as rn
            FROM
                employee
            WHERE
                active = true
                AND extension IN (
                    SELECT
                        extension
                    FROM
                        employee
                    WHERE
                        active = true
                    GROUP BY
                        extension
                    HAVING
                        COUNT(id) > 1
                )
        )
        UPDATE
            employee
        SET
            active = false,
            last_modified_date = NOW()
        WHERE
            id IN (
                SELECT
                    id
                FROM
                    DuplicatesToDeactivate
                WHERE
                    rn > 1
            )
        """, nativeQuery = true)
    int deactivateDuplicateActiveEmployees();
}