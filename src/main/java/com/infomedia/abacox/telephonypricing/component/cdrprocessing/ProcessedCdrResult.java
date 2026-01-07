// E:/Github/abacox/abacox-telephony-pricing/src/main/java/com/infomedia/abacox/telephonypricing/component/cdrprocessing/ProcessedCdrResult.java

package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessedCdrResult {
    private String tenantId; // <-- ADD THIS FIELD
    private CdrData cdrData;
    private CommunicationLocation commLocation;
    private ProcessingOutcome outcome;
    
    // For quarantined records
    private QuarantineErrorType errorType;
    private String errorMessage;
    private String errorStep;
    private Long originalCallRecordId;
}