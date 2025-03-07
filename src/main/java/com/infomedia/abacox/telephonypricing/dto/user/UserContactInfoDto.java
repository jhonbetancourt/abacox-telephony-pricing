package com.infomedia.abacox.telephonypricing.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class UserContactInfoDto {
    private String username;
    private String email;
    private String phone;
    private String rolename;
}