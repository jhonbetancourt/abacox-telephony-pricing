package com.infomedia.abacox.telephonypricing.exception;


import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ResourceDisabledException extends RuntimeException{
    private Class<?> entityClass;
    private String id;

    public ResourceDisabledException(Class<?> entityClass, Object id){
        super("Resource of type "+entityClass.getSimpleName()+" with id "+getIdString(id)+" is not active");
        this.entityClass = entityClass;
        this.id = getIdString(id);
    }

    public ResourceDisabledException(Class<?> entityClass){
        super("Resource of type "+entityClass.getSimpleName()+" is not active");
        this.entityClass = entityClass;
    }

    private static String getIdString(Object id){
        return id == null ? "null" : id.toString();
    }
}
