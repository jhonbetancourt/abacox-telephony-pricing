package com.infomedia.abacox.telephonypricing.cdr;

public interface ICdrTypeProcessor {
    void parseHeader(String headerLine);
    CdrData evaluateFormat(String cdrLine);
    String getPlantTypeIdentifier(); // e.g., "CISCO_CM_6_0"
}