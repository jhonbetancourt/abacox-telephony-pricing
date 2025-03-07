package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing telephone indicators/area codes.
 * Original table name: INDICATIVO
 */
@Entity
@Table(name = "indicator")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Indicator extends AuditedEntity {

    /**
     * Primary key for the indicator.
     * Original field: INDICATIVO_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "indicator_id_seq")
    @SequenceGenerator(
            name = "indicator_id_seq",
            sequenceName = "indicator_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the telephone type.
     * Original field: INDICATIVO_TIPOTELE_ID
     */
    //TODO: This should be a foreign key to the TelephoneType entity
    @Column(name = "telephone_type_id", nullable = false)
    @ColumnDefault("0")
    private Long telephoneTypeId;

    /**
     * The actual indicator/area code value.
     * Original field: INDICATIVO_INDICATIVO
     * Note: This field was commented in the original schema with a semicolon.
     */
    @Column(name = "code", nullable = false)
    @ColumnDefault("0")
    private Integer code;

    /**
     * Department or country associated with this indicator.
     * Original field: INDICATIVO_DPTO_PAIS
     */
    @Column(name = "department_country", length = 80, nullable = false)
    @ColumnDefault("")
    private String departmentCountry;

    /**
     * City associated with this indicator.
     * Original field: INDICATIVO_CIUDAD
     */
    @Column(name = "city", length = 80, nullable = false)
    @ColumnDefault("")
    private String city;

    /**
     * ID reference to a cities table.
     * Original field: INDICATIVO_CIUDADES_ID
     */
    //TODO: This should be a foreign key to the Cities entity
    @Column(name = "city_id", nullable = false)
    @ColumnDefault("0")
    private Long cityId;

    /**
     * Flag indicating if this indicator is associated with a partner/associate.
     * Original field: INDICATIVO_ASOCIADO
     */
    @Column(name = "is_associated", nullable = false)
    @ColumnDefault("false")
    private boolean isAssociated;

    /**
     * ID of the telecom operator.
     * Original field: INDICATIVO_OPERADOR_ID
     */
    //TODO: This should be a foreign key to the Operator entity
    @Column(name = "operator_id", nullable = false)
    @ColumnDefault("0")
    private Long operatorId;

    /**
     * ID of the origin municipality.
     * Original field: INDICATIVO_MPORIGEN_ID
     */
    //TODO: This should be a foreign key to the OriginCountry entity
    @Column(name = "origin_country_id", nullable = false)
    @ColumnDefault("1")
    private Long originCountryId;
}