package com.infomedia.abacox.telephonypricing.db.entity;

import com.infomedia.abacox.telephonypricing.db.entity.superclass.ActivableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing inventory items (telecom equipment assigned to employees).
 * Original table name: inventario
 */
@Entity
@Table(name = "inventory", indexes = {
        @Index(name = "idx_inventory_serial_number", columnList = "serial_number"),
        @Index(name = "idx_inventory_mac", columnList = "mac"),
        @Index(name = "idx_inventory_plate", columnList = "plate"),
        @Index(name = "idx_inventory_account", columnList = "account"),
        @Index(name = "idx_inventory_network_user", columnList = "network_user"),
        @Index(name = "idx_inventory_employee", columnList = "employee_id"),
        @Index(name = "idx_inventory_equipment", columnList = "inventory_equipment_id"),
        @Index(name = "idx_inventory_equipment_type", columnList = "equipment_type_id"),
        @Index(name = "idx_inventory_subdivision", columnList = "subdivision_id"),
        @Index(name = "idx_inventory_supplier", columnList = "inventory_supplier_id"),
        @Index(name = "idx_inventory_work_order_type", columnList = "inventory_work_order_type_id"),
        @Index(name = "idx_inventory_user_type", columnList = "inventory_user_type_id"),
        @Index(name = "idx_inventory_owner", columnList = "inventory_owner_id"),
        @Index(name = "idx_inventory_additional_service", columnList = "inventory_additional_service_id"),
        @Index(name = "idx_inventory_history_control", columnList = "history_control_id"),
        @Index(name = "idx_inventory_history_since", columnList = "history_since"),
        @Index(name = "idx_inventory_installation_date", columnList = "installation_date"),
        @Index(name = "idx_inventory_case_act", columnList = "case_act"),
        @Index(name = "idx_inventory_case_act_number", columnList = "case_act_number"),
        @Index(name = "idx_inventory_change_reason", columnList = "change_reason"),
        @Index(name = "idx_inventory_permissions_ext", columnList = "permissions_ext_id"),
        @Index(name = "idx_inventory_permissions_expiry", columnList = "permissions_expiry")
}, uniqueConstraints = {
        @UniqueConstraint(name = "udx_inventory_history", columnNames = {"serial_number", "history_since", "mac", "plate"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Inventory extends ActivableEntity implements HistoricalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inventory_id_seq")
    @SequenceGenerator(name = "inventory_id_seq", sequenceName = "inventory_id_seq", allocationSize = 1, initialValue = 10000000)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "serial_number", length = 20, nullable = false)
    @ColumnDefault("''")
    private String serialNumber;

    @Column(name = "mac", length = 20)
    private String mac;

    @Column(name = "plate", length = 20)
    private String plate;

    @Column(name = "employee_id")
    private Long employeeId;

    @ManyToOne
    @JoinColumn(name = "employee_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_employee"))
    private Employee employee;

    @Column(name = "inventory_equipment_id")
    private Long inventoryEquipmentId;

    @ManyToOne
    @JoinColumn(name = "inventory_equipment_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_equipment"))
    private InventoryEquipment inventoryEquipment;

    @Column(name = "service")
    private Integer service;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "subdivision_id")
    private Long subdivisionId;

    @ManyToOne
    @JoinColumn(name = "subdivision_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_subdivision"))
    private Subdivision subdivision;

    @Column(name = "cost_center_id")
    private Long costCenterId;

    @ManyToOne
    @JoinColumn(name = "cost_center_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_cost_center"))
    private CostCenter costCenter;

    @Column(name = "network_user", length = 50, nullable = false)
    @ColumnDefault("''")
    private String networkUser;

    @Column(name = "equipment_type_id")
    private Long equipmentTypeId;

    @ManyToOne
    @JoinColumn(name = "equipment_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_equipment_type"))
    private EquipmentType equipmentType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @Column(name = "history_since")
    private LocalDateTime historySince;

    @Column(name = "history_change", columnDefinition = "TEXT")
    private String historyChange;

    @Column(name = "history_control_id")
    private Long historyControlId;

    @ManyToOne
    @JoinColumn(name = "history_control_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_history_control"))
    private HistoryControl historyControl;

    @Column(name = "account", length = 50)
    private String account;

    @Column(name = "change_reason")
    private Integer changeReason;

    @Column(name = "inventory_supplier_id")
    private Long inventorySupplierId;

    @ManyToOne
    @JoinColumn(name = "inventory_supplier_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_supplier"))
    private InventoryDs inventorySupplier;

    @Column(name = "installation_date")
    private LocalDate installationDate;

    @Column(name = "inventory_work_order_type_id")
    private Long inventoryWorkOrderTypeId;

    @ManyToOne
    @JoinColumn(name = "inventory_work_order_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_work_order_type"))
    private InventoryWorkOrderType inventoryWorkOrderType;

    @Column(name = "inventory_user_type_id")
    private Long inventoryUserTypeId;

    @ManyToOne
    @JoinColumn(name = "inventory_user_type_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_user_type"))
    private InventoryUserType inventoryUserType;

    @Column(name = "case_act_number", length = 50, nullable = false)
    @ColumnDefault("''")
    private String caseActNumber;

    @Column(name = "case_act")
    private Integer caseAct;

    @Column(name = "inventory_owner_id")
    private Long inventoryOwnerId;

    @ManyToOne
    @JoinColumn(name = "inventory_owner_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_owner"))
    private InventoryOwner inventoryOwner;

    @Column(name = "inventory_additional_service_id")
    private Long inventoryAdditionalServiceId;

    @ManyToOne
    @JoinColumn(name = "inventory_additional_service_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_inventory_additional_service"))
    private InventoryAdditionalService inventoryAdditionalService;

    @Column(name = "permissions_expiry")
    private LocalDate permissionsExpiry;

    @Column(name = "permissions_ext_id")
    private Long permissionsExtId;
}
