package com.infomedia.abacox.telephonypricing.component.modeltools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

@Component
public class ModelConverter {

    private ModelMapper modelMapper;
    private final ObjectMapper objectMapper;

    public ModelConverter(ObjectMapper objectMapper){
        this.objectMapper = objectMapper;
        modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setAmbiguityIgnored(true)
                .setFieldMatchingEnabled(true)
                .setFieldAccessLevel(org.modelmapper.config.Configuration.AccessLevel.PRIVATE);
    }

    public <T> T map(Object sourceObject, Class<T> mapType){
        return modelMapper.map(sourceObject, mapType);
    }

    public Map<String, Object> toMap(Object sourceObject){
        TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};
        return objectMapper.convertValue(sourceObject, mapType);
    }

    public <T> T fromMap(Map<String, Object> sourceMap, Class<T> type){
        return objectMapper.convertValue(sourceMap, type);
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
