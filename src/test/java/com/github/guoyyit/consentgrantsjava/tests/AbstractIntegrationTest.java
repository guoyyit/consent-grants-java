package com.github.guoyyit.consentgrantsjava.tests;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for integration tests. Each test class uses a private in-memory H2 database
 * (DB_CLOSE_DELAY keeps it alive across the JDBC pool for the life of the context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "consent.admin.key=test-admin-key",
        "consent.revocation.propagation-worker-enabled=false",
        "consent.cache.enabled=true",
        "consent.cache.ttl-ms=60000"
})
public abstract class AbstractIntegrationTest {
}
