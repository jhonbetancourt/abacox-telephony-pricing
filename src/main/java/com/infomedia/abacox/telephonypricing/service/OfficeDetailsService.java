package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.officedetails.CreateOfficeDetails;
import com.infomedia.abacox.telephonypricing.dto.officedetails.UpdateOfficeDetails;
import com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails;
import com.infomedia.abacox.telephonypricing.repository.OfficeDetailsRepository;
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
public class OfficeDetailsService extends CrudService<OfficeDetails, Long, OfficeDetailsRepository> {
    public OfficeDetailsService(OfficeDetailsRepository repository) {
        super(repository);
    }

    public OfficeDetails create(CreateOfficeDetails cDto) {
        OfficeDetails officeDetails = OfficeDetails.builder()
                .subdivisionId(cDto.getSubdivisionId())
                .address(cDto.getAddress())
                .phone(cDto.getPhone())
                .indicatorId(cDto.getIndicatorId())
                .build();

        return save(officeDetails);
    }

    public OfficeDetails update(Long id, UpdateOfficeDetails uDto) {
        OfficeDetails officeDetails = get(id);
        uDto.getSubdivisionId().ifPresent(officeDetails::setSubdivisionId);
        uDto.getAddress().ifPresent(officeDetails::setAddress);
        uDto.getPhone().ifPresent(officeDetails::setPhone);
        uDto.getIndicatorId().ifPresent(officeDetails::setIndicatorId);
        return save(officeDetails);
    }

    public ByteArrayResource exportExcel(Specification<OfficeDetails> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<OfficeDetails> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList(),
                    Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}