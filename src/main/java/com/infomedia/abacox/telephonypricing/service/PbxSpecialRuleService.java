package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.export.excel.GenericExcelGenerator;
import com.infomedia.abacox.telephonypricing.dto.pbxspecialrule.CreatePbxSpecialRule;
import com.infomedia.abacox.telephonypricing.dto.pbxspecialrule.UpdatePbxSpecialRule;
import com.infomedia.abacox.telephonypricing.entity.PbxSpecialRule;
import com.infomedia.abacox.telephonypricing.repository.PbxSpecialRuleRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Service
public class PbxSpecialRuleService extends CrudService<PbxSpecialRule, Long, PbxSpecialRuleRepository> {
    public PbxSpecialRuleService(PbxSpecialRuleRepository repository) {
        super(repository);
    }

    public PbxSpecialRule create(CreatePbxSpecialRule cDto) {
        PbxSpecialRule pbxSpecialRule = PbxSpecialRule.builder()
                .name(cDto.getName())
                .searchPattern(cDto.getSearchPattern())
                .ignorePattern(cDto.getIgnorePattern())
                .replacement(cDto.getReplacement())
                .commLocationId(cDto.getCommLocationId())
                .minLength(cDto.getMinLength())
                .direction(cDto.getDirection())
                .build();

        return save(pbxSpecialRule);
    }

    public PbxSpecialRule update(Long id, UpdatePbxSpecialRule uDto) {
        PbxSpecialRule pbxSpecialRule = get(id);
        uDto.getName().ifPresent(pbxSpecialRule::setName);
        uDto.getSearchPattern().ifPresent(pbxSpecialRule::setSearchPattern);
        uDto.getIgnorePattern().ifPresent(pbxSpecialRule::setIgnorePattern);
        uDto.getReplacement().ifPresent(pbxSpecialRule::setReplacement);
        uDto.getCommLocationId().ifPresent(pbxSpecialRule::setCommLocationId);
        uDto.getMinLength().ifPresent(pbxSpecialRule::setMinLength);
        uDto.getDirection().ifPresent(pbxSpecialRule::setDirection);
        return save(pbxSpecialRule);
    }

    public ByteArrayResource exportExcel(Specification<PbxSpecialRule> specification, Pageable pageable, Map<String, String> alternativeHeaders, Set<String> excludeColumns) {
        Page<PbxSpecialRule> collection = find(specification, pageable);
        try {
            InputStream inputStream = GenericExcelGenerator.generateExcelInputStream(collection.toList(),
                    Set.of(), alternativeHeaders, excludeColumns);
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}