package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.CreateExtensionRange;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.UpdateExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.repository.ExtensionRangeRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class ExtensionRangeService extends CrudService<ExtensionRange, Long, ExtensionRangeRepository> {

    public ExtensionRangeService(ExtensionRangeRepository repository) {
        super(repository);
    }

    public ExtensionRange create(CreateExtensionRange cDto){
        ExtensionRange extensionRange = ExtensionRange.builder()
                .commLocationId(cDto.getCommLocationId())
                .subdivisionId(cDto.getSubdivisionId())
                .prefix(cDto.getPrefix())
                .rangeStart(cDto.getRangeStart())
                .rangeEnd(cDto.getRangeEnd())
                .costCenterId(cDto.getCostCenterId())
                .build();

        return save(extensionRange);
    }

    public ExtensionRange update(Long id, UpdateExtensionRange uDto){
        ExtensionRange extensionRange = get(id);

        uDto.getCommLocationId().ifPresent(extensionRange::setCommLocationId);
        uDto.getSubdivisionId().ifPresent(extensionRange::setSubdivisionId);
        uDto.getPrefix().ifPresent(extensionRange::setPrefix);
        uDto.getRangeStart().ifPresent(extensionRange::setRangeStart);
        uDto.getRangeEnd().ifPresent(extensionRange::setRangeEnd);
        uDto.getCostCenterId().ifPresent(extensionRange::setCostCenterId);
        return save(extensionRange);
    }

    public ByteArrayResource exportExcel(Specification<ExtensionRange> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<ExtensionRange> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
