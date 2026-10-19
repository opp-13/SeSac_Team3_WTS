package com.team.wts.market.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시세 관련 설정. (CLAUDE.md §19, §21, §31)
 *
 * @param provider      mock | kis. 어댑터 선택 기준이다.
 * @param pinnedSymbols 아무도 보지 않아도 항상 시세가 있는 종목 (§57.2의 고정 종목). kis 모드에서는 KIS 실시간으로 받고,
 *                      mock 모드에서는 모의 시세가 만든다. 나머지는 보는 사람이 있을 때 모의 시세가 만든다 (§67).
 *                      KIS 수요 기반 구독은 Phase 8-2에서 붙인다.
 * @param master        종목 마스터 동기화 (§57.1)
 * @param synthetic     결정형 모의 시세 (§67). Mock 모드에서는 모든 종목, kis 모드에서는 실시간으로 받지 않는 종목
 * @param price         시세 유효성 판단 기준
 * @param feedRelay     corebank로 시세를 중계한다 (§65)
 */
@ConfigurationProperties(prefix = "market")
public record MarketProperties(
        String provider,
        List<String> pinnedSymbols,
        Master master,
        Synthetic synthetic,
        Price price,
        FeedRelay feedRelay) {

    public MarketProperties {
        pinnedSymbols = pinnedSymbols == null ? List.of() : List.copyOf(pinnedSymbols);
        master = master == null ? new Master(null, null, null) : master;
        synthetic = synthetic == null ? new Synthetic(null, null, null, null, null) : synthetic;
        price = price == null ? new Price(null) : price;
        // 모의 시세의 시각은 슬롯 시작이다. 슬롯이 max-age보다 길면 막 만든 시세도 낡은 것으로 보여 시장가 주문이 거절된다 (§11).
        if (synthetic.slot().compareTo(price.maxAge()) >= 0) {
            throw new IllegalArgumentException("market.synthetic.slot(" + synthetic.slot()
                    + ") 은 market.price.max-age(" + price.maxAge() + ") 보다 짧아야 한다.");
        }
        feedRelay = feedRelay == null ? new FeedRelay(false, null, null, null, null) : feedRelay;
    }

    public boolean kis() {
        return "kis".equalsIgnoreCase(provider);
    }

    /**
     * @param downloadBaseUrl 한국투자증권 종목정보 파일 위치. 파일 이름(kospi_code.mst.zip 등)을 뒤에 붙인다
     * @param refreshAfter    kis 모드에서 기동할 때 마스터가 이보다 오래됐으면 새로 받는다
     * @param downloadTimeout 연결 · 응답 대기 상한
     */
    public record Master(String downloadBaseUrl, Duration refreshAfter, Duration downloadTimeout) {
        public Master {
            downloadBaseUrl = downloadBaseUrl == null || downloadBaseUrl.isBlank()
                    ? "https://new.real.download.dws.co.kr/common/master/"
                    : downloadBaseUrl;
            refreshAfter = refreshAfter == null ? Duration.ofHours(20) : refreshAfter;
            downloadTimeout = downloadTimeout == null ? Duration.ofSeconds(30) : downloadTimeout;
        }
    }

    /**
     * 결정형 모의 시세 (CLAUDE.md §67, ADR-0023). 실제 시세를 받지 않는 종목에 시세를 준다.
     *
     * @param enabled      끄면 실제 시세를 받는 종목만 시세가 있다
     * @param slot         가격이 바뀌는 단위 시간. 같은 슬롯 안에서는 언제 계산해도 같은 값이다.
     *                     시세 시각이 슬롯 시작이라 {@code market.price.max-age}보다 짧아야 시장가 주문이 받아들여진다
     * @param tickInterval 수요를 확인하고 시세를 내보내는 주기. 비우면 슬롯과 같다. 슬롯보다 짧아도 같은 슬롯은 다시 내보내지 않는다
     * @param seed         해시 seed. 바꾸면 모든 종목의 시세 경로가 바뀐다. 고정값이 기본이다
     * @param maxSymbols   한 번에 모의 시세를 만드는 수요 종목 수 상한. 고정 종목은 따로 센다
     */
    public record Synthetic(
            Boolean enabled,
            Duration slot,
            Duration tickInterval,
            Long seed,
            Integer maxSymbols) {

        public Synthetic {
            enabled = enabled == null || enabled;
            slot = slot == null ? Duration.ofSeconds(1) : slot;
            tickInterval = tickInterval == null ? slot : tickInterval;
            seed = seed == null ? 0L : seed;
            maxSymbols = maxSymbols == null ? 100 : maxSymbols;
            if (slot.isNegative() || slot.isZero() || tickInterval.isNegative() || tickInterval.isZero()) {
                throw new IllegalArgumentException("market.synthetic.slot · tick-interval 은 0보다 커야 한다.");
            }
        }
    }

    /**
     * @param maxAge 이 시간을 넘긴 시세는 stale로 본다. 시장가 주문 거절 기준이다 (§11).
     */
    public record Price(Duration maxAge) {

        public Price {
            maxAge = maxAge == null ? Duration.ofSeconds(5) : maxAge;
        }
    }

    /**
     * corebank trading-service로 시세를 중계한다 (CLAUDE.md §65, ADR-0021).
     *
     * <p>trading-service와 tier가 달라 Valkey · Kafka를 나눠 쓸 때만 켠다.
     * 로컬은 같은 Valkey · Kafka라 끈다.
     *
     * @param enabled        켜면 {@code market.price.updated}를 따로 소비해 corebank로 보낸다
     * @param url            받는 주소. 예: {@code https://trading.internal.cloudnetwork.kr/internal/market/prices}
     * @param secret         서명 키. trading-service와 같은 값이다 (Secrets Manager wts/channel/market-feed)
     * @param connectTimeout 연결 대기 상한. 시세는 다음 것이 곧 오므로 짧게 둔다
     * @param readTimeout    응답 대기 상한
     */
    public record FeedRelay(boolean enabled, String url, String secret,
            Duration connectTimeout, Duration readTimeout) {

        public FeedRelay {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
            // 켰는데 주소나 키가 없으면 조용히 아무 데도 못 보낸다. 기동에서 막는다 (§52).
            if (enabled && (url == null || url.isBlank() || secret == null || secret.isBlank())) {
                throw new IllegalArgumentException(
                        "market.feed-relay.enabled=true 인데 MARKET_FEED_RELAY_URL 또는 MARKET_FEED_SECRET 이 비어 있다.");
            }
        }
    }
}
