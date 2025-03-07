package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
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
            initialValue = 1000000
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
    @Column(name = "plant_type_id", nullable = false)
    @ColumnDefault("0")
    private Long plantTypeId;

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
    @Column(name = "indicator_id", nullable = false)
    @ColumnDefault("0")
    private Long indicatorId;

    /**
     * PBX prefix.
     * Original field: COMUBICACION_PREFIJOPBX
     */
    @Column(name = "pbx_prefix", length = 32, nullable = false)
    @ColumnDefault("")
    private String pbxPrefix;

    /**
     * Address information.
     * Original field: COMUBICACION_DIRECCION
     * Note: The original schema noted this is used in BBVA.
     */
    @Column(name = "address", length = 100, nullable = false)
    @ColumnDefault("")
    private String address;

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
     * ID of the band group.
     * Original field: COMUBICACION_BANDAGRUPO_ID
     */
    @Column(name = "band_group_id", nullable = false)
    @ColumnDefault("0")
    private Long bandGroupId;

    /**
     * ID of the header.
     * Original field: COMUBICACION_CABECERA_ID
     */
    @Column(name = "header_id", nullable = false)
    @ColumnDefault("0")
    private Long headerId;

    /**
     * Without captures flag.
     * Original field: COMUBICACION_SINCAPTURAS
     */
    @Column(name = "without_captures", nullable = false)
    @ColumnDefault("0")
    private Integer withoutCaptures;

    // The following fields were commented out in the original schema:
    
    /**
     * Internal prefix (commented out in original schema).
     * Original field: COMUBICACION_PREFIJOINTERNO
     */
    // @Column(name = "internal_prefix", length = 32, nullable = false)
    // @ColumnDefault("''")
    // private String internalPrefix;

    /**
     * Revision date (commented out in original schema).
     * Original field: COMUBICACION_FECHAREVISION
     */
    // @Column(name = "revision_date")
    // private LocalDateTime revisionDate;

    /**
     * Responsible person (commented out in original schema).
     * Original field: COMUBICACION_RESPONSABLE
     */
    // @Column(name = "responsible_person", length = 80, nullable = false)
    // @ColumnDefault("''")
    // private String responsiblePerson;

    /**
     * Contact telephone (commented out in original schema).
     * Original field: COMUBICACION_TELEFONO
     */
    // @Column(name = "telephone", length = 20, nullable = false)
    // @ColumnDefault("''")
    // private String telephone;

    /**
     * Message date (commented out in original schema).
     * Original field: COMUBICACION_FMENSAJE
     */
    // @Column(name = "message_date")
    // private LocalDateTime messageDate;

    /**
     * In transit flag (commented out in original schema).
     * Original field: COMUBICACION_DEPASO
     */
    // @Column(name = "in_transit", nullable = false)
    // @ColumnDefault("false")
    // private boolean inTransit;
}