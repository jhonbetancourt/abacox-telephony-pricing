package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing CDR load control information.
 * Original table name: cargactl
 */
@Entity
@Table(name = "cdr_load_control")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CdrLoadControl extends ActivableEntity {

    /**
     * Primary key for CDR load control.
     * Original field: CARGACTL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cdr_load_control_id_seq")
    @SequenceGenerator(name = "cdr_load_control_id_seq", sequenceName = "cdr_load_control_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Directory or load identifier name.
     * Original field: CARGACTL_DIRECTORIO
     */
    @Column(name = "name", length = 64, nullable = false, unique = true)
    @ColumnDefault("''")
    private String name;

    /**
     * ID of the associated plant type.
     * Original field: CARGACTL_TIPOPLANTA_ID
     */
    @Column(name = "plant_type_id", nullable = false)
    @ColumnDefault("0")
    private Integer plantTypeId;
}
