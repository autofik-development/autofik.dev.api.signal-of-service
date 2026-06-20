package com.autofik.sos.config;

import com.google.maps.GeoApiContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GoogleMapsConfig {

    @Value("${google.maps.api-key:}")
    private String apiKey;

    @Bean
    public GeoApiContext geoApiContext() {
        return new GeoApiContext.Builder()
                .apiKey(apiKey)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }
}
