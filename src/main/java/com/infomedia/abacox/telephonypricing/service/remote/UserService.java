package com.infomedia.abacox.telephonypricing.service.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.infomedia.abacox.telephonypricing.dto.generic.PageDto;
import com.infomedia.abacox.telephonypricing.dto.role.RoleDto;
import com.infomedia.abacox.telephonypricing.dto.user.UserDto;
import com.infomedia.abacox.telephonypricing.service.AuthService;
import com.infomedia.abacox.telephonypricing.service.ConfigManagerService;
import com.infomedia.abacox.telephonypricing.service.common.RemoteService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserService extends RemoteService {

    private final ControlService controlService;

    protected UserService(AuthService authService, ControlService controlService) {
        super(authService);
        this.controlService = controlService;
    }

    public PageDto<UserDto> findUsers(String filter, int page, int size, String sort) {
        Map<String, Object> params = Map.of("filter", filter==null?"":filter
                , "page", page, "size", size, "sort", sort==null?"":sort);
        TypeReference<PageDto<UserDto>> typeReference = new TypeReference<>() {};
        return get(params, "/api/user", typeReference);
    }

    public UserDto findUser(String filter) {
        PageDto<UserDto> users = findUsers(filter, 0, 1, null);
        return users.getContent().isEmpty() ? null : users.getContent().getFirst();
    }

    public PageDto<RoleDto> findRoles(String filter, int page, int size, String sort) {
        Map<String, Object> params = Map.of("filter", filter==null?"":filter
                , "page", page, "size", size, "sort", sort==null?"":sort);
        TypeReference<PageDto<RoleDto>> typeReference = new TypeReference<>() {};
        return get(params, "/api/role", typeReference);
    }

    public RoleDto findRole(String filter) {
        PageDto<RoleDto> roles = findRoles(filter, 0, 1, null);
        return roles.getContent().isEmpty() ? null : roles.getContent().getFirst();
    }

    @Override
    public String getBaseUrl() {
        return controlService.getUsersUrl();
    }
}
