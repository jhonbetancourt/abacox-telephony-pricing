package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

/**
 * Entity representing inventory equipment catalog.
 * Original table name: invequipos
 */
@Entity
@Table(name = "inventory_equipment", indexes = {
        @Index(name = "idx_inventory_equipment_name", columnList = "name"),
        @Index(name = "idx_inventory_equipment_parent", columnList = "parent_equipment_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "udx_inventory_equipment_name_parent", columnNames = {"name", "parent_equipment_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class InventoryEquipment extends ActivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inventory_equipment_id_seq")
    @SequenceGenerator(name = "inventory_equipment_id_seq", sequenceName = "inventory_equipment_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    @ColumnDefault("''")
    private String name;

    @Column(name = "parent_equipment_id")
    private Long parentEquipmentId;

    @ManyToOne
    @JoinColumn(name = "parent_equipment_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_equipment_parent"))
    private InventoryEquipment parentEquipment;

    @Column(name = "value_tt", precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal valueTt;

    @Column(name = "value_infomedia", precision = 10, scale = 4)
    @ColumnDefault("0")
    private BigDecimal valueInfomedia;
}
