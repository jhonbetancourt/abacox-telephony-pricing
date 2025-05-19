package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;

public interface ICdrTypeProcessor {
    void parseHeader(String headerLine);

    CdrData evaluateFormat(String cdrLine, CommunicationLocation commLocation);

    String getPlantTypeIdentifier(); // e.g., "CISCO_CM_6_0"
}