package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing types of plants or facilities.
 * Original table name: tipoplanta
 */
@Entity
@Table(name = "plant_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class PlantType extends ActivableEntity {

    /**
     * Primary key for the plant type.
     * Original field: TIPOPLANTA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plant_type_id_seq")
    @SequenceGenerator(
            name = "plant_type_id_seq",
            sequenceName = "plant_type_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the plant type.
     * Original field: TIPOPLANTA_NOMBRE
     */
    @Column(name = "name", length = 40, nullable = false)
    @ColumnDefault("''")
    private String name;
}