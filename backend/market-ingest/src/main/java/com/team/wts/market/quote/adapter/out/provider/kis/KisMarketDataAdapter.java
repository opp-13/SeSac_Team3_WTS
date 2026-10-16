package com.team.wts.market.quote.adapter.out.provider.kis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.team.wts.market.config.KisProperties;
import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketDataProvider;
import com.team.wts.market.quote.domain.MarketPrice;

/**
 * 한국투자증권 OpenAPI 시세 공급자. (CLAUDE.md §19, §20, §47 Phase 6)
 *
 * <p>{@code MARKET_PROVIDER=kis} 일 때만 만들어진다. Mock 어댑터와 배타적이다.
 *
 * <p>기동 순서:
 * <ol>
 *   <li>REST로 종목별 마지막 시세를 채운다 — 장 시작 전·마감 후에도 화면이 비지 않게</li>
 *   <li>WebSocket을 열고 고정 종목({@code market.pinned-symbols})을 등록한다.
 *       수요 기반 등록 · 해제는 Phase 8-2다 (§57.2)</li>
 * </ol>
 *
 * <p>시세가 들어오는 이후 경로는 Mock과 완전히 같다. {@link MarketDataListener}로 넘기면
 * Valkey · Kafka · WebSocket 브로드캐스트가 순서대로 일어난다.
 */
@Component
@ConditionalOnProperty(name = "market.provider", havingValue = "kis")
public class KisMarketDataAdapter implements MarketDataProvider, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KisMarketDataAdapter.class);

    private final List<String> pinnedSymbols;
    private final MarketDataListener listener;
    private final KisProperties properties;
    private final KisQuoteRestClient restClient;
    private final KisRealtimeDecoder decoder;
    private final KisWebSocketClient webSocketClient;

    private final Map<String, MarketPrice> latestPrices = new ConcurrentHashMap<>();

    private volatile boolean running;

    public KisMarketDataAdapter(MarketProperties marketProperties, MarketDataListener listener,
                                KisProperties properties, RestClient.Builder restClientBuilder,
                                Clock clock) {
        requireCredentials(properties);
        this.pinnedSymbols = marketProperties.pinnedSymbols();
        this.listener = listener;
        this.properties = properties;

        RestClient rest = restClientBuilder.baseUrl(properties.restBaseUrl()).build();
        KisAuthClient auth = new KisAuthClient(rest, properties, clock);
        this.restClient = new KisQuoteRestClient(rest, auth, properties, clock);
        this.decoder = new KisRealtimeDecoder(clock);
        this.webSocketClient = new KisWebSocketClient(properties, auth, this::onFrame, clock);
    }

    /** 자격증명 없이 kis 모드로 뜨면 원인을 찾기 어려운 인증 오류만 남는다. 기동에서 막는다 (§52). */
    private static void requireCredentials(KisProperties properties) {
        if (isBlank(properties.appKey()) || isBlank(properties.appSecret())) {
            throw new IllegalStateException(
                    "market.provider=kis 인데 KIS_APP_KEY / KIS_APP_SECRET 이 비어 있다. "
                            + "루트 .env 에 채우거나 MARKET_PROVIDER=mock 으로 되돌린다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ── MarketDataProvider ─────────────────────────────────

    @Override
    public Optional<MarketPrice> getCurrentPrice(String symbol) {
        return Optional.ofNullable(latestPrices.get(symbol));
    }

    @Override
    public void subscribe(String symbol) {
        webSocketClient.subscribe(symbol);
    }

    @Override
    public void unsubscribe(String symbol) {
        webSocketClient.unsubscribe(symbol);
    }

    // ── SmartLifecycle ─────────────────────────────────────

    @Override
    public void start() {
        // 마스터 전체를 등록하면 KIS 등록 한도(kis.websocket.subscription-limit)를 바로 넘는다.
        List<String> symbols = pinnedSymbols;
        log.info("KIS 시세 공급자 시작: environment={} 종목 {}개",
                properties.environment(), symbols.size());

        seedFromRest(symbols);
        webSocketClient.start(symbols);
        running = true;
    }

    /**
     * 실시간이 흐르기 전에 마지막 시세를 채운다.
     *
     * <p>한 종목이 실패해도 나머지는 계속한다. 시세가 없으면 그 종목만 조회가 503이 될 뿐이고,
     * 다음 실시간 체결이 오면 해소된다.
     */
    private void seedFromRest(List<String> symbols) {
        List<String> remaining = fetchEach(symbols);
        if (!remaining.isEmpty()) {
            // 유량 제한은 일시적이다. 한 번만 더 시도한다. 그래도 안 되면 실시간이 채운다.
            log.info("KIS 초기 시세 재시도: {}", remaining);
            remaining = fetchEach(remaining);
        }
        log.info("KIS 초기 시세 {}/{} 종목 수신{}",
                symbols.size() - remaining.size(), symbols.size(),
                remaining.isEmpty() ? "" : " (실패: " + remaining + ")");
    }

    /** @return 받지 못한 종목 */
    private List<String> fetchEach(List<String> symbols) {
        long interval = properties.rest().requestInterval().toMillis();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            if (i > 0) {
                // KIS는 초당 거래건수를 제한한다. 연달아 쏘면 EGW00201로 거절된다.
                sleep(interval);
            }
            Optional<MarketPrice> price = restClient.fetchPrice(symbols.get(i));
            if (price.isPresent()) {
                publish(price.get());
            } else {
                failed.add(symbols.get(i));
            }
        }
        return failed;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        webSocketClient.stop();
        log.info("KIS 시세 공급자 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── 실시간 수신 ─────────────────────────────────────────

    private void onFrame(KisRealtimeFrame frame) {
        try {
            switch (frame.trId()) {
                case KisRealtimeDecoder.TR_PRICE ->
                        decoder.decodePrices(frame).forEach(this::publish);
                case KisRealtimeDecoder.TR_ORDER_BOOK ->
                        decoder.decodeOrderBook(frame).ifPresent(listener::onOrderBook);
                default -> log.debug("구독하지 않은 TR: {}", frame.trId());
            }
        } catch (RuntimeException e) {
            // 한 프레임의 파싱 실패가 스트림 전체를 멈추게 하면 안 된다.
            log.error("KIS 실시간 프레임 처리 실패: tr_id={}", frame.trId(), e);
        }
    }

    private void publish(MarketPrice price) {
        latestPrices.put(price.symbol(), price);
        listener.onPrice(price);
    }
}
