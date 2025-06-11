package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing telephone types.
 * Original table name: TIPOTELE
 */
@Entity
@Table(name = "telephony_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class TelephonyType extends ActivableEntity {

    /**
     * Primary key for the telephone type.
     * Original field: TIPOTELE_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "telephony_type_id_seq")
    @SequenceGenerator(
            name = "telephony_type_id_seq",
            sequenceName = "telephony_type_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the telephone type.
     * Original field: TIPOTELE_NOMBRE
     */
    @Column(name = "name", length = 40, nullable = false)
    @ColumnDefault("")
    private String name;

    /**
     * ID of the call class/type.
     * Original field: TIPOTELE_CLASELLAMA_ID
     */
    @Column(name = "call_category_id")
    private Long callCategoryId;

    /**
     * Call category relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "call_category_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_telephony_type_call_category")
    )
    private CallCategory callCategory;

    /**
     * Flag indicating if it uses trunk lines.
     * Original field: TIPOTELE_TRONCALES
     */
    @Column(name = "uses_trunks", nullable = false)
    @ColumnDefault("false")
    private boolean usesTrunks;

    // The following fields were commented out in the original schema:

    /**
     * Minimum value (commented out in original schema).
     * Original field: TIPOTELE_MIN
     */
    // @Column(name = "minimum", nullable = false)
    // @ColumnDefault("0")
    // private Integer minimum;

    /**
     * Maximum value (commented out in original schema).
     * Original field: TIPOTELE_MAX
     */
    // @Column(name = "maximum", nullable = false)
    // @ColumnDefault("0")
    // private Integer maximum;
}