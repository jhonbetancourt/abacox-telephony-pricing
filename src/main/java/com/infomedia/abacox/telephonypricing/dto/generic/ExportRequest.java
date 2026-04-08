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
        // Spring may split comma-separated "property,direction" into separate list entries,
        // so rejoin and re-parse as token pairs: property1,direction1,property2,direction2,...
        String joined = String.join(",", sort);
        String[] tokens = joined.split(",");
        List<Sort.Order> orders = new ArrayList<>();
        int i = 0;
        while (i < tokens.length) {
            String property = tokens[i].trim();
            if (property.isEmpty() || property.equalsIgnoreCase("asc") || property.equalsIgnoreCase("desc")) {
                i++;
                continue;
            }
            Sort.Direction direction = Sort.Direction.ASC;
            if (i + 1 < tokens.length) {
                String next = tokens[i + 1].trim();
                if (next.equalsIgnoreCase("asc") || next.equalsIgnoreCase("desc")) {
                    direction = Sort.Direction.fromString(next);
                    i++;
                }
            }
            orders.add(new Sort.Order(direction, property));
            i++;
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
