package com.infomedia.abacox.telephonypricing.service.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infomedia.abacox.telephonypricing.dto.generic.PageDto;
import com.infomedia.abacox.telephonypricing.dto.role.RoleDto;
import com.infomedia.abacox.telephonypricing.dto.user.UserDto;
import com.infomedia.abacox.telephonypricing.exception.RemoteServiceException;
import com.infomedia.abacox.telephonypricing.messaging.InternalMessage;
import com.infomedia.abacox.telephonypricing.messaging.MessagingService;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final long QUERY_TIMEOUT_MS = 30_000;

    private final MessagingService messagingService;
    private final ObjectMapper objectMapper;

    public PageDto<UserDto> findUsers(String filter, int page, int size, String sort) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", filter != null ? filter : "");
        payload.put("page", page);
        payload.put("size", size);
        payload.put("sort", sort != null ? sort : "");
        payload.put("tenant", TenantContext.getTenant());

        InternalMessage response = messagingService.sendQuery(
                "users", "USER_FIND_QUERY", payload, QUERY_TIMEOUT_MS);
        return deserializePayload(response, "USER_FIND_QUERY",
                new TypeReference<PageDto<UserDto>>() {});
    }

    public UserDto findUser(String filter) {
        PageDto<UserDto> users = findUsers(filter, 0, 1, null);
        return users.getContent().isEmpty() ? null : users.getContent().getFirst();
    }

    public PageDto<RoleDto> findRoles(String filter, int page, int size, String sort) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("filter", filter != null ? filter : "");
        payload.put("page", page);
        payload.put("size", size);
        payload.put("sort", sort != null ? sort : "");
        payload.put("tenant", TenantContext.getTenant());

        InternalMessage response = messagingService.sendQuery(
                "users", "ROLE_FIND_QUERY", payload, QUERY_TIMEOUT_MS);
        return deserializePayload(response, "ROLE_FIND_QUERY",
                new TypeReference<PageDto<RoleDto>>() {});
    }

    public RoleDto findRole(String filter) {
        PageDto<RoleDto> roles = findRoles(filter, 0, 1, null);
        return roles.getContent().isEmpty() ? null : roles.getContent().getFirst();
    }

    private <T> T deserializePayload(InternalMessage response, String queryType, TypeReference<T> type) {
        if (response == null) {
            throw new RemoteServiceException("Query timed out: " + queryType, null);
        }
        if (response.getType().endsWith("_ERROR")) {
            throw new RemoteServiceException("Query failed [" + queryType + "]: " + response.getPayload(), null);
        }
        try {
            return objectMapper.convertValue(response.getPayload(), type);
        } catch (Exception e) {
            throw new RemoteServiceException("Failed to deserialize response for " + queryType, e);
        }
    }
}
