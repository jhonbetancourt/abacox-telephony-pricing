package com.infomedia.abacox.telephonypricing.component.filtertools;

import com.turkraft.springfilter.converter.FilterSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class SpecificationFilter {
    private static ConversionService conversionService;

    @Autowired
    @Qualifier("sfConversionService")
    public void setConversionService(ConversionService conversionService) {
        SpecificationFilter.conversionService = conversionService;
    }

    public static <T> Specification<T> build(String filter) {
        return conversionService.convert(filter, FilterSpecification.class);
    }
}
