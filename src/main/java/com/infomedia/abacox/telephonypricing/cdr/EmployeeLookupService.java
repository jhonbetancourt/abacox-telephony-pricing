package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Log4j2
@Transactional(readOnly = true)
public class EmployeeLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final CdrProcessingConfig configService;

    public EmployeeLookupService(EntityManager entityManager, CdrProcessingConfig configService) {
        this.entityManager = entityManager;
        this.configService = configService;
    }

    public Optional<Employee> findEmployeeByExtensionOrAuthCode(String extension, String authCode, Long commLocationId) {
        log.debug("Looking up employee by extension: '{}', authCode: '{}', commLocationId: {}", extension, authCode, commLocationId);
        StringBuilder sqlBuilder = new StringBuilder("SELECT e.* FROM employee e WHERE e.active = true ");
        Map<String, Object> params = new HashMap<>();

        if (commLocationId != null && commLocationId > 0) {
            sqlBuilder.append(" AND e.communication_location_id = :commLocationId");
            params.put("commLocationId", commLocationId);
        } else {
            log.trace("CommLocationId is null or 0 for employee lookup. Searching without location constraint if not found locally.");
        }

        boolean hasAuthCode = StringUtils.hasText(authCode) && !configService.getIgnoredAuthCodes().contains(authCode);
        boolean hasExtension = StringUtils.hasText(extension);

        String specificCondition = "";
        if (hasAuthCode) {
            specificCondition = " AND e.auth_code = :authCode ";
            params.put("authCode", authCode);
        } else if (hasExtension) {
            specificCondition = " AND e.extension = :extension ";
            params.put("extension", extension);
        } else {
            log.trace("No valid identifier (extension or non-ignored authCode) provided for employee lookup.");
            return Optional.empty();
        }
        
        sqlBuilder.append(specificCondition);
        sqlBuilder.append(" ORDER BY e.id DESC LIMIT 1");

        Query query = entityManager.createNativeQuery(sqlBuilder.toString(), Employee.class);
        params.forEach(query::setParameter);

        try {
            Employee employee = (Employee) query.getSingleResult();
            log.trace("Found employee: {}", employee.getId());
            return Optional.of(employee);
        } catch (NoResultException e) {
            log.trace("Employee not found for ext: '{}', code: '{}', loc: {}", extension, authCode, commLocationId);
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Error finding employee for ext: '{}', code: '{}', loc: {}: {}", extension, authCode, commLocationId, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> findRangeAssignment(String extension, Long commLocationId, LocalDateTime callTime) {
        if (!StringUtils.hasText(extension) || commLocationId == null || callTime == null) return Optional.empty();
        log.debug("Finding range assignment for ext: {}, commLocationId: {}, callTime: {}", extension, commLocationId, callTime);

        long extNumeric;
        try {
            extNumeric = Long.parseLong(extension.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            log.warn("Extension {} is not numeric, cannot perform range assignment.", extension);
            return Optional.empty();
        }

        String sql = "SELECT er.subdivision_id, er.cost_center_id, er.prefix as range_prefix " +
                     "FROM extension_range er " +
                     "WHERE er.active = true " +
                     "  AND er.comm_location_id = :commLocationId " +
                     "  AND er.range_start <= :extNumeric " +
                     "  AND er.range_end >= :extNumeric " +
                     "ORDER BY (er.range_end - er.range_start) ASC, er.id DESC " +
                     "LIMIT 1";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("commLocationId", commLocationId);
        query.setParameter("extNumeric", extNumeric);

        try {
            Object[] result = (Object[]) query.getSingleResult();
            Map<String, Object> map = new HashMap<>();
            map.put("subdivision_id", result[0]);
            map.put("cost_center_id", result[1]);
            map.put("range_prefix", result[2]);
            log.trace("Found range assignment for extension {}: {}", extension, map);
            return Optional.of(map);
        } catch (NoResultException e) {
            log.trace("No active range assignment found for extension {}", extension);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error finding range assignment for extension {}: {}", extension, e.getMessage(), e);
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
}