package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.contact.CreateContact;
import com.infomedia.abacox.telephonypricing.dto.contact.UpdateContact;
import com.infomedia.abacox.telephonypricing.db.entity.Contact;
import com.infomedia.abacox.telephonypricing.repository.ContactRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Service
public class ContactService extends CrudService<Contact, Long, ContactRepository> {
    public ContactService(ContactRepository repository) {
        super(repository);
    }

    public Contact create(CreateContact cDto) {
        Contact contact = Contact
                .builder()
                .contactType(cDto.getContactType())
                .employeeId(cDto.getEmployeeId())
                .companyId(cDto.getCompanyId())
                .phoneNumber(cDto.getPhoneNumber())
                .name(cDto.getName())
                .description(cDto.getDescription())
                .indicatorId(cDto.getIndicatorId())
                .build();
        return save(contact);
    }

    public Contact update(Long id, UpdateContact uDto){
        Contact contact = get(id);
        uDto.getContactType().ifPresent(contact::setContactType);
        uDto.getEmployeeId().ifPresent(contact::setEmployeeId);
        uDto.getCompanyId().ifPresent(contact::setCompanyId);
        uDto.getPhoneNumber().ifPresent(contact::setPhoneNumber);
        uDto.getName().ifPresent(contact::setName);
        uDto.getDescription().ifPresent(contact::setDescription);
        uDto.getIndicatorId().ifPresent(contact::setIndicatorId);
        return save(contact);
    }

    public ByteArrayResource exportExcel(Specification<Contact> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<Contact> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}