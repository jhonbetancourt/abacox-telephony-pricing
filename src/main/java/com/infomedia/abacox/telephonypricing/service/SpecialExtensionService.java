package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.ExcelGeneratorBuilder;
import com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.specialextension.CreateSpecialExtension;
import com.infomedia.abacox.telephonypricing.dto.specialextension.UpdateSpecialExtension;
import com.infomedia.abacox.telephonypricing.repository.SpecialExtensionRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class SpecialExtensionService extends CrudService<SpecialExtension, Long, SpecialExtensionRepository> {

    public SpecialExtensionService(SpecialExtensionRepository repository) {
        super(repository);
    }

    /**
     * Creates a new SpecialExtension entity from a DTO.
     *
     * @param cDto The DTO containing the data for the new entity.
     * @return The persisted SpecialExtension entity.
     */
    public SpecialExtension create(CreateSpecialExtension cDto) {
        SpecialExtension specialExtension = SpecialExtension.builder()
                .extension(cDto.getExtension())
                .type(cDto.getType())
                .ldapEnabled(cDto.getLdapEnabled())
                .description(cDto.getDescription())
                .build();

        return save(specialExtension);
    }

    /**
     * Updates an existing SpecialExtension entity based on the provided DTO.
     * Fields in the DTO are optional and will only be updated if present.
     *
     * @param id   The ID of the SpecialExtension to update.
     * @param uDto The DTO containing the fields to update.
     * @return The updated and persisted SpecialExtension entity.
     */
    public SpecialExtension update(Long id, UpdateSpecialExtension uDto) {
        SpecialExtension specialExtension = get(id);

        uDto.getExtension().ifPresent(specialExtension::setExtension);
        uDto.getType().ifPresent(specialExtension::setType);
        uDto.getLdapEnabled().ifPresent(specialExtension::setLdapEnabled);
        uDto.getDescription().ifPresent(specialExtension::setDescription);

        return save(specialExtension);
    }

    /**
     * Exports a list of SpecialExtension entities to an Excel file.
     *
     * @param specification The filter criteria.
     * @param pageable      The pagination and sorting information.
     * @param builder       The Excel generator builder to configure the output.
     * @return A ByteArrayResource representing the generated Excel file.
     */
    public ByteArrayResource exportExcel(Specification<SpecialExtension> specification, Pageable pageable, ExcelGeneratorBuilder builder) {
        Page<SpecialExtension> collection = find(specification, pageable);
        try {
            InputStream inputStream = builder.withEntities(collection.toList()).generateAsInputStream();
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            // In a real application, consider a more specific exception type
            throw new RuntimeException("Failed to generate Excel file.", e);
        }
    }
}