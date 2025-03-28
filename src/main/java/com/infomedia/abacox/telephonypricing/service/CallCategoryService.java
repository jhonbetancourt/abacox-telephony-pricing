package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.callcategory.CreateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.callcategory.UpdateCallCategory;
import com.infomedia.abacox.telephonypricing.entity.CallCategory;
import com.infomedia.abacox.telephonypricing.repository.CallCategoryRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class CallCategoryService extends CrudService<CallCategory, Long, CallCategoryRepository> {
    public CallCategoryService(CallCategoryRepository repository) {
        super(repository);
    }


    public CallCategory create(CreateCallCategory cDto) {
        CallCategory callCategory = CallCategory.builder()
                .name(cDto.getName())
                .build();
        return save(callCategory);
    }

    public CallCategory update(Long id, UpdateCallCategory uDto) {
        CallCategory callCategory = get(id);
        uDto.getName().ifPresent(callCategory::setName);
        return save(callCategory);
    }
}
