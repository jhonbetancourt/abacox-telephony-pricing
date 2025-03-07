package com.infomedia.abacox.telephonypricing.dto.user;

import com.infomedia.abacox.telephonypricing.dto.role.RoleDto;
import com.infomedia.abacox.telephonypricing.dto.superclass.ActivableDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDto extends ActivableDto {
    private UUID id;
    private String username;
    private String email;
    private String phone;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private RoleDto role;
}