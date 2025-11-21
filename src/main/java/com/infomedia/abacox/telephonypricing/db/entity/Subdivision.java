// src/main/java/com/infomedia/abacox/telephonypricing/db/entity/Subdivision.java
package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
    name = "subdivision",
    indexes = {
        @Index(name = "idx_subdivision_parent", columnList = "parent_subdivision_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Subdivision extends ActivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subdivision_id_seq")
    @SequenceGenerator(
            name = "subdivision_id_seq",
            sequenceName = "subdivision_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "parent_subdivision_id")
    private Long parentSubdivisionId;

    @ManyToOne
    @JoinColumn(
            name = "parent_subdivision_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_subdivision_parent")
    )
    private Subdivision parentSubdivision;

    @Column(name = "name", length = 200, nullable = false)
    @ColumnDefault("''")
    private String name;
}