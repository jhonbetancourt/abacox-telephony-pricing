package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.employee.CreateEmployee;
import com.infomedia.abacox.telephonypricing.dto.employee.UpdateEmployee;
import com.infomedia.abacox.telephonypricing.entity.Employee;
import com.infomedia.abacox.telephonypricing.repository.EmployeeRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService extends CrudService<Employee, Long, EmployeeRepository> {
    public EmployeeService(EmployeeRepository repository) {
        super(repository);
    }


    public Employee create(CreateEmployee cDto){
        Employee employee = Employee.builder()
                .name(cDto.getName())
                .subdivisionId(cDto.getSubdivisionId())
                .costCenterId(cDto.getCostCenterId())
               // .accessKey(cDto.getAccessKey())
                .extension(cDto.getExtension())
                .communicationLocationId(cDto.getCommunicationLocationId())
                .jobPositionId(cDto.getJobPositionId())
                .email(cDto.getEmail())
                .phone(cDto.getPhone())
                .address(cDto.getAddress())
                .idNumber(cDto.getIdNumber())
                .build();

        return save(employee);
    }

    public Employee update(Long id, UpdateEmployee uDto){
        Employee employee = get(id);
        uDto.getName().ifPresent(employee::setName);
        uDto.getSubdivisionId().ifPresent(employee::setSubdivisionId);
        uDto.getCostCenterId().ifPresent(employee::setCostCenterId);
      //  uDto.getAccessKey().ifPresent(employee::setAccessKey);
        uDto.getExtension().ifPresent(employee::setExtension);
        uDto.getCommunicationLocationId().ifPresent(employee::setCommunicationLocationId);
        uDto.getJobPositionId().ifPresent(employee::setJobPositionId);
        uDto.getEmail().ifPresent(employee::setEmail);
        uDto.getPhone().ifPresent(employee::setPhone);
        uDto.getAddress().ifPresent(employee::setAddress);
        uDto.getIdNumber().ifPresent(employee::setIdNumber);
        return save(employee);
    }
}
