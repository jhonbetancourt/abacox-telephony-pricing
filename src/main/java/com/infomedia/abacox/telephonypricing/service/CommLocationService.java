package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CreateCommLocation;
import com.infomedia.abacox.telephonypricing.dto.commlocation.UpdateCommLocation;
import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.repository.CommunicationLocationRepository;
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
public class CommLocationService extends CrudService<CommunicationLocation, Long, CommunicationLocationRepository> {
    public CommLocationService(CommunicationLocationRepository repository) {
        super(repository);
    }

    public CommunicationLocation create (CreateCommLocation cDto) {
        CommunicationLocation communicationLocation = CommunicationLocation
                .builder()
                .directory(cDto.getDirectory())
                .plantTypeId(cDto.getPlantTypeId())
                .serial(cDto.getSerial())
                .indicatorId(cDto.getIndicatorId())
                .pbxPrefix(cDto.getPbxPrefix())
                .captureDate(cDto.getCaptureDate())
                .cdrCount(cDto.getCdrCount())
                .fileName(cDto.getFileName())
                .headerId(cDto.getHeaderId())
                .build();
        return save(communicationLocation);
    }

    public CommunicationLocation update (Long id, UpdateCommLocation cDto) {
        CommunicationLocation communicationLocation = get(id);
        cDto.getDirectory().ifPresent(communicationLocation::setDirectory);
        cDto.getPlantTypeId().ifPresent(communicationLocation::setPlantTypeId);
        cDto.getSerial().ifPresent(communicationLocation::setSerial);
        cDto.getIndicatorId().ifPresent(communicationLocation::setIndicatorId);
        cDto.getPbxPrefix().ifPresent(communicationLocation::setPbxPrefix);
        cDto.getCaptureDate().ifPresent(communicationLocation::setCaptureDate);
        cDto.getCdrCount().ifPresent(communicationLocation::setCdrCount);
        cDto.getFileName().ifPresent(communicationLocation::setFileName);
        cDto.getHeaderId().ifPresent(communicationLocation::setHeaderId);
        return save(communicationLocation);
    }

    public ByteArrayResource exportExcel(Specification<CommunicationLocation> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<CommunicationLocation> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}