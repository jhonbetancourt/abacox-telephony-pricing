package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing cities/municipalities.
 * Original table name: CIUDADES
 */
@Entity
@Table(name = "city")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class City extends ActivableEntity {

    /**
     * Primary key for the city.
     * Original field: CIUDADES_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "city_id_seq")
    @SequenceGenerator(
            name = "city_id_seq",
            sequenceName = "city_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Department or state name.
     * Original field: CIUDADES_DEPARTAMENTO
     */
    @Column(name = "department", length = 80, nullable = false)
    @ColumnDefault("")
    private String department;

    /**
     * Classification or category of the city.
     * Original field: CIUDADES_CLASIFICACION
     */
    @Column(name = "classification", length = 80, nullable = false)
    @ColumnDefault("")
    private String classification;

    /**
     * Municipality name.
     * Original field: CIUDADES_MUNICIPIO
     */
    @Column(name = "municipality", length = 80, nullable = false)
    @ColumnDefault("")
    private String municipality;

    /**
     * Municipal capital name.
     * Original field: CIUDADES_CABMUNICIPAL
     */
    @Column(name = "municipal_capital", length = 80, nullable = false)
    @ColumnDefault("")
    private String municipalCapital;

    /**
     * Latitude coordinate.
     * Original field: CIUDADES_LATITUD
     */
    @Column(name = "latitude", length = 15, nullable = false)
    @ColumnDefault("")
    private String latitude;

    /**
     * Longitude coordinate.
     * Original field: CIUDADES_LONGITUD
     */
    @Column(name = "longitude", length = 15, nullable = false)
    @ColumnDefault("")
    private String longitude;

    /**
     * Altitude in meters.
     * Original field: CIUDADES_ALTITUD
     */
    @Column(name = "altitude", nullable = false)
    @ColumnDefault("0")
    private Integer altitude;

    /**
     * Northern coordinate (possibly in a local coordinate system).
     * Original field: CIUDADES_NORTE
     */
    @Column(name = "north_coordinate", nullable = false)
    @ColumnDefault("0")
    private Integer northCoordinate;

    /**
     * Eastern coordinate (possibly in a local coordinate system).
     * Original field: CIUDADES_ESTE
     */
    @Column(name = "east_coordinate", nullable = false)
    @ColumnDefault("0")
    private Integer eastCoordinate;

    /**
     * Origin or source of the city data.
     * Original field: CIUDADES_ORIGEN
     */
    @Column(name = "origin", length = 50, nullable = false)
    @ColumnDefault("")
    private String origin;
}