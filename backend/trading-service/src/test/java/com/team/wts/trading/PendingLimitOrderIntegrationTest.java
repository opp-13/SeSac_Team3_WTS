package com.team.wts.trading;

import static com.team.wts.trading.support.MarketEventFixture.waitUntil;
import static com.team.wts.trading.support.TradingApiClient.decimal;
import static com.team.wts.trading.support.TradingApiClient.order;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import com.team.wts.common.event.KafkaTopics;

import com.team.wts.trading.execution.domain.Execution;
import com.team.wts.trading.execution.domain.ExecutionRepository;
import com.team.wts.trading.ledger.domain.LedgerEntry;
import com.team.wts.trading.ledger.domain.LedgerEntryRepository;
import com.team.wts.trading.ledger.domain.LedgerEntryType;
import com.team.wts.trading.support.IntegrationTestBase;
import com.team.wts.trading.support.MarketEventFixture;
import com.team.wts.trading.support.MarketPriceFixture;
import com.team.wts.trading.support.TradingApiClient;

/**
 * Phase 4 완료조건이자 CLAUDE.md §36이 요구하는 통합 시나리오.
 *
 * <pre>
 * market.price.updated 발생
 * → 지정가 주문 체결
 * → execution 생성
 * → account 변경
 * → position 변경
 * → ledger 생성
 * </pre>
 *
 * <p>실제 Kafka 브로커를 거친다. 소비자 설정이나 JSON 형식이 어긋나면 여기서 드러난다.
 */
@Import(PendingLimitOrderIntegrationTest.Fixtures.class)
class PendingLimitOrderIntegrationTest extends IntegrationTestBase {

    private static final String SAMSUNG = "005930";

    @TestConfiguration
    static class Fixtures {

        /**
         * 토픽을 미리 만든다.
         *
         * <p>운영에서는 market-service가 발행하면서 자동 생성되지만, 이 테스트에는 발행자가 없다.
         * 없는 토픽을 구독한 소비자는 파티션을 배정받지 못해 메시지를 영원히 못 받는다.
         */
        @Bean
        NewTopic marketPriceUpdatedTopic() {
            return TopicBuilder.name(KafkaTopics.MARKET_PRICE_UPDATED)
                    .partitions(1)
                    .replicas(1)
                    .build();
        }

        @Bean
        MarketEventFixture marketEventFixture(KafkaTemplate<String, String> kafka,
                KafkaListenerEndpointRegistry registry) {
            return new MarketEventFixture(kafka, registry);
        }
    }

    @Autowired
    private TradingApiClient api;

    @Autowired
    private MarketPriceFixture marketPrice;

    @Autowired
    private MarketEventFixture marketEvents;

    @Autowired
    private ExecutionRepository executions;

    @Autowired
    private LedgerEntryRepository ledger;

    @BeforeEach
    void startListeners() {
        marketEvents.startListeners();
    }

    @Test
    @DisplayName("가격이 지정가에 도달하면 미체결 주문이 자동으로 체결된다")
    void limitOrderFillsWhenPriceReachesCondition() {
        String user = UUID.randomUUID().toString();
        marketPrice.publish(SAMSUNG, "80000");

        // 1) 현재가 80,000에서 70,000 지정가 매수 → 미체결
        Map<String, Object> placed = api.place(user, order(SAMSUNG, "BUY", "LIMIT", 10, "70000"))
                .getBody();
        long orderId = ((Number) placed.get("orderId")).longValue();

        assertThat(placed).containsEntry("status", "ACCEPTED");
        assertThat(api.reservedCash(user)).isEqualByComparingTo("700000");
        assertThat(api.list(user, "/api/trading/positions")).isEmpty();

        // 2) 조건을 만족하지 않는 시세는 아무것도 바꾸지 않는다
        marketEvents.publishPrice(SAMSUNG, "75000");
        marketEvents.publishPrice(SAMSUNG, "70100");
        // 3) 조건을 만족하는 시세
        marketEvents.publishPrice(SAMSUNG, "69900");

        waitUntil("지정가 주문 체결", () -> executions.findByOrderIdOrderByIdAsc(orderId).size() == 1);

        // execution 생성 — 체결가는 지정가가 아니라 조건을 만족시킨 시세다
        Execution execution = executions.findByOrderIdOrderByIdAsc(orderId).get(0);
        assertThat(execution.price()).isEqualByComparingTo("69900");
        assertThat(execution.quantity()).isEqualTo(10);
        assertThat(execution.amount()).isEqualByComparingTo("699000");
        assertThat(execution.realizedProfit()).isNull();

        // 주문 상태
        Map<String, Object> filled = api.get(user, "/api/trading/orders/" + orderId);
        assertThat(filled).containsEntry("status", "FILLED");
        assertThat(filled).containsEntry("filledQuantity", 10);

        // account 변경 — 예약이 풀리고 체결 대금만 빠진다
        Map<String, Object> account = api.account(user);
        assertThat(decimal(account, "cashBalance")).isEqualByComparingTo("99301000");
        assertThat(decimal(account, "reservedCash")).isEqualByComparingTo("0");

        // position 변경
        List<Map<String, Object>> positions = api.list(user, "/api/trading/positions");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0)).containsEntry("quantity", 10);
        assertThat(decimal(positions.get(0), "averagePrice")).isEqualByComparingTo("69900");

        // ledger 생성 — 계좌 개설 입금과 매수 출금
        Long accountId = ((Number) account.get("accountId")).longValue();
        List<LedgerEntry> entries = ledger.findByAccountIdOrderByIdDesc(accountId);
        assertThat(entries).hasSize(2);

        LedgerEntry buy = entries.get(0);
        assertThat(buy.type()).isEqualTo(LedgerEntryType.BUY);
        assertThat(buy.amount()).isEqualByComparingTo("-699000");
        assertThat(buy.beforeBalance()).isEqualByComparingTo("100000000");
        assertThat(buy.afterBalance()).isEqualByComparingTo("99301000");
        assertThat(buy.executionId()).isEqualTo(execution.id());

        LedgerEntry deposit = entries.get(1);
        assertThat(deposit.type()).isEqualTo(LedgerEntryType.INITIAL_DEPOSIT);
        assertThat(deposit.amount()).isEqualByComparingTo("100000000");
        assertThat(deposit.beforeBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("미체결 지정가 매도도 가격이 오르면 체결되고 실현손익이 남는다")
    void limitSellFillsAndRecordsRealizedProfit() {
        String user = UUID.randomUUID().toString();
        marketPrice.publish(SAMSUNG, "80000");
        api.place(user, order(SAMSUNG, "BUY", "MARKET", 10, null));

        Map<String, Object> placed = api.place(user, order(SAMSUNG, "SELL", "LIMIT", 4, "90000"))
                .getBody();
        long orderId = ((Number) placed.get("orderId")).longValue();
        assertThat(placed).containsEntry("status", "ACCEPTED");

        marketEvents.publishPrice(SAMSUNG, "91000");

        waitUntil("지정가 매도 체결", () -> executions.findByOrderIdOrderByIdAsc(orderId).size() == 1);

        Execution execution = executions.findByOrderIdOrderByIdAsc(orderId).get(0);
        assertThat(execution.price()).isEqualByComparingTo("91000");
        // (91,000 - 80,000) × 4
        assertThat(execution.realizedProfit()).isEqualByComparingTo("44000");

        // 100,000,000 - 800,000 + 364,000
        assertThat(api.cashBalance(user)).isEqualByComparingTo("99564000");

        List<Map<String, Object>> positions = api.list(user, "/api/trading/positions");
        assertThat(positions.get(0)).containsEntry("quantity", 6);
        assertThat(positions.get(0)).containsEntry("reservedQuantity", 0);
        // 매도는 평균단가를 바꾸지 않는다
        assertThat(decimal(positions.get(0), "averagePrice")).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("취소된 주문은 조건을 만족해도 체결되지 않는다")
    void cancelledOrderIsNeverFilled() {
        String user = UUID.randomUUID().toString();
        marketPrice.publish(SAMSUNG, "80000");
        long cancelledId = orderIdOf(api.place(user, order(SAMSUNG, "BUY", "LIMIT", 10, "70000")));
        assertThat(api.cancel(user, cancelledId).getBody()).containsEntry("status", "CANCELLED");

        // 같은 종목의 살아 있는 주문. 이게 체결되면 소비가 실제로 일어났다는 뜻이다.
        String other = UUID.randomUUID().toString();
        long liveId = orderIdOf(api.place(other, order(SAMSUNG, "BUY", "LIMIT", 1, "70000")));

        marketEvents.publishPrice(SAMSUNG, "60000");
        waitUntil("살아 있는 주문 체결", () -> executions.findByOrderIdOrderByIdAsc(liveId).size() == 1);

        assertThat(executions.findByOrderIdOrderByIdAsc(cancelledId)).isEmpty();
        assertThat(api.cashBalance(user)).isEqualByComparingTo("100000000");
        assertThat(api.reservedCash(user)).isEqualByComparingTo("0");
    }

    private static long orderIdOf(org.springframework.http.ResponseEntity<Map<String, Object>> response) {
        return ((Number) response.getBody().get("orderId")).longValue();
    }
}
