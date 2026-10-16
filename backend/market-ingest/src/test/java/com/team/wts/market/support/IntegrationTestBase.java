package com.team.wts.market.support;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * market-ingest 통합 테스트 베이스. (CLAUDE.md §35, §36, §66)
 *
 * <p>MySQL · Valkey · Kafka를 실제로 띄운다. 시세 한 건이 Valkey 저장 → Kafka 발행 · pub/sub 발행 →
 * 1분봉 집계까지 흘러가는지를 실제 인프라 위에서 확인하기 위해서다.
 *
 * <p>Mock 스트림의 스케줄러는 사실상 꺼둔다(주기 1시간). 테스트가 {@code tick()}을 직접 호출해
 * 시점을 통제한다.
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

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        MYSQL.start();
        VALKEY.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // 테스트 시작 전에 발행된 메시지도 놓치지 않게 한다.
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");

        // 가격 흐름을 재현 가능하게 고정하고, 스케줄러가 끼어들지 않게 주기를 길게 둔다 (§31).
        registry.add("market.mock.seed", () -> 20260921L);
        registry.add("market.mock.tick-interval", () -> "1h");
    }

    /**
     * 조건이 참이 될 때까지 기다린다.
     *
     * <p>Kafka 소비는 비동기라 즉시 확인할 수 없다. 고정 sleep 대신 조건을 폴링한다.
     */
    protected static void awaitUntil(Duration timeout, Callable<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
                lastFailure = null;
            } catch (AssertionError e) {
                lastFailure = e;
            } catch (Exception e) {
                throw new IllegalStateException("조건 평가 중 오류", e);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("대기 중 인터럽트", e);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("제한 시간 " + timeout + " 안에 조건이 충족되지 않았다.");
    }
}
