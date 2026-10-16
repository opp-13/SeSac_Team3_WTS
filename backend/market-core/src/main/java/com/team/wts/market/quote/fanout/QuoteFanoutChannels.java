package com.team.wts.market.quote.fanout;

/**
 * 시세 · 호가를 WebSocket 송신 쪽으로 흘려보내는 Valkey pub/sub 채널. (CLAUDE.md §66.3, §18 "WebSocket fan-out 보조")
 *
 * <p>market-ingest가 발행하고 market-service(api)의 모든 인스턴스가 구독한다. 두 앱이 같은 이름을 써야 하므로
 * market-core에 둔다. 값은 Valkey 캐시와 같은 JSON({@code MarketPrice} · {@code OrderBook})이다.
 */
public final class QuoteFanoutChannels {

    public static final String PRICE = "market:fanout:price";
    public static final String ORDER_BOOK = "market:fanout:orderbook";

    private QuoteFanoutChannels() {
    }
}
