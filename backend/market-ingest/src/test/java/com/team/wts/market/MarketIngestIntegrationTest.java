package com.team.wts.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CurrentCandleStore;
import com.team.wts.market.quote.adapter.out.provider.MockMarketDataAdapter;
import com.team.wts.market.quote.domain.QuoteCache;
import com.team.wts.market.quote.fanout.QuoteFanoutChannels;
import com.team.wts.market.stock.domain.StockRepository;
import com.team.wts.market.support.IntegrationTestBase;

/**
 * 시세 수집 앱의 흐름 (CLAUDE.md §47 Phase 2, §66).
 *
 * <p>Mock 시세 한 틱이 Valkey 저장 · Valkey pub/sub 발행 · Kafka → 1분봉 집계까지 흘러가는지를 실제 인프라에서 검증한다.
 * 화면으로 보내는 것(WebSocket)과 REST 조회는 market-service의 통합 테스트가 맡는다.
 */
class MarketIngestIntegrationTest extends IntegrationTestBase {

    private static final String SAMSUNG = "005930";

    @Autowired
    private MockMarketDataAdapter mockMarket;

    @Autowired
    private StockRepository stocks;

    @Autowired
    private QuoteCache quotes;

    @Autowired
    private CurrentCandleStore currentCandles;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("기동하면 저장소 스냅샷으로 전 종목 마스터가 채워진다 (Mock, 네트워크 없음)")
    void loadsFullMasterOnStartup() {
        // 2026-10 스냅샷: KOSPI 주권 914 + KOSDAQ 주권 1,805
        assertThat(stocks.count()).isGreaterThan(2_000);
    }

    @Test
    @DisplayName("한 틱이 Valkey에 최신 시세 · 호가로 남는다")
    void savesLatestQuoteToValkey() {
        mockMarket.tick();

        assertThat(quotes.findPrice(SAMSUNG)).isPresent();
        assertThat(quotes.findOrderBook(SAMSUNG)).get()
                .satisfies(book -> assertThat(book.asks()).hasSize(5));
    }

    @Test
    @DisplayName("한 틱이 Valkey pub/sub로 나간다 - market-service가 받아 WebSocket으로 보낸다 (§66.3)")
    void publishesTicksForFanout() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer subscriber = new RedisMessageListenerContainer();
        subscriber.setConnectionFactory(connectionFactory);
        subscriber.addMessageListener((message, pattern) -> {
            try {
                received.add(objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, new ChannelTopic(QuoteFanoutChannels.PRICE));
        subscriber.afterPropertiesSet();
        subscriber.start();
        try {
            // 구독이 실제로 걸리기 전의 발행은 사라진다. 받을 때까지 틱을 넣는다.
            JsonNode price = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (price == null && System.nanoTime() < deadline) {
                mockMarket.tick();
                price = received.poll(300, TimeUnit.MILLISECONDS);
            }
            assertThat(price).as("pub/sub 시세").isNotNull();
            assertThat(price.path("symbol").asText()).isNotBlank();
            assertThat(price.path("price").decimalValue()).isPositive();
        } finally {
            subscriber.stop();
            subscriber.destroy();
        }
    }

    @Test
    @DisplayName("시세가 Kafka를 거쳐 1분봉으로 집계되고, 진행 중인 봉이 Valkey에 남는다 (§66.4)")
    void aggregatesCandlesAndSharesTheCurrentOne() throws Exception {
        // 여러 틱을 넣어 고가/저가가 갈리게 한다.
        for (int i = 0; i < 5; i++) {
            mockMarket.tick();
        }

        awaitUntil(Duration.ofSeconds(30), () -> currentCandles.find(SAMSUNG).isPresent());

        Candle current = currentCandles.find(SAMSUNG).orElseThrow();
        assertThat(current.high()).isGreaterThanOrEqualTo(current.low());
        assertThat(current.high()).isGreaterThanOrEqualTo(current.open()).isGreaterThanOrEqualTo(current.close());
        assertThat(current.low()).isLessThanOrEqualTo(current.open()).isLessThanOrEqualTo(current.close());
    }

    @Test
    @DisplayName("사용자 요청용 구성 요소는 없다 - WebSocket 송신은 market-service의 일이다")
    void hasNoBrowserFacingWebSocket() {
        assertThat(context.getBeanNamesForType(
                org.springframework.web.socket.config.annotation.WebSocketConfigurer.class)).isEmpty();
    }
}
