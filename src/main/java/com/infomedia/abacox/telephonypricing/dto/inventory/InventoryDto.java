package com.infomedia.abacox.telephonypricing.dto.inventory;

import com.infomedia.abacox.telephonypricing.dto.costcenter.CostCenterDto;
import com.infomedia.abacox.telephonypricing.dto.employee.EmployeeDto;
import com.infomedia.abacox.telephonypricing.dto.equipmenttype.EquipmentTypeDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryadditionalservice.InventoryAdditionalServiceDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryequipment.InventoryEquipmentDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryowner.InventoryOwnerDto;
import com.infomedia.abacox.telephonypricing.dto.inventorysupplier.InventorySupplierDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryusertype.InventoryUserTypeDto;
import com.infomedia.abacox.telephonypricing.dto.inventoryworkordertype.InventoryWorkOrderTypeDto;
import com.infomedia.abacox.telephonypricing.dto.subdivision.SubdivisionDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO for {@link com.infomedia.abacox.telephonypricing.db.entity.Inventory}
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventoryDto extends ActivableDto {
    private Long id;
    private String serialNumber;
    private String mac;
    private String plate;
    private Long employeeId;
    private Long inventoryEquipmentId;
    private Integer service;
    private Long locationId;
    private Long subdivisionId;
    private Long costCenterId;
    private String networkUser;
    private Long equipmentTypeId;
    private String description;
    private String comments;
    private String account;
    private Integer changeReason;
    private Long inventorySupplierId;
    private LocalDate installationDate;
    private Long inventoryWorkOrderTypeId;
    private Long inventoryUserTypeId;
    private String caseActNumber;
    private Integer caseAct;
    private Long inventoryOwnerId;
    private Long inventoryAdditionalServiceId;
    private LocalDate permissionsExpiry;
    private Long permissionsExtId;
    private EmployeeDto employee;
    private InventoryEquipmentDto inventoryEquipment;
    private SubdivisionDto subdivision;
    private CostCenterDto costCenter;
    private EquipmentTypeDto equipmentType;
    private InventorySupplierDto inventorySupplier;
    private InventoryWorkOrderTypeDto inventoryWorkOrderType;
    private InventoryUserTypeDto inventoryUserType;
    private InventoryOwnerDto inventoryOwner;
    private InventoryAdditionalServiceDto inventoryAdditionalService;
}
