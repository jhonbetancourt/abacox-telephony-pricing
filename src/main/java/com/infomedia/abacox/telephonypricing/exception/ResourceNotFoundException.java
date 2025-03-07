package com.infomedia.abacox.telephonypricing.exception;


import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ResourceNotFoundException extends RuntimeException{
    private Class<?> entityClass;
    private String id;

    public ResourceNotFoundException(Class<?> entityClass, Object id){
        super("Resource of type "+entityClass.getSimpleName()+" with id "+getIdString(id)+" not found");
        this.entityClass = entityClass;
        this.id = getIdString(id);
    }

    public ResourceNotFoundException(Class<?> entityClass){
        super("Resource of type "+entityClass.getSimpleName()+" not found");
        this.entityClass = entityClass;
    }

    public ResourceNotFoundException(Class<?> entityClass, String message){
        super("Resource of type "+entityClass.getSimpleName()+" not found: "+message);
        this.entityClass = entityClass;
    }

    private static String getIdString(Object id){
        return id == null ? "null" : id.toString();
    }
}
