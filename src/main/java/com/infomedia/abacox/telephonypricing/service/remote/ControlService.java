package com.infomedia.abacox.telephonypricing.service.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infomedia.abacox.telephonypricing.dto.module.ModuleInfo;
import com.infomedia.abacox.telephonypricing.exception.RemoteServiceException;
import com.infomedia.abacox.telephonypricing.messaging.InternalMessage;
import com.infomedia.abacox.telephonypricing.messaging.MessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ControlService {

    private static final long QUERY_TIMEOUT_MS = 30_000;

    private final MessagingService messagingService;
    private final ObjectMapper objectMapper;

    public ModuleInfo getInfoByPrefix(String prefix) {
        InternalMessage response = messagingService.sendQuery(
                "control",
                "MODULE_INFO_BY_PREFIX_QUERY",
                Map.of("prefix", prefix),
                QUERY_TIMEOUT_MS
        );
        if (response == null) {
            throw new RemoteServiceException("Query timed out: MODULE_INFO_BY_PREFIX_QUERY", null);
        }
        if (!response.isSuccess()) {
            throw new RemoteServiceException("Query failed: " + response.getPayload(), null);
        }
        try {
            return objectMapper.convertValue(response.getPayload(), ModuleInfo.class);
        } catch (Exception e) {
            throw new RemoteServiceException("Failed to deserialize ModuleInfo", e);
        }
    }

    public String getUsersUrl() {
        return getInfoByPrefix("users").getUrl();
    }
}
