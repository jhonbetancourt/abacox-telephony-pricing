package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.company.CreateCompany;
import com.infomedia.abacox.telephonypricing.dto.company.UpdateCompany;
import com.infomedia.abacox.telephonypricing.db.entity.Company;
import com.infomedia.abacox.telephonypricing.repository.CompanyRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class CompanyService extends CrudService<Company, Long, CompanyRepository> {
    public CompanyService(CompanyRepository repository) {
        super(repository);
    }

    public Company create(CreateCompany cDto) {
        Company company = Company.builder()
                .name(cDto.getName())
                .address(cDto.getAddress())
                .additionalInfo(cDto.getAdditionalInfo())
                .legalName(cDto.getLegalName())
                .taxId(cDto.getTaxId())
                .website(cDto.getWebsite())
                .indicatorId(cDto.getIndicatorId())
                .build();
        return save(company);
    }

    public Company update(Long id, UpdateCompany uDto) {
        Company company = get(id);
        uDto.getName().ifPresent(company::setName);
        uDto.getAddress().ifPresent(company::setAddress);
        uDto.getAdditionalInfo().ifPresent(company::setAdditionalInfo);
        uDto.getLegalName().ifPresent(company::setLegalName);
        uDto.getTaxId().ifPresent(company::setTaxId);
        uDto.getWebsite().ifPresent(company::setWebsite);
        uDto.getIndicatorId().ifPresent(company::setIndicatorId);
        return save(company);
    }

    public ByteArrayResource exportExcel(Specification<Company> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<Company> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}