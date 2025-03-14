package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.costcenter.CreateCostCenter;
import com.infomedia.abacox.telephonypricing.dto.costcenter.UpdateCostCenter;
import com.infomedia.abacox.telephonypricing.entity.CostCenter;
import com.infomedia.abacox.telephonypricing.repository.CostCenterRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

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
}
