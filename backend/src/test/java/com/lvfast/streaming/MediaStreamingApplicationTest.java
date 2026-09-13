package com.lvfast.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = MediaStreamingApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "app.catalog.import-enabled=false",
                "app.auth.enabled=false"
        })
@org.springframework.test.context.ActiveProfiles("test")
class MediaStreamingApplicationTest {

    @Test
    void startsTheApplicationContext() {
    }
}
