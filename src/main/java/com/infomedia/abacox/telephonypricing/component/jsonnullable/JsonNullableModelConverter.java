package com.infomedia.abacox.telephonypricing.component.jsonnullable;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.SimpleType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Iterator;

@Component
public class JsonNullableModelConverter implements ModelConverter {
	@Override
	public Schema<?> resolve(AnnotatedType annotatedType, ModelConverterContext modelConverterContext,
										Iterator<ModelConverter> iterator) {
		if (annotatedType.getType() instanceof SimpleType) {
			SimpleType type = (SimpleType) annotatedType.getType();
			if(type.getRawClass().getName().equals("org.openapitools.jackson.nullable.JsonNullable")){
				JavaType javaType = type.getBindings().getBoundType(0);
				annotatedType.setType(javaType.getRawClass());
				return this.resolve(annotatedType, modelConverterContext, iterator);
			}
		}
		else if (annotatedType.getType() instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) annotatedType.getType();
			Type type = parameterizedType.getActualTypeArguments()[0];
			if (type instanceof WildcardType) {
				WildcardType wildcardType = (WildcardType) type;
				if (Object.class.equals(wildcardType.getUpperBounds()[0])) {
					annotatedType.setType(parameterizedType.getRawType());
					return this.resolve(annotatedType, modelConverterContext, iterator);
				}
			}
		}
		if (iterator.hasNext()) {
			return iterator.next().resolve(annotatedType, modelConverterContext, iterator);
		}
		else {
			return null;
		}
	}
}