package com.infomedia.abacox.telephonypricing.dto.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO for creating {@link com.infomedia.abacox.telephonypricing.db.entity.Inventory}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateInventory {
    @NotBlank
    @Size(max = 20)
    private String serialNumber;
    @Size(max = 20)
    private String mac;
    @Size(max = 20)
    private String plate;
    private Long employeeId;
    private Long inventoryEquipmentId;
    private Integer service;
    private Long locationId;
    private Long subdivisionId;
    private Long costCenterId;
    @NotBlank
    @Size(max = 50)
    private String networkUser;
    private Long equipmentTypeId;
    private String description;
    private String comments;
    @Size(max = 50)
    private String account;
    private Integer changeReason;
    private Long inventorySupplierId;
    private LocalDate installationDate;
    private Long inventoryWorkOrderTypeId;
    private Long inventoryUserTypeId;
    @Size(max = 50)
    private String caseActNumber;
    private Integer caseAct;
    private Long inventoryOwnerId;
    private Long inventoryAdditionalServiceId;
    private LocalDate permissionsExpiry;
    private Long permissionsExtId;
}
