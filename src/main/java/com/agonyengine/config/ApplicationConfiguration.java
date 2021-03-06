package com.agonyengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Date;
import java.util.UUID;

@Configuration
public class ApplicationConfiguration {
    private Date bootDate = new Date();

    @Value("${agonyengine.maps.default}")
    private UUID defaultMapId;

    @Bean(name = "applicationVersion")
    public String applicationVersion() {
        return ApplicationConfiguration.class.getPackage().getImplementationVersion();
    }

    @Bean(name = "applicationBootDate")
    public Date applicationBootDate() {
        return bootDate;
    }

    @Bean
    public UUID defaultMapId() {
        return defaultMapId;
    }
}
