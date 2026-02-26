package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing a named list of extensions (used for group-based call
 * reports).
 * Original table name: listadoext
 */
@Entity
@Table(name = "extension_list", indexes = {
        @Index(name = "idx_extension_list_active", columnList = "active"),
        @Index(name = "idx_extension_list_type", columnList = "type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@SuperBuilder(toBuilder = true)
public class ExtensionList extends ActivableEntity {

    /**
     * Primary key.
     * Original field: LISTADOEXT_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "extension_list_id_seq")
    @SequenceGenerator(name = "extension_list_id_seq", sequenceName = "extension_list_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Human-readable name of the extension list/group.
     * Original field: LISTADOEXT_NOMBRE
     */
    @Column(name = "name", length = 50, nullable = false, unique = true)
    @ColumnDefault("''")
    private String name;

    /**
     * Type of list (e.g. 'EXT' for extension groups, 'AUDIO' for auto-attendants).
     * Original field: LISTADOEXT_TIPO
     */
    @Column(name = "type", length = 20, nullable = false)
    @ColumnDefault("''")
    private String type;

    /**
     * Comma/newline-separated list of extension numbers belonging to this group.
     * Original field: LISTADOEXT_LISTADO
     */
    @Column(name = "extension_list", columnDefinition = "TEXT")
    private String extensionList;

    // 'active', 'createdDate', 'createdBy', 'lastModifiedDate', 'lastModifiedBy'
    // are inherited from ActivableEntity / AuditedEntity, mapping to:
    // LISTADOEXT_ACTIVO, LISTADOEXT_FCREACION, LISTADOEXT_CREADOR,
    // LISTADOEXT_FMODIFICADO, LISTADOEXT_MODIFICADO
}
