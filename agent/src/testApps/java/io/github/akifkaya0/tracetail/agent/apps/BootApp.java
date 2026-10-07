package io.github.akifkaya0.tracetail.agent.apps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;

/** A Spring Boot application, which reconfigures Logback or Log4j2 as it starts, then logs once it runs. */
@SpringBootConfiguration
public class BootApp implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootApp.class);

    public static void main(String[] args) {
        SpringApplication.run(BootApp.class, args);
    }

    @Override
    public void run(String... args) {
        // the names Micrometer gives the ids when Spring Boot's tracing is used
        MDC.put("traceId", "t-1");
        MDC.put("spanId", "s-1");
        log.info("runner line");
        log.warn("runner warning", new IllegalStateException("boom"));
        MDC.clear();
    }
}
