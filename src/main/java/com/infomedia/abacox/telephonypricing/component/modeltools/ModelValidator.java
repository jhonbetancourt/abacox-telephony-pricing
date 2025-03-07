package com.infomedia.abacox.telephonypricing.component.modeltools;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ModelValidator {
    private final Validator javaxValidator;

    public ModelValidator() {
        this.javaxValidator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    public <T> T validate(T t){
        if(t!=null){
            Set<ConstraintViolation<T>> violations = javaxValidator.validate(t);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
            return t;
        }
        return null;
    }
}
