package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.callcategory.CreateCallCategory;
import com.infomedia.abacox.telephonypricing.dto.callcategory.UpdateCallCategory;
import com.infomedia.abacox.telephonypricing.db.entity.CallCategory;
import com.infomedia.abacox.telephonypricing.repository.CallCategoryRepository;
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

    public ByteArrayResource exportExcel(Specification<CallCategory> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<CallCategory> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}