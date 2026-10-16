package com.team.wts.market.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 한국투자증권 OpenAPI 설정. (CLAUDE.md §19, §20, §33)
 *
 * <p>자격증명은 환경변수로만 들어온다. 기본값을 두지 않는다 (§52).
 * {@code market.provider=mock} 이면 이 값들은 쓰이지 않는다.
 *
 * @param environment 모의투자(VTS)만 있다. 이 서비스는 무조건 모의투자다 (CLAUDE.md §62.3).
 *                    {@code KIS_ENVIRONMENT=real}이면 값을 바인딩하지 못해 기동이 멈춘다. 의도한 동작이다.
 */
@ConfigurationProperties(prefix = "kis")
public record KisProperties(
        Environment environment,
        String appKey,
        String appSecret,
        Rest rest,
        Websocket websocket,
        Auth auth) {

    public KisProperties {
        environment = environment == null ? Environment.VTS : environment;
        rest = rest == null ? new Rest(null, null, null, null) : rest;
        websocket = websocket == null ? new Websocket(null, 0, null, null, null, null, null) : websocket;
        auth = auth == null ? new Auth(null) : auth;
    }

    /**
     * @param approvalKeyTtl 승인키를 다시 받기까지의 간격. KIS가 만료 시각을 응답에 주지 않아
     *                       우리가 정한다. 실제 유효기간(24시간)보다 넉넉히 짧게 잡는다.
     *                       만료된 키로는 재연결 시 등록이 조용히 실패해 시세가 끊긴다.
     */
    public record Auth(Duration approvalKeyTtl) {

        public Auth {
            approvalKeyTtl = approvalKeyTtl == null ? Duration.ofHours(12) : approvalKeyTtl;
        }
    }

    /**
     * 접속 도메인. 실전투자(REAL)는 두지 않는다. 시세만 받으므로 모의투자 앱키로 충분하고,
     * 실전 앱키가 코드 경로에 들어올 일 자체를 없앤다 (CLAUDE.md §62.3).
     */
    public enum Environment {
        /** 모의투자. 국내주식 실시간 체결가·호가를 지원한다. */
        VTS("https://openapivts.koreainvestment.com:29443", "ws://ops.koreainvestment.com:31000");

        private final String restBaseUrl;
        private final String websocketUrl;

        Environment(String restBaseUrl, String websocketUrl) {
            this.restBaseUrl = restBaseUrl;
            this.websocketUrl = websocketUrl;
        }

        public String restBaseUrl() {
            return restBaseUrl;
        }

        public String websocketUrl() {
            return websocketUrl;
        }
    }

    /** REST 기본 주소. 설정에 없으면 {@code environment}의 기본값을 쓴다. */
    public String restBaseUrl() {
        return rest.baseUrl() == null || rest.baseUrl().isBlank()
                ? environment.restBaseUrl()
                : rest.baseUrl();
    }

    public String websocketUrl() {
        return websocket.url() == null || websocket.url().isBlank()
                ? environment.websocketUrl()
                : websocket.url();
    }

    /**
     * @param requestInterval 연속 호출 사이 최소 간격. KIS는 초당 거래건수를 제한한다
     *                        (모의투자가 실전보다 빡빡하다). 기동 시 종목별 시세를 연달아
     *                        조회하면 바로 걸리므로 간격을 둔다. 모의투자는 초당 2건이라
     *                        500ms로는 경계에 걸린다.
     */
    public record Rest(String baseUrl, Duration connectTimeout, Duration readTimeout,
            Duration requestInterval) {

        public Rest {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            requestInterval = requestInterval == null ? Duration.ofSeconds(1) : requestInterval;
        }
    }

    /**
     * @param subscriptionLimit 세션당 등록 가능한 실시간 건수. 종목 하나에 체결가·호가 둘을
     *                          등록하므로 종목 수의 두 배가 든다. 숫자를 코드에 박지 않는다 (§20).
     * @param maxTextMessageSize 한 프레임의 최대 크기. KIS는 체결이 몰리면 여러 건을 한 프레임에
     *                           묶어 보낸다. 기본 8KB로는 부족해 연결이 1009로 끊긴다.
     * @param staleTimeout       이 시간 동안 <b>아무 메시지도</b> 오지 않으면 연결이 죽은 것으로
     *                           보고 다시 붙는다. 서버가 heartbeat(PINGPONG)를 주기적으로 보내므로
     *                           장 마감 후 시세가 없어도 이 시계는 계속 돈다 (§20).
     * @param healthLogInterval  스트림 상태를 남기는 주기. 연결이 살아 있는지, 데이터가 오는지를
     *                           평소에 기록해 두지 않으면 끊겼을 때 원인을 되짚을 수 없다.
     */
    public record Websocket(
            String url,
            int subscriptionLimit,
            Duration reconnectInitialDelay,
            Duration reconnectMaxDelay,
            DataSize maxTextMessageSize,
            Duration staleTimeout,
            Duration healthLogInterval) {

        public Websocket {
            subscriptionLimit = subscriptionLimit <= 0 ? 40 : subscriptionLimit;
            reconnectInitialDelay =
                    reconnectInitialDelay == null ? Duration.ofSeconds(1) : reconnectInitialDelay;
            reconnectMaxDelay =
                    reconnectMaxDelay == null ? Duration.ofSeconds(30) : reconnectMaxDelay;
            maxTextMessageSize =
                    maxTextMessageSize == null ? DataSize.ofKilobytes(512) : maxTextMessageSize;
            staleTimeout = staleTimeout == null ? Duration.ofSeconds(90) : staleTimeout;
            healthLogInterval =
                    healthLogInterval == null ? Duration.ofMinutes(1) : healthLogInterval;
        }
    }
}
