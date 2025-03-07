package com.infomedia.abacox.telephonypricing.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ResourceDeletionException extends RuntimeException{
    private Class<?> entityClass;
    private String id;

    public ResourceDeletionException(Class<?> entityClass, Object id, Throwable cause){
        super("Resource of type "+entityClass.getSimpleName()+" with id "+getIdString(id)+" could not be deleted", cause);
        this.entityClass = entityClass;
        this.id = getIdString(id);
    }

    public ResourceDeletionException(Class<?> entityClass, Throwable cause){
        super("Resource of type "+entityClass.getSimpleName()+" could not be deleted", cause);
        this.entityClass = entityClass;
    }

    private static String getIdString(Object id){
        return id == null ? "null" : id.toString();
    }
}
