package com.infomedia.abacox.telephonypricing.exception;


import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ResourceAlreadyExistsException extends RuntimeException{
    private Class<?> entityClass;
    private String id;

    public ResourceAlreadyExistsException(Class<?> entityClass, Object id){
        super("Resource of type "+entityClass.getSimpleName()+" with id "+getIdString(id)+" already exists");
        this.entityClass = entityClass;
        this.id = getIdString(id);
    }

    public ResourceAlreadyExistsException(Class<?> entityClass){
        super("Resource of type "+entityClass.getSimpleName()+" already exists");
        this.entityClass = entityClass;
    }

    private static String getIdString(Object id){
        return id == null ? "null" : id.toString();
    }
}
