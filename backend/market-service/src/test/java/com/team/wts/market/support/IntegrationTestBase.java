package com.team.wts.market.support;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * market-service(api) 통합 테스트 베이스. (CLAUDE.md §35, §36, §66)
 *
 * <p>MySQL · Valkey만 띄운다. 이 앱은 Kafka · KIS를 쓰지 않는다. 시세 · 종목 · 1분봉은 market-ingest가 쓰는 값이라
 * 테스트가 같은 저장소(market-core)로 직접 넣는다 ({@link MarketDataFixture}).
 */
@Tag("integration") // Docker가 필요하다. CI는 단위 테스트와 다른 단계에서 돌린다 (CLAUDE.md §37)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @SuppressWarnings("resource") // JVM 종료 시 Ryuk이 정리한다.
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wts_market")
            .withUsername("wts")
            .withPassword("wts")
            .withCommand("--default-time-zone=+00:00");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        VALKEY.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    /** 조건이 참이 될 때까지 기다린다. 고정 sleep 대신 조건을 폴링한다. */
    protected static void awaitUntil(Duration timeout, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("제한 시간 " + timeout + " 안에 조건이 충족되지 않았다.");
    }
}
