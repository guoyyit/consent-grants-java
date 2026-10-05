package com.github.guoyyit.consentgrantsjava.config;

import com.github.guoyyit.consentgrantsjava.security.AdminKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminKeyInterceptor adminKeyInterceptor;

    public WebConfig(AdminKeyInterceptor adminKeyInterceptor) {
        this.adminKeyInterceptor = adminKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminKeyInterceptor).addPathPatterns("/admin/**");
    }
}
