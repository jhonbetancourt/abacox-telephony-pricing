package com.infomedia.abacox.telephonypricing.component.modeltools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;
import lombok.SneakyThrows;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ModelConverter {

    private ModelMapper modelMapper;
    @Getter
    private ObjectMapper objectMapper;

    public ModelConverter(){
        objectMapper = new ObjectMapper();
        modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setAmbiguityIgnored(true)
                .setFieldMatchingEnabled(true)
                .setFieldAccessLevel(org.modelmapper.config.Configuration.AccessLevel.PRIVATE);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.findAndRegisterModules();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
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

    @SneakyThrows
    public <T> T convert(Object sourceObject, Class<T> mapType) {
        if (sourceObject instanceof String str) {
            return objectMapper.readValue(str, mapType);
        }
        return objectMapper.convertValue(sourceObject, mapType);
    }

    @SneakyThrows
    public <T> T convert(Object sourceObject, TypeReference<T> typeReference) {
        if(sourceObject instanceof String str){
            return objectMapper.readValue(str, typeReference);
        }
        return objectMapper.convertValue(sourceObject, typeReference);
    }
}
