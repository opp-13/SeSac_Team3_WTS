package com.team.wts.market.quote.application;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.team.wts.common.web.TraceIdFilter;
import com.team.wts.market.quote.domain.MarketDataListener;
import com.team.wts.market.quote.domain.MarketEventPublisher;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.domain.QuoteBroadcaster;
import com.team.wts.market.quote.domain.QuoteCache;

/**
 * 공급자가 밀어 넣은 시세를 처리한다. (CLAUDE.md §6.2)
 *
 * <p>Mock이든 KIS든 여기로 들어오면 이후 경로는 같다.
 *
 * <pre>
 * 시세 수신
 *   1. Valkey에 최신값 저장   — REST 조회가 즉시 최신값을 보게 하려면 가장 먼저 한다
 *   2. Kafka 이벤트 발행      — 캔들 집계, 미체결 지정가 체결 검사
 *   3. WebSocket 브로드캐스트 — 구독 중인 화면
 * </pre>
 *
 * <p>시세는 HTTP 요청이 아니라 스케줄러/소켓 스레드에서 들어오므로 traceId가 없다.
 * 여기서 틱마다 하나를 만든다. 그래야 "이 틱이 저 체결을 일으켰다"를 로그에서 이을 수 있다.
 * trading-service의 소비자가 이벤트의 traceId를 이어받는다 (CLAUDE.md §40).
 */
@Service
public class QuoteIngestService implements MarketDataListener {

    private final QuoteCache cache;
    private final MarketEventPublisher publisher;
    private final QuoteBroadcaster broadcaster;

    public QuoteIngestService(QuoteCache cache,
                              MarketEventPublisher publisher,
                              QuoteBroadcaster broadcaster) {
        this.cache = cache;
        this.publisher = publisher;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onPrice(MarketPrice price) {
        MDC.put(TraceIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            cache.savePrice(price);
            publisher.publishPriceUpdated(price);
            broadcaster.broadcastPrice(price);
        } finally {
            // 스레드가 재사용되므로 반드시 지운다. 남기면 다음 틱이 남의 traceId를 쓴다.
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }

    @Override
    public void onOrderBook(OrderBook orderBook) {
        // 호가는 Kafka로 내보내지 않는다. 소비자가 없고, 양이 많아 토픽만 부풀린다 (§16).
        cache.saveOrderBook(orderBook);
        broadcaster.broadcastOrderBook(orderBook);
    }
}
