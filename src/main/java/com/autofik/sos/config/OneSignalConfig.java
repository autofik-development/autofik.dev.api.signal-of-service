package com.autofik.sos.config;

import com.onesignal.client.ApiClient;
import com.onesignal.client.Configuration;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.auth.HttpBearerAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Configures the OneSignal Java SDK client bean used by OneSignalServiceImpl to send push notifications.
 * Authentication uses the REST API Key (not the Organization API Key — that is only for managing apps).
 */
@org.springframework.context.annotation.Configuration
public class OneSignalConfig {

    @Value("${onesignal.api-key}")
    private String apiKey;

    @Bean
    public DefaultApi oneSignalApi() {
        // getDefaultApiClient() returns a shared singleton — safe to configure once at startup
        ApiClient client = Configuration.getDefaultApiClient();
        // "rest_api_key" is the named auth scheme defined in the OneSignal OpenAPI spec
        HttpBearerAuth auth = (HttpBearerAuth) client.getAuthentication("rest_api_key");
        auth.setBearerToken(apiKey);
        return new DefaultApi(client);
    }
}
