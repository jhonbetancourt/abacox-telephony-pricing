package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing communication location/installation details.
 * Original table name: COMUBICACION
 */
@Entity
@Table(name = "communication_location")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CommunicationLocation extends ActivableEntity {

    /**
     * Primary key for the communication location.
     * Original field: COMUBICACION_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "communication_location_id_seq")
    @SequenceGenerator(
            name = "communication_location_id_seq",
            sequenceName = "communication_location_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Directory/location name.
     * Original field: COMUBICACION_DIRECTORIO
     */
    @Column(name = "directory", length = 80, nullable = false)
    @ColumnDefault("")
    private String directory;

    /**
     * ID of the plant type.
     * Original field: COMUBICACION_TIPOPLANTA_ID
     */
    @Column(name = "plant_type_id")
    private Long plantTypeId;

    /**
     * Plant type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "plant_type_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_communication_location_plant_type")
    )
    private PlantType plantType;

    /**
     * Serial number of the installation.
     * Original field: COMUBICACION_SERIAL
     */
    @Column(name = "serial", length = 20, nullable = false)
    @ColumnDefault("")
    private String serial;

    /**
     * ID of the indicator/area code.
     * Original field: COMUBICACION_INDICATIVO_ID
     */
    @Column(name = "indicator_id")
    private Long indicatorId;

    /**
     * Indicator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "indicator_id", 
            insertable = false, 
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_communication_location_indicator")
    )
    private Indicator indicator;

    /**
     * PBX prefix.
     * Original field: COMUBICACION_PREFIJOPBX
     */
    @Column(name = "pbx_prefix", length = 32, nullable = false)
    @ColumnDefault("")
    private String pbxPrefix;

    /**
     * Date of capture/recording.
     * Original field: COMUBICACION_FECHACAPTURA
     * Note: This field was nullable in the original schema.
     */
    @Column(name = "capture_date")
    private LocalDateTime captureDate;

    /**
     * Number of Call Detail Records (CDRs).
     * Original field: COMUBICACION_CDRS
     */
    @Column(name = "cdr_count", nullable = false)
    @ColumnDefault("0")
    private Integer cdrCount;

    /**
     * File name or path.
     * Original field: COMUBICACION_ARCHIVO
     */
    @Column(name = "file_name", length = 80, nullable = false)
    @ColumnDefault("")
    private String fileName;

    /**
     * ID of the header.
     * Original field: COMUBICACION_CABECERA_ID
     */
    @Column(name = "header_id", nullable = true)
    private Long headerId;
}