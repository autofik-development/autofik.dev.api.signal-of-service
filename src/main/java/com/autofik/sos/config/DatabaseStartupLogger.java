package com.autofik.sos.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
/**
 * Startup health check that verifies database connectivity and logs the connection details.
 * Runs once automatically after the Spring context is fully initialized.
 * Helps catch misconfigured DB credentials immediately at boot rather than at first query.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseStartupLogger {

    private final DataSource dataSource;

    // ApplicationRunner executes after the application context is ready and the app has started.
    // try-with-resources ensures the test connection is closed immediately after the check.
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
