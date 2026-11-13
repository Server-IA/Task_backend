package com.taskflow.api.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra el aviso en tiempo real después de los cambios hechos por la API ({@link RealtimeInterceptor}). */
@Configuration
@RequiredArgsConstructor
public class RealtimeConfig implements WebMvcConfigurer {

    private final RealtimeInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
