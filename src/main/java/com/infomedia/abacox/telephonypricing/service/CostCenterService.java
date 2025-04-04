package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.costcenter.CreateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.UpdateCostCenter;
import com.infomedia.abacox.telephonypricing.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.repository.CostCenterRepository;
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
public class CostCenterService extends CrudService<CostCenter,Long,CostCenterRepository> {
    public CostCenterService(CostCenterRepository repository) {
        super(repository);
    }

    public CostCenter create(CreateCostCenter uDto){
        CostCenter costCenter = CostCenter.builder()
                .name(uDto.getName())
                .workOrder(uDto.getWorkOrder())
                .parentCostCenterId(uDto.getParentCostCenterId())
                .originCountryId(uDto.getOriginCountryId())
                .build();

        return save(costCenter);
    }

    public CostCenter update(Long id, UpdateCostCenter uDto){
        CostCenter costCenter = get(id);
        uDto.getName().ifPresent(costCenter::setName);
        uDto.getWorkOrder().ifPresent(costCenter::setWorkOrder);
        uDto.getParentCostCenterId().ifPresent(costCenter::setParentCostCenterId);
        uDto.getOriginCountryId().ifPresent(costCenter::setOriginCountryId);
        return save(costCenter);
    }

    public ByteArrayResource exportExcel(Specification<CostCenter> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<CostCenter> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}