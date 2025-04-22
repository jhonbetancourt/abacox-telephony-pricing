package com.infomedia.abacox.telephonypricing.entity;

import com.infomedia.abacox.telephonypricing.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

/**
 * Entity representing cellular links or trunks.
 * Original table name: celulink
 */
@Entity
@Table(name = "trunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Trunk extends ActivableEntity {

    /**
     * Primary key for the cellular link.
     * Original field: CELULINK_ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trunk_id_seq")
    @SequenceGenerator(
            name = "trunk_id_seq",
            sequenceName = "trunk_id_seq",
            allocationSize = 1,
            initialValue = 1000000
    )
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * ID of the communication location.
     * Original field: CELULINK_COMUBICACION_ID
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
            foreignKey = @ForeignKey(name = "fk_cellular_link_comm_location")
    )
    private CommunicationLocation commLocation;

    /**
     * Description of the cellular link.
     * Original field: CELULINK_DESC
     */
    @Column(name = "description", length = 50, nullable = false)
    @ColumnDefault("''")
    private String description;

    /**
     * Trunk identifier for the cellular link.
     * Original field: CELULINK_TRONCAL
     */
    @Column(name = "trunk", length = 50, nullable = false)
    @ColumnDefault("''")
    private String trunk;

    /**
     * ID of the operator.
     * Original field: CELULINK_OPERADOR_ID
     */
    @Column(name = "operator_id", nullable = false)
    @ColumnDefault("0")
    private Long operatorId;

    /**
     * Operator relationship.
     */
    @ManyToOne
    @JoinColumn(
            name = "operator_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_cellular_link_operator")
    )
    private Operator operator;

    /**
     * Flag indicating if PBX prefix should not be used.
     * Original field: CELULINK_NOPREFIJOPBX
     */
    @Column(name = "no_pbx_prefix", nullable = false)
    @ColumnDefault("1")
    private Integer noPbxPrefix;

    /**
     * Number of channels available.
     * Original field: CELULINK_CANALES
     */
    @Column(name = "channels", nullable = false)
    @ColumnDefault("0")
    private Integer channels;
}