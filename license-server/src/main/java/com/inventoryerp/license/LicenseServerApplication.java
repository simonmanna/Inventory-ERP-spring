package com.inventoryerp.license;

import com.inventoryerp.license.application.LicenseProperties;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The central licence authority.
 *
 * <p>Runs apart from the ERP, with its own database, because it is the only place the signing
 * private key may exist (ADR-S002). An ERP install is a client of this service and can verify a
 * licence without it — the server is needed to issue, renew and revoke, not to keep a shop trading.
 */
@SpringBootApplication
@EnableConfigurationProperties(LicenseProperties.class)
@EnableScheduling
public class LicenseServerApplication {

    public static void main(String[] args) {
        // Every term, grace window and audit timestamp is UTC; pin the JVM so a host's zone cannot
        // shift a customer's expiry date by a day.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(LicenseServerApplication.class, args);
    }
}
