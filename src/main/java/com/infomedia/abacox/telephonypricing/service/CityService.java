package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.dto.city.CreateCity;
import com.infomedia.abacox.telephonypricing.dto.city.UpdateCity;
import com.infomedia.abacox.telephonypricing.entity.City;
import com.infomedia.abacox.telephonypricing.repository.CityRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

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
        return save(city);
    }

}
