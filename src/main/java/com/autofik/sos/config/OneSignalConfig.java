package com.autofik.sos.config;

import com.onesignal.client.ApiClient;
import com.onesignal.client.Configuration;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.auth.HttpBearerAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class OneSignalConfig {

    @Value("${onesignal.api-key}")
    private String apiKey;

    @Bean
    public DefaultApi oneSignalApi() {
        ApiClient client = Configuration.getDefaultApiClient();
        HttpBearerAuth auth = (HttpBearerAuth) client.getAuthentication("rest_api_key");
        auth.setBearerToken(apiKey);
        return new DefaultApi(client);
    }
}
