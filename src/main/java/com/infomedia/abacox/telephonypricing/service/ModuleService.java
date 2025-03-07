package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.events.EventType;
import com.infomedia.abacox.telephonypricing.config.SecurityConfig;
import com.infomedia.abacox.telephonypricing.dto.module.EventTypesInfo;
import com.infomedia.abacox.telephonypricing.dto.module.MEndpointInfo;
import com.infomedia.abacox.telephonypricing.dto.module.ModuleInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Log4j2
public class ModuleService {

    private final ApplicationContext applicationContext;
    private final SecurityConfig securityConfig;
    private static final String moduleName = "Telephony Pricing";
    private static final String moduleType = "STANDARD";
    private static final String moduleDescription = "Telephony Pricing Module";
    private static final String moduleVersion = "1.0";
    private static final String modulePrefix = "telephony-pricing";
    private static final List<EventType> eventTypesProduces =
            List.of(EventType.TEST);
    private static final List<EventType> eventTypesConsumes =
            List.of(EventType.TEST);

    public ModuleInfo getInfo() {
        return ModuleInfo.builder()
                .name(moduleName)
                .type(moduleType)
                .description(moduleDescription)
                .version(moduleVersion)
                .prefix(modulePrefix)
                .build();
    }

    public EventTypesInfo getEventTypes() {
        return EventTypesInfo.builder()
                .produces(eventTypesProduces)
                .consumes(eventTypesConsumes)
                .build();
    }

    public List<MEndpointInfo> getEndpoints() {
        List<MEndpointInfo> mEndpointInfos = new ArrayList<>();
        RequestMappingHandlerMapping mapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = mapping.getHandlerMethods();

        handlerMethods.forEach((key, value) -> {
            Set<String> patterns;
            if (key.getPatternsCondition() != null) {
                patterns = key.getPatternsCondition().getPatterns();
            } else if (!key.getPathPatternsCondition().getPatterns().isEmpty()) {
                patterns = key.getPathPatternsCondition().getPatterns().stream()
                        .map(PathPattern::getPatternString)
                        .collect(Collectors.toSet());
            } else {
                patterns = Set.of();
            }

            Set<RequestMethod> methods = key.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                methods = Set.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                        RequestMethod.DELETE, RequestMethod.PATCH);
            }

            for (RequestMethod method : methods) {
                for (String pattern : patterns) {
                    log.info("Endpoint: {} {}", method, pattern);
                    mEndpointInfos.add(new MEndpointInfo(method.toString(), pattern,
                            !securityConfig.isPublicPath(pattern)));
                }
            }
        });

        mEndpointInfos.add(new MEndpointInfo("GET", "/swagger-ui/**", false));
        mEndpointInfos.sort(Comparator.comparing(MEndpointInfo::getPath));
        return mEndpointInfos;
    }

    public String getPrefix() {
        return modulePrefix;
    }
}
