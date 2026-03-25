package com.infomedia.abacox.telephonypricing.dto.inventorysupplier;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * DTO for updating {@link com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier}
 */
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateInventorySupplier {
    @Size(max = 50)
    private JsonNullable<String> name = JsonNullable.undefined();
    private JsonNullable<Long> inventoryEquipmentId = JsonNullable.undefined();
    @Size(max = 50)
    private JsonNullable<String> company = JsonNullable.undefined();
    @Size(max = 15)
    private JsonNullable<String> nit = JsonNullable.undefined();
    private JsonNullable<Long> subdivisionId = JsonNullable.undefined();
    @Size(max = 100)
    private JsonNullable<String> address = JsonNullable.undefined();
}
