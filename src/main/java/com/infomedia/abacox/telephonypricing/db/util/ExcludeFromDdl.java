package com.infomedia.abacox.telephonypricing.db.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A marker annotation for JPA entities.
 * Any entity marked with this annotation will be excluded from
 * Hibernate's DDL auto-generation (create, update, drop).
 */
@Target(ElementType.TYPE) // Can only be applied to classes/interfaces/enums
@Retention(RetentionPolicy.RUNTIME) // Available at runtime for reflection
public @interface ExcludeFromDdl {
}