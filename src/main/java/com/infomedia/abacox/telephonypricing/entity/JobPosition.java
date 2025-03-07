package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;

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
public class JobPosition extends AuditedEntity {

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
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Integer id;

    /**
     * Name of the job position.
     * Original field: FUNCARGO_NOMBRE
     */
    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("")
    private String name;
}