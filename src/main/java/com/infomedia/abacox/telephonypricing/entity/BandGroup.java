package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing a band or group.
 * Original table name: BANDAGRUPO
 */
@Entity
@Table(name = "band_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class BandGroup extends AuditedEntity {

    /**
     * Primary key for the band.
     * Original field: BANDAGRUPO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "band_group_id_seq")
    @SequenceGenerator(
            name = "band_group_id_seq",
            sequenceName = "band_group_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the band or group.
     * Original field: BANDAGRUPO_NOMBRE
     */
    @Column(name = "name", length = 120, nullable = false)
    @ColumnDefault("")
    private String name;
}