package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * Entity representing extension ranges.
 * Original table name: rangoext
 */
@Entity
@Table(name = "extension_range")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class ExtensionRange extends AuditedEntity {

    /**
     * Primary key for the extension range.
     * Original field: RANGOEXT_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "extension_range_id_seq")
    @SequenceGenerator(
            name = "extension_range_id_seq",
            sequenceName = "extension_range_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the communication location.
     * Original field: RANGOEXT_COMUBICACION_ID
     */
    @Column(name = "comm_location_id", nullable = false)
    @ColumnDefault("0")
    private Long commLocationId;

    /**
     * Communication location relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "comm_location_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_comm_location")
    )
    private CommunicationLocation commLocation;

    /**
     * ID of the subdivision.
     * Original field: RANGOEXT_SUBDIRECCION_ID
     */
    @Column(name = "subdivision_id", nullable = false)
    @ColumnDefault("0")
    private Long subdivisionId;

    /**
     * Subdivision relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "subdivision_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_subdivision")
    )
    private Subdivision subdivision;

    /**
     * Prefix for the extension range.
     * Original field: RANGOEXT_PREFIJO
     */
    @Column(name = "prefix", length = 250, nullable = false)
    @ColumnDefault("''")
    private String prefix;

    /**
     * Starting extension in the range.
     * Original field: RANGOEXT_DESDE
     */
    @Column(name = "range_start", length = 50, nullable = false)
    @ColumnDefault("''")
    private String rangeStart;

    /**
     * Ending extension in the range.
     * Original field: RANGOEXT_HASTA
     */
    @Column(name = "range_end", length = 50, nullable = false)
    @ColumnDefault("''")
    private String rangeEnd;

    /**
     * ID of the cost center.
     * Original field: RANGOEXT_CENTROCOSTOS_ID
     */
    @Column(name = "cost_center_id", nullable = false)
    @ColumnDefault("0")
    private Long costCenterId;

    /**
     * Cost center relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "cost_center_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_extension_range_cost_center")
    )
    private CostCenter costCenter;
}