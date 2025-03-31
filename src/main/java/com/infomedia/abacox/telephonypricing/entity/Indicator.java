package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.Set;

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
public class Indicator extends ActivableEntity {

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
    @Column(name = "telephony_type_id", nullable = true)
    private Long telephonyTypeId;

    /**
     * Telephone type relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "telephony_type_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_indicator_telephony_type")
    )
    private TelephonyType telephonyType;

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
    @Column(name = "city_name", length = 80, nullable = false)
    @ColumnDefault("")
    private String cityName;

    /**
     * ID reference to a cities table.
     * Original field: INDICATIVO_CIUDADES_ID
     */
    @Column(name = "city_id", nullable = true)
    private Long cityId;

    /**
     * City relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "city_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_indicator_city")
    )
    private City city;

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
    @Column(name = "operator_id", nullable = true)
    private Long operatorId;

    /**
     * Operator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_indicator_operator")
    )
    private Operator operator;

    /**
     * ID of the origin municipality.
     * Original field: INDICATIVO_MPORIGEN_ID
     */
    @Column(name = "origin_country_id", nullable = true)
    private Long originCountryId;

    /**
     * Origin country relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "origin_country_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_indicator_origin_country")
    )
    private OriginCountry originCountry;
}