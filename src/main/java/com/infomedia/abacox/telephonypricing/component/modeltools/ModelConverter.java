package com.infomedia.abacox.telephonypricing.component.modeltools;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelConverter {

    private ModelMapper modelMapper;

    public ModelConverter(){
        modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setAmbiguityIgnored(true)
                .setFieldMatchingEnabled(true)
                .setFieldAccessLevel(org.modelmapper.config.Configuration.AccessLevel.PRIVATE);
    }

    public  <T> T map(Object sourceObject, Class<T> mapType){
        return modelMapper.map(sourceObject, mapType);
    }

    public  <T> List<T> mapList(List<?> sourceList, Class<T> mapType){
        return sourceList
                .stream()
                .map(element -> modelMapper.map(element, mapType))
                .toList();
    }

    public <T>Page<T> mapPage(Page<?> sourcePage, Class<T> mapType){
        return sourcePage.map(element -> modelMapper.map(element, mapType));
    }
}
