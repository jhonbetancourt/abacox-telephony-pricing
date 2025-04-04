package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.CreateBandGroup;
import com.infomedia.abacox.telephonypricing.dto.bandgroup.UpdateBandGroup;
import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import com.infomedia.abacox.telephonypricing.repository.BandGroupRepository;
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
public class BandGroupService extends CrudService<BandGroup, Long, BandGroupRepository> {
    public BandGroupService(BandGroupRepository repository) {
        super(repository);
    }

    public BandGroup create(CreateBandGroup cDto) {
        BandGroup bandGroup = BandGroup
                .builder()
                .name(cDto.getName())
                .build();
        return save(bandGroup);
    }

    public BandGroup update(Long id, UpdateBandGroup uDto) {
        BandGroup bandGroup = get(id);
        uDto.getName().ifPresent(bandGroup::setName);
        return save(bandGroup);
    }

    public ByteArrayResource exportExcel(Specification<BandGroup> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<BandGroup> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}