package com.team.wts.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.candle.domain.Candle;
import com.team.wts.market.candle.domain.CurrentCandleStore;
import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketDataProvider;
import com.team.wts.market.quote.domain.QuoteCache;
import com.team.wts.market.quote.domain.QuoteDemand;
import com.team.wts.market.quote.domain.QuoteSource;
import com.team.wts.market.quote.fanout.QuoteFanoutChannels;
import com.team.wts.market.quote.synthetic.SyntheticQuoteFeed;
import com.team.wts.market.stock.domain.StockRepository;
import com.team.wts.market.support.IntegrationTestBase;

/**
 * 시세 수집 앱의 흐름 (CLAUDE.md §47 Phase 2, §66).
 *
 * <p>모의 시세 한 틱이 Valkey 저장 · Valkey pub/sub 발행 · Kafka → 1분봉 집계까지 흘러가는지를 실제 인프라에서 검증한다.
 * 고정 종목 밖의 종목은 수요가 기록됐을 때만 시세가 생기고, 같은 시각의 시세는 몇 번 만들어도 같다 (§67).
 * 화면으로 보내는 것(WebSocket)과 REST 조회는 market-service의 통합 테스트가 맡는다.
 */
class MarketIngestIntegrationTest extends IntegrationTestBase {

    private static final String SAMSUNG = "005930";
    /** 고정 종목이 아닌 실제 상장 종목 (동화약품). */
    private static final String UNPINNED = "000020";

    @Autowired
    private SyntheticQuoteFeed feed;

    @Autowired
    private QuoteDemand demand;

    @Autowired
    private StringRedisTemplate redis;

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
    @DisplayName("한 틱이 Valkey에 최신 시세 · 호가로 남는다. 모의 시세라고 표시된다")
    void savesLatestQuoteToValkey() {
        feed.tick(Instant.now());

        assertThat(quotes.findPrice(SAMSUNG)).get()
                .satisfies(price -> assertThat(price.source()).isEqualTo(QuoteSource.SIMULATED));
        assertThat(quotes.findOrderBook(SAMSUNG)).get()
                .satisfies(book -> assertThat(book.asks()).hasSize(5));
    }

    @Test
    @DisplayName("고정 종목이 아니어도 누군가 보고 있으면 다음 틱에 시세가 생긴다 (§67)")
    void producesQuotesForViewedSymbols() {
        assertThat(stocks.findBySymbol(UNPINNED)).as("스냅샷 마스터의 종목").isPresent();
        Instant now = Instant.now();
        redis.delete("market:price:" + UNPINNED);

        feed.tick(now);
        assertThat(quotes.findPrice(UNPINNED)).as("수요가 없으면 만들지 않는다").isEmpty();

        demand.touch(List.of(UNPINNED), now.plusSeconds(30));
        feed.tick(now.plusSeconds(1));
        assertThat(quotes.findPrice(UNPINNED)).isPresent();
    }

    @Test
    @DisplayName("같은 시각의 시세는 재시작한 뒤 다시 만들어도 Valkey에 바이트 단위로 같게 남는다 (§67)")
    void sameInstantYieldsIdenticalQuoteAcrossRestarts(@Autowired MarketDataListener listener,
            @Autowired ObjectProvider<MarketDataProvider> realtime, @Autowired MarketProperties properties,
            @Autowired java.time.Clock clock) {
        Instant at = Instant.parse("2026-10-19T01:30:00.250Z");
        feed.tick(at);
        String first = redis.opsForValue().get("market:price:" + SAMSUNG);
        String firstBook = redis.opsForValue().get("market:orderbook:" + SAMSUNG);

        redis.delete(List.of("market:price:" + SAMSUNG, "market:orderbook:" + SAMSUNG));
        // 재시작: 메모리 상태가 없는 새 인스턴스
        new SyntheticQuoteFeed(demand, stocks, listener, realtime, properties, clock).tick(at.plusMillis(500));

        assertThat(redis.opsForValue().get("market:price:" + SAMSUNG)).isNotNull().isEqualTo(first);
        assertThat(redis.opsForValue().get("market:orderbook:" + SAMSUNG)).isNotNull().isEqualTo(firstBook);
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
            Instant at = Instant.now();
            while (price == null && System.nanoTime() < deadline) {
                at = at.plusSeconds(1); // 같은 슬롯은 다시 내보내지 않는다
                feed.tick(at);
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
        // 여러 틱을 넣어 고가/저가가 갈리게 한다. 슬롯이 달라야 다시 내보낸다.
        Instant start = Instant.now();
        for (int i = 0; i < 5; i++) {
            feed.tick(start.plusSeconds(i));
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
