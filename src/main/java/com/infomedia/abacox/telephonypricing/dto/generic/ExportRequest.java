package com.infomedia.abacox.telephonypricing.dto.generic;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Parameter object for export endpoints — only size (max rows) and optional sort.
 */
@Data
public class ExportRequest {

    @Schema(description = "Maximum number of rows to export", defaultValue = "10000")
    private Integer size;

    @ArraySchema(schema = @Schema(description = "Sort expression (format: property,direction)", implementation = String.class))
    private List<String> sort;

    public int getMaxRows() {
        return size != null && size > 0 ? size : 100000;
    }

    public Sort getSortOrder() {
        if (sort == null || sort.isEmpty()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String s : sort) {
            String[] parts = s.split(",", 2);
            String property = parts[0].trim();
            if (property.isEmpty()) continue;
            Sort.Direction direction = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc"))
                    ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
