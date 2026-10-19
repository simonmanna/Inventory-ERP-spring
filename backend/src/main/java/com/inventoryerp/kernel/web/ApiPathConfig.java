package com.inventoryerp.kernel.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Every application controller is served under {@code /api/v1}, the Nest global prefix. */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

    public static final String PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forBasePackage("com.inventoryerp"));
    }
}
