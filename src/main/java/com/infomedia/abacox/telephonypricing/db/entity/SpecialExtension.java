package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing special extensions, such as main lines or hunt groups.
 * This entity maps to the legacy 'extespe' table.
 */
@Entity
@Table(name = "special_extension")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SpecialExtension extends ActivableEntity {

    /**
     * Primary key for the special extension.
     * Original field: EXTESPE_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "special_extension_id_seq")
    @SequenceGenerator(
            name = "special_extension_id_seq",
            sequenceName = "special_extension_id_seq",
            allocationSize = 1,
            initialValue = 10000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * The special extension number.
     * Original field: EXTESPE_EXT
     */
    @Column(name = "extension", length = 255, nullable = false)
    @ColumnDefault("''")
    private String extension;

    /**
     * The type of special extension.
     * Original field: EXTESPE_TIPO
     */
    @Column(name = "type", nullable = false)
    @ColumnDefault("0")
    private Integer type;

    /**
     * Flag indicating if this extension is related to LDAP.
     * Original field: EXTESPE_LDAP
     */
    @Column(name = "ldap_enabled", nullable = false)
    @ColumnDefault("false")
    private boolean ldapEnabled;

    /**
     * Description of the special extension.
     * Original field: EXTESPE_DESCRIPCION
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // Note: The 'active' field and all audit fields (createdDate, createdBy, etc.)
    // are inherited from the ActivableEntity superclass, which maps to the
    // EXTESPE_ACTIVO, EXTESPE_FCREACION, EXTESPE_CREADOR, etc. columns.
}