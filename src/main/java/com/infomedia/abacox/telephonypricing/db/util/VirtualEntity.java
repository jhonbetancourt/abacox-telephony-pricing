// src/main/java/com/example/model/utility/VirtualEntity.java
package com.infomedia.abacox.telephonypricing.db.util;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

/**
 * This is a "dummy" or "virtual" entity that does not map to a real database table.
 * Its purpose is to serve as a placeholder for a repository that can house
 * complex, cross-entity, or DTO-based queries.
 *
 * The @Subselect annotation makes Hibernate treat the result of the given query
 * as the data source, and @Immutable marks it as read-only.
 */
@Entity
@Immutable
@Subselect("select -1 as id") // A trivial query to satisfy the entity mapping
public class VirtualEntity {

    @Id
    private Long id;
}