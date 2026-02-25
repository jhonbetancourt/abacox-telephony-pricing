package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@Builder
public class LineProcessingContext {
    private String cdrLine;
    private CommunicationLocation commLocation;
    private CdrProcessor cdrProcessor;
    private Map<Long, List<ExtensionRange>> extensionRanges;
    private Map<Long, ExtensionLimits> extensionLimits;
    private FileInfo fileInfo;
    private HistoricalDataContainer historicalData;

    // Added: Holds the CSV column mapping for the specific file being processed
    private Map<String, Integer> headerPositions;

    public ExtensionLimits getCommLocationExtensionLimits() {
        return extensionLimits.get(commLocation.getId());
    }

    public List<ExtensionRange> getCommLocationExtensionRanges() {
        return extensionRanges.get(commLocation.getId());
    }

    public Long getFileInfoId() {
        return fileInfo != null ? fileInfo.getId() : null;
    }

    public Long getCommLocationId() {
        return commLocation != null ? commLocation.getId() : null;
    }

    public List<String> getIgnoredAuthCodes() {
        return cdrProcessor.getIgnoredAuthCodeDescriptions();
    }
}