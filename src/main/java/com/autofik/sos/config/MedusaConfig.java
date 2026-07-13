package com.autofik.sos.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "medusa")
@Getter @Setter
public class MedusaConfig {

    /**
     * The base URL endpoint for the Medusa external API service.
     */
    private String baseUrl;

    /**
     * The public API key used for non-sensitive public identifier tasks.
     */
    private String publicKey;
}
