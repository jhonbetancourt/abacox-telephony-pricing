package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.constants.RefTable;
import com.infomedia.abacox.telephonypricing.db.entity.Employee;
import com.infomedia.abacox.telephonypricing.db.repository.EmployeeRepository;
import com.infomedia.abacox.telephonypricing.dto.employee.CreateEmployee;
import com.infomedia.abacox.telephonypricing.dto.employee.UpdateEmployee;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Service
public class EmployeeService extends CrudService<Employee, Long, EmployeeRepository> {

    private final HistoryControlService historyControlService;

    public EmployeeService(EmployeeRepository repository, HistoryControlService historyControlService) {
        super(repository);
        this.historyControlService = historyControlService;
    }

    @Transactional
    public Employee create(CreateEmployee cDto) {
        Employee employee = Employee.builder()
                .name(cDto.getName())
                .subdivisionId(cDto.getSubdivisionId())
                .costCenterId(cDto.getCostCenterId())
                .authCode(cDto.getAuthCode())
                .extension(cDto.getExtension())
                .communicationLocationId(cDto.getCommunicationLocationId())
                .jobPositionId(cDto.getJobPositionId())
                .email(cDto.getEmail())
                .phone(cDto.getPhone())
                .address(cDto.getAddress())
                .idNumber(cDto.getIdNumber())
                .build();

        historyControlService.initHistory(employee);
        return save(employee);
    }

    @Transactional
    public Employee update(Long id, UpdateEmployee uDto) {
        Employee current = get(id);
        Employee updated = current.toBuilder().build();

        uDto.getName().ifPresent(updated::setName);
        uDto.getSubdivisionId().ifPresent(updated::setSubdivisionId);
        uDto.getCostCenterId().ifPresent(updated::setCostCenterId);
        uDto.getAuthCode().ifPresent(updated::setAuthCode);
        uDto.getExtension().ifPresent(updated::setExtension);
        uDto.getCommunicationLocationId().ifPresent(updated::setCommunicationLocationId);
        uDto.getJobPositionId().ifPresent(updated::setJobPositionId);
        uDto.getEmail().ifPresent(updated::setEmail);
        uDto.getPhone().ifPresent(updated::setPhone);
        uDto.getAddress().ifPresent(updated::setAddress);
        uDto.getIdNumber().ifPresent(updated::setIdNumber);

        return historyControlService.processUpdate(
                current,
                updated,
                Map.of("Extension", Employee::getExtension, "Location", Employee::getCommunicationLocationId),
                RefTable.EMPLOYEE,
                getRepository());
    }

    @Transactional
    public void retire(Long id) {
        Employee employee = get(id);
        historyControlService.processRetire(employee, RefTable.EMPLOYEE, getRepository());
    }

    public ByteArrayResource exportExcel(Specification<Employee> specification, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<Employee> collection = find(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}