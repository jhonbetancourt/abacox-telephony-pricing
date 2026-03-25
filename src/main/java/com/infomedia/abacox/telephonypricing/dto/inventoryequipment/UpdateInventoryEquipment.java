package com.infomedia.abacox.telephonypricing.dto.inventoryequipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateInventoryEquipment {
    @NotBlank
    @Size(max = 100)
    private JsonNullable<String> name = JsonNullable.undefined();
    private JsonNullable<Long> parentEquipmentId = JsonNullable.undefined();
    private JsonNullable<Float> valueTt = JsonNullable.undefined();
    private JsonNullable<Float> valueInfomedia = JsonNullable.undefined();
}
