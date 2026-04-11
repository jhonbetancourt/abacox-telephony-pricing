package com.infomedia.abacox.telephonypricing.security.method;

import com.infomedia.abacox.telephonypricing.security.annotation.RequiresPermission;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Wires {@link RequiresPermission} into Spring Security's method-security
 * pipeline using the same extension point that backs {@code @PreAuthorize}.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    AuthorizationManagerBeforeMethodInterceptor permissionAuthorizationMethodInterceptor(
            PermissionAuthorizationManager manager) {
        AnnotationMatchingPointcut pointcut =
                AnnotationMatchingPointcut.forMethodAnnotation(RequiresPermission.class);
        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(pointcut, manager);
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }
}
