package com.infomedia.abacox.telephonypricing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.component.modeltools.ModelConverter;
import com.infomedia.abacox.telephonypricing.dto.contact.ContactDto;
import com.infomedia.abacox.telephonypricing.dto.contact.CreateContact;
import com.infomedia.abacox.telephonypricing.dto.contact.UpdateContact;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivationDto;
import com.infomedia.abacox.telephonypricing.db.entity.Contact;
import com.infomedia.abacox.telephonypricing.service.ContactService;
import com.turkraft.springfilter.boot.Filter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@RestController
@Tag(name = "Contact", description = "Contact API")
@SecurityRequirements({
        @SecurityRequirement(name = "JWT_Token"),
        @SecurityRequirement(name = "Username")
})
@RequestMapping("/api/contact")
public class ContactController {

    private final ContactService contactService;
    private final ModelConverter modelConverter;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ContactDto> find(@Parameter(hidden = true) @Filter Specification<Contact> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return modelConverter.mapPage(contactService.find(spec, pageable), ContactDto.class);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ContactDto create(@Valid @RequestBody CreateContact createContact) {
        return modelConverter.map(contactService.create(createContact), ContactDto.class);
    }

    @PatchMapping(value = "{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ContactDto update(@PathVariable("id") Long id, @Valid @RequestBody UpdateContact uDto) {
        return modelConverter.map(contactService.update(id, uDto), ContactDto.class);
    }

    @PatchMapping(value = "/status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ContactDto activate(@PathVariable("id") Long id, @Valid @RequestBody ActivationDto activationDto) {
        return modelConverter.map(contactService.changeActivation(id, activationDto.getActive()), ContactDto.class);
    }

    @GetMapping(value = "{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ContactDto get(@PathVariable("id") Long id) {
        return modelConverter.map(contactService.get(id), ContactDto.class);
    }

    @GetMapping(value = "/export/excel", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> exportExcel(@Parameter(hidden = true) @Filter Specification<Contact> spec
            , @Parameter(hidden = true) Pageable pageable
            , @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page
            , @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort
            , @RequestParam(required = false) String alternativeHeaders
            , @RequestParam(required = false) String excludeColumns
            , @RequestParam(required = false) String includeColumns
            , @RequestParam(required = false) String valueReplacements) {

        Map<String, String> alternativeHeadersMap = modelConverter.convert(alternativeHeaders==null?null:
                new String(Base64.getDecoder().decode(alternativeHeaders)), new TypeReference<Map<String, String>>() {});
        Set<String> excludeColumnsList = modelConverter.convert(excludeColumns==null?null:
                new String(Base64.getDecoder().decode(excludeColumns)), new TypeReference<Set<String>>() {});
        Set<String> includeColumnsList = modelConverter.convert(includeColumns==null?null:
                new String(Base64.getDecoder().decode(includeColumns)), new TypeReference<Set<String>>() {});
        Map<String, Map<String, String>> valueReplacementsMap = modelConverter.convert(valueReplacements==null?null:
                new String(Base64.getDecoder().decode(valueReplacements)), new TypeReference<Map<String, Map<String, String>>>() {});


        ByteArrayResource resource = contactService.exportExcel(spec, pageable
                , alternativeHeadersMap, excludeColumnsList, includeColumnsList, valueReplacementsMap);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=contacts.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}