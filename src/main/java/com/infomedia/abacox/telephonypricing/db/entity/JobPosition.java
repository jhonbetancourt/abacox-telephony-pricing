package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing job positions or roles.
 * Original table name: funcargo
 */
@Entity
@Table(name = "job_position")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class JobPosition extends ActivableEntity {

    /**
     * Primary key for the job position.
     * Original field: FUNCARGO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "job_position_id_seq")
    @SequenceGenerator(
            name = "job_position_id_seq",
            sequenceName = "job_position_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the job position.
     * Original field: FUNCARGO_NOMBRE
     */
    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("")
    private String name;
}