// FILE: lookup/EmployeeLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId) {
        log.debug("Looking up employee by extension: '{}', authCode: '{}', commLocationId: {}", extension, authCode, commLocationId);
        StringBuilder sqlBuilder = new StringBuilder("SELECT e.* FROM employee e WHERE e.active = true ");
        Map<String, Object> params = new HashMap<>();

        if (commLocationId != null) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
            log.warn("CommLocationId is null during employee lookup. Results may be incorrect if extensions/codes are not unique across locations.");
        }

        if (authCode != null && !authCode.isEmpty()) {
            sqlBuilder.append(" AND e.auth_code = :authCode");
            params.put("authCode", authCode);
        } else if (extension != null && !extension.isEmpty()){
            sqlBuilder.append(" AND e.extension = :extension");
            params.put("extension", extension);
        } else {
            log.trace("No valid identifier (extension or authCode) provided for employee lookup.");
            return Optional.empty();
        }
        sqlBuilder.append(" LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Employee.class);
        params.forEach(query::setParameter);

        try {
            Employee employee = (Employee) query.getSingleResult();
            log.trace("Found employee: {}", employee.getId());
            return Optional.of(employee);
        } catch (NoResultException e) {
            log.trace("Employee not found for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding employee for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId, e);
            return Optional.empty();
        }
    }

    public Optional<Employee> findEmployeeById(Long id) {
        if (id == null || id <= 0) return Optional.empty();
        String sql = "SELECT e.* FROM employee e WHERE e.id = :id AND e.active = true";
        Query query = entityManager.createNativeQuery(sql, Employee.class);
        query.setParameter("id", id);
        try { return Optional.of((Employee) query.getSingleResult()); }
        catch (NoResultException e) { return Optional.empty(); }
    }

    public Optional<Employee> findDestinationEmployeeById(Long id) {
        // Same logic as findEmployeeById, potentially different cache/logging if needed
        return findEmployeeById(id);
    }
}