package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.dto.company.CreateCompany;
import com.infomedia.abacox.telephonypricing.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CompanyRepository extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {

}