package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CdrConfigService {

    private final LookupService lookupService; // For dynamic limits

    public int getMinCallDurationForProcessing() {
        return 0;
    }

    public int getMaxCallDurationCap() {
        return 172800;
    }

    public List<String> getPbxOutputPrefixes(CommunicationLocation commLocation) {
        if (commLocation == null || commLocation.getPbxPrefix() == null || commLocation.getPbxPrefix().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(commLocation.getPbxPrefix().split(","));
    }

    public InternalExtensionLimitsDto getInternalExtensionLimits(Long originCountryId, Long commLocationId) {
        // Delegate to LookupService to get dynamically determined limits
        return lookupService.getInternalExtensionLimits(originCountryId, commLocationId);
    }

    public List<String> getIgnoredAuthCodes() {
        return Arrays.asList("Invalid Authorization Code", "Invalid Authorization Level");
    }
}