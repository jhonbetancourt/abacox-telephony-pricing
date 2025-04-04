package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.commlocation.CreateCommLocation;
import com.infomedia.abacox.telephonypricing.dto.commlocation.UpdateCommLocation;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
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
                .address(cDto.getAddress())
                .captureDate(cDto.getCaptureDate())
                .cdrCount(cDto.getCdrCount())
                .fileName(cDto.getFileName())
                .bandGroupId(cDto.getBandGroupId())
                .headerId(cDto.getHeaderId())
                .withoutCaptures(cDto.getWithoutCaptures())
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
        cDto.getAddress().ifPresent(communicationLocation::setAddress);
        cDto.getCaptureDate().ifPresent(communicationLocation::setCaptureDate);
        cDto.getCdrCount().ifPresent(communicationLocation::setCdrCount);
        cDto.getFileName().ifPresent(communicationLocation::setFileName);
        cDto.getBandGroupId().ifPresent(communicationLocation::setBandGroupId);
        cDto.getHeaderId().ifPresent(communicationLocation::setHeaderId);
        cDto.getWithoutCaptures().ifPresent(communicationLocation::setWithoutCaptures);
        return save(communicationLocation);
    }

    public ByteArrayResource exportExcel(Specification<CommunicationLocation> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<CommunicationLocation> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList()
                    , Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}