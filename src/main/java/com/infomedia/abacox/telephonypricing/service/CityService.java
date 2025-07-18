package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.dto.city.CreateCity;
import com.infomedia.abacox.telephonypricing.dto.city.UpdateCity;
import com.infomedia.abacox.telephonypricing.db.entity.City;
import com.infomedia.abacox.telephonypricing.db.repository.CityRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class CityService extends CrudService<City, Long, CityRepository> {
    public CityService(CityRepository repository) {
        super(repository);
    }

    public City create(CreateCity cDto) {
        City city = City.builder()
                .department(cDto.getDepartment())
                .classification(cDto.getClassification())
                .municipality(cDto.getMunicipality())
                .municipalCapital(cDto.getMunicipalCapital())
                .latitude(cDto.getLatitude())
                .longitude(cDto.getLongitude())
                .altitude(cDto.getAltitude())
                .northCoordinate(cDto.getNorthCoordinate())
                .eastCoordinate(cDto.getEastCoordinate())
                .origin(cDto.getOrigin())
                .build();

        return save(city);
    }

    public City update(Long id, UpdateCity uDto) {
        City city = get(id);
        uDto.getDepartment().ifPresent(city::setDepartment);
        uDto.getClassification().ifPresent(city::setClassification);
        uDto.getMunicipality().ifPresent(city::setMunicipality);
        uDto.getMunicipalCapital().ifPresent(city::setMunicipalCapital);
        uDto.getLatitude().ifPresent(city::setLatitude);
        uDto.getLongitude().ifPresent(city::setLongitude);
        uDto.getAltitude().ifPresent(city::setAltitude);
        uDto.getNorthCoordinate().ifPresent(city::setNorthCoordinate);
        uDto.getEastCoordinate().ifPresent(city::setEastCoordinate);
        uDto.getOrigin().ifPresent(city::setOrigin);
        return save(city);
    }

    public ByteArrayResource exportExcel(Specification<City> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<City> collection = find(specification, pageable);
       try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}