package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import com.infomedia.abacox.telephonypricing.entity.superclass.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.log4j.Log4j2;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing PBX special rules.
 * Original table name: pbxespecial
 */
@Entity
@Table(name = "pbx_special_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class PbxSpecialRule extends ActivableEntity {

    /**
     * Primary key for the PBX special rule.
     * Original field: PBXESPECIAL_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pbx_special_rule_id_seq")
    @SequenceGenerator(
            name = "pbx_special_rule_id_seq",
            sequenceName = "pbx_special_rule_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Name/description of the special rule.
     * Original field: PBXESPECIAL_NOMBRE
     */
    @Column(name = "name", length = 200, nullable = false)
    @ColumnDefault("''")
    private String name;

    /**
     * Search pattern for the rule.
     * Original field: PBXESPECIAL_BUSCAR
     */
    @Column(name = "search_pattern", length = 50, nullable = false)
    @ColumnDefault("''")
    private String searchPattern;

    /**
     * Ignore pattern for the rule.
     * Original field: PBXESPECIAL_IGNORAR
     */
    @Column(name = "ignore_pattern", length = 200, nullable = false)
    @ColumnDefault("''")
    private String ignorePattern;

    /**
     * Replacement pattern for the rule.
     * Original field: PBXESPECIAL_REMPLAZO
     */
    @Column(name = "replacement", length = 50, nullable = false)
    @ColumnDefault("''")
    private String replacement;

    /**
     * ID of the communication location.
     * Original field: PBXESPECIAL_COMUBICACION_ID
     */
    @Column(name = "comm_location_id", nullable = false)
    @ColumnDefault("0")
    private Long commLocationId;

    /**
     * Communication location relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "comm_location_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_pbx_special_rule_comm_location")
    )
    private CommunicationLocation commLocation;

    /**
     * Minimum length requirement for the rule.
     * Original field: PBXESPECIAL_MINLEN
     */
    @Column(name = "min_length", nullable = false)
    @ColumnDefault("0")
    private Short minLength;

    /**
     * Flag for incoming/outgoing/both direction.
     * Original field: PBXESPECIAL_IO
     * Note: 0=both, 1=incoming, 2=outgoing
     */
    @Column(name = "direction", nullable = false)
    @ColumnDefault("2") 
    private Short direction;
}