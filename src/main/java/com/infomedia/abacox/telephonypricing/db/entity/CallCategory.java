package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;

import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing call categories/classifications.
 * Original table name: CLASELLAMA
 */
@Entity
@Table(name = "call_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class CallCategory extends ActivableEntity {

    /**
     * Primary key for the call category.
     * Original field: CLASELLAMA_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "call_category_id_seq")
    @SequenceGenerator(
            name = "call_category_id_seq",
            sequenceName = "call_category_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name of the call category.
     * Original field: CLASELLAMA_NOMBRE
     */
    @Column(name = "name", length = 80, nullable = false)
    @ColumnDefault("")
    private String name;

    // The following field was commented out in the original schema:
    
    /**
     * ID of the telephone type (commented out in original schema).
     * Original field: CLASELLAMA_TIPOTELE_ID
     */
    // @Column(name = "phone_type_id", nullable = false)
    // @ColumnDefault("0")
    // private Integer phoneTypeId;
}