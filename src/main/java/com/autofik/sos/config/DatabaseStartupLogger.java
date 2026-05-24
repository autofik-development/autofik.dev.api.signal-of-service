package com.autofik.sos.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseStartupLogger {

    private final DataSource dataSource;

    @Bean
    public ApplicationRunner logDatabaseConnection() {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {

                String url = connection.getMetaData().getURL();
                String user = connection.getMetaData().getUserName();
                log.info("Database connected successfully");
                log.info("  URL  : {}", url);
                log.info("  User : {}", user);
            } catch (Exception e) {
                log.error("Failed to connect to database: {}", e.getMessage());
            }
        };
    }
}
