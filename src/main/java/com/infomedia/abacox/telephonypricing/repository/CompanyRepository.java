package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.dto.company.CreateCompany;
import com.infomedia.abacox.telephonypricing.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CompanyRepository extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {

  public Company create(CreateCompany cDto) {
    Company company = Company.builder()
        .name(cDto.getName())
        .address(cDto.getAddress())
        .phone(cDto.getPhone())
        .email(cDto.getEmail())
        .website(cDto.getWebsite())
        .build();

    return save(company);
  }
}