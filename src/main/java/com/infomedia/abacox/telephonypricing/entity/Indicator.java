package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.Set;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

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
    @Column(name = "telephony_type_id", nullable = false)
    @ColumnDefault("0")
    private Long telephoneTypeId;

    /**
     * Telephone type relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "telephony_type_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private TelephonyType telephonyType;

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
    @Column(name = "city_name", length = 80, nullable = false)
    @ColumnDefault("")
    private String cityName;

    /**
     * ID reference to a cities table.
     * Original field: INDICATIVO_CIUDADES_ID
     */
    @Column(name = "city_id", nullable = false)
    @ColumnDefault("0")
    private Long cityId;

    /**
     * City relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "city_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
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
    @Column(name = "operator_id", nullable = false)
    @ColumnDefault("0")
    private Long operatorId;

    /**
     * Operator relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "operator_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private Operator operator;

    /**
     * ID of the origin municipality.
     * Original field: INDICATIVO_MPORIGEN_ID
     */
    @Column(name = "origin_country_id", nullable = false)
    @ColumnDefault("1")
    private Long originCountryId;

    /**
     * Origin country relationship.
     */
    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "origin_country_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private OriginCountry originCountry;
}