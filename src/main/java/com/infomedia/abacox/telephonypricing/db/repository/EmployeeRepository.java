// File: com/infomedia/abacox/telephonypricing/repository/EmployeeRepository.java
package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    /**
     * Deactivates duplicate active employees for each extension, leaving only the one with the highest ID active.
     * This method identifies all extensions that have more than one active employee, and for each of those extensions,
     * it sets the 'active' flag to false for all employees except for the one with the maximum ID.
     *
     * This is a data integrity and cleanup operation.
     *
     * @return The number of employee records that were deactivated.
     */
    @Modifying
    @Transactional
    @Query(value = """
        WITH DuplicatesToDeactivate AS (
            -- Step 1: Identify all active employees who are part of a duplicate extension group.
            -- We use a window function to rank employees within each extension group by their ID.
            SELECT
                id,
                -- Rank employees within each extension group, highest ID gets rank 1.
                ROW_NUMBER() OVER (PARTITION BY extension ORDER BY id DESC) as rn
            FROM
                employee
            WHERE
                active = true
                AND extension IN (
                    -- Subquery to find only those extensions with more than one active employee.
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
        -- Step 2: Update the 'active' flag to false for all identified duplicates
        -- except for the one with the highest ID (which has rn = 1).
        UPDATE
            employee
        SET
            active = false,
            last_modified_date = NOW() -- Also update the audit timestamp
        WHERE
            id IN (
                SELECT
                    id
                FROM
                    DuplicatesToDeactivate
                WHERE
                    rn > 1 -- Deactivate all but the highest-ranked one
            )
        """, nativeQuery = true)
    int deactivateDuplicateActiveEmployees();
}