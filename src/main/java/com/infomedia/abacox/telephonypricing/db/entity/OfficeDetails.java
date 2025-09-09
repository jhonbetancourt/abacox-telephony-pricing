package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing office details.
 * Original table name: DATOSOFICINA
 */
@Entity
@Table(name = "office_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class OfficeDetails extends ActivableEntity {

    /**
     * Primary key for the office details.
     * Original field: DATOSOFICINA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "office_details_id_seq")
    @SequenceGenerator(
            name = "office_details_id_seq",
            sequenceName = "office_details_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the subdivision/department.
     * Original field: DATOSOFICINA_SUBDIRECCION_ID
     */
    @Column(name = "subdivision_id", nullable = false)
    private Long subdivisionId;

    /**
     * Subdivision relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "subdivision_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_office_details_subdivision")
    )
    private Subdivision subdivision;

    /**
     * Address of the office.
     * Original field: DATOSOFICINA_DIRECCION
     */
    @Column(name = "address", length = 100, nullable = false)
    @ColumnDefault("''")
    private String address;

    /**
     * Phone number of the office.
     * Original field: DATOSOFICINA_TELEFONO
     */
    @Column(name = "phone", length = 100, nullable = false)
    @ColumnDefault("''")
    private String phone;

    /**
     * Indicator ID.
     * Original field: DATOSOFICINA_INDICATIVO_ID
     */
    @Column(name = "indicator_id", nullable = false)
    private Long indicatorId;

    /**
     * Indicator relationship.
     * Note: Assuming there's an Indicator entity, adjust if needed
     */
    @ManyToOne
    @JoinColumn(
            name = "indicator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_office_details_indicator")
    )
    private Indicator indicator;
}