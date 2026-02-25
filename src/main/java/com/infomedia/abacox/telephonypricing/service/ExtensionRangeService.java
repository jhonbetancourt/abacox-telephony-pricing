package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.constants.RefTable;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.repository.ExtensionRangeRepository;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.CreateExtensionRange;
import com.infomedia.abacox.telephonypricing.dto.extensionrange.UpdateExtensionRange;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class ExtensionRangeService extends CrudService<ExtensionRange, Long, ExtensionRangeRepository> {

    private final HistoryControlService historyControlService;

    public ExtensionRangeService(ExtensionRangeRepository repository, HistoryControlService historyControlService) {
        super(repository);
        this.historyControlService = historyControlService;
    }

    @Transactional
    public ExtensionRange create(CreateExtensionRange cDto) {
        ExtensionRange extensionRange = ExtensionRange.builder()
                .commLocationId(cDto.getCommLocationId())
                .subdivisionId(cDto.getSubdivisionId())
                .prefix(cDto.getPrefix())
                .rangeStart(cDto.getRangeStart())
                .rangeEnd(cDto.getRangeEnd())
                .costCenterId(cDto.getCostCenterId())
                .build();

        historyControlService.initHistory(extensionRange);
        return save(extensionRange);
    }

    @Transactional
    public ExtensionRange update(Long id, UpdateExtensionRange uDto) {
        ExtensionRange current = get(id);
        ExtensionRange updated = current.toBuilder().build();

        uDto.getCommLocationId().ifPresent(updated::setCommLocationId);
        uDto.getSubdivisionId().ifPresent(updated::setSubdivisionId);
        uDto.getPrefix().ifPresent(updated::setPrefix);
        uDto.getRangeStart().ifPresent(updated::setRangeStart);
        uDto.getRangeEnd().ifPresent(updated::setRangeEnd);
        uDto.getCostCenterId().ifPresent(updated::setCostCenterId);

        return historyControlService.processUpdate(
                current,
                updated,
                List.of(ExtensionRange::getRangeStart, ExtensionRange::getCommLocationId),
                RefTable.EXTENSION_RANGE,
                getRepository());
    }

    @Transactional
    public void retire(Long id) {
        ExtensionRange extensionRange = get(id);
        historyControlService.processRetire(extensionRange, RefTable.EXTENSION_RANGE, getRepository());
    }

    public ByteArrayResource exportExcel(Specification<ExtensionRange> specification, Pageable pageable,
            ExcelGeneratorBuilder builder) {
        Page<ExtensionRange> collection = find(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
