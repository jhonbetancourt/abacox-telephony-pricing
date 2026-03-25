package com.infomedia.abacox.telephonypricing.dto.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDate;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.Inventory}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateInventory {
    @NotBlank
    @Size(max = 20)
    private JsonNullable<String> serialNumber = JsonNullable.undefined();
    @Size(max = 20)
    private JsonNullable<String> mac = JsonNullable.undefined();
    @Size(max = 20)
    private JsonNullable<String> plate = JsonNullable.undefined();
    private JsonNullable<Long> employeeId = JsonNullable.undefined();
    private JsonNullable<Long> inventoryEquipmentId = JsonNullable.undefined();
    private JsonNullable<Integer> service = JsonNullable.undefined();
    private JsonNullable<Long> locationId = JsonNullable.undefined();
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    private JsonNullable<Long> costCenterId = JsonNullable.undefined();
    @NotBlank
    @Size(max = 50)
    private JsonNullable<String> networkUser = JsonNullable.undefined();
    private JsonNullable<Long> equipmentTypeId = JsonNullable.undefined();
    private JsonNullable<String> description = JsonNullable.undefined();
    private JsonNullable<String> comments = JsonNullable.undefined();
    @Size(max = 50)
    private JsonNullable<String> account = JsonNullable.undefined();
    private JsonNullable<Integer> changeReason = JsonNullable.undefined();
    private JsonNullable<Long> inventorySupplierId = JsonNullable.undefined();
    private JsonNullable<LocalDate> installationDate = JsonNullable.undefined();
    private JsonNullable<Long> inventoryWorkOrderTypeId = JsonNullable.undefined();
    private JsonNullable<Long> inventoryUserTypeId = JsonNullable.undefined();
    @Size(max = 50)
    private JsonNullable<String> caseActNumber = JsonNullable.undefined();
    private JsonNullable<Integer> caseAct = JsonNullable.undefined();
    private JsonNullable<Long> inventoryOwnerId = JsonNullable.undefined();
    private JsonNullable<Long> inventoryAdditionalServiceId = JsonNullable.undefined();
    private JsonNullable<LocalDate> permissionsExpiry = JsonNullable.undefined();
    private JsonNullable<Long> permissionsExtId = JsonNullable.undefined();
    private JsonNullable<Integer> status = JsonNullable.undefined();
}
