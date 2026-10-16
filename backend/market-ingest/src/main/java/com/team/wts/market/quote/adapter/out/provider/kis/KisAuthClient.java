package com.team.wts.market.quote.adapter.out.provider.kis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.team.wts.market.config.KisProperties;

/**
 * KIS 인증. (CLAUDE.md §20 – Approval Key)
 *
 * <p>두 가지가 필요하고 서로 다르다.
 * <ul>
 *   <li><b>접근토큰</b> – REST 호출용. {@code /oauth2/tokenP}, 유효기간 24시간</li>
 *   <li><b>approval_key</b> – WebSocket 등록용. {@code /oauth2/Approval}</li>
 * </ul>
 *
 * <p>접근토큰 발급에는 <b>1분당 1회</b> 제한이 있다. 반드시 캐시해야 한다.
 * 재기동이 잦은 개발 중에는 이 제한에 걸릴 수 있고, 그때는 발급이 실패한다.
 *
 * <p>토큰과 앱시크릿은 어떤 경우에도 로그에 남기지 않는다 (§40).
 */
public class KisAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KisAuthClient.class);

    /** 만료 직전에 갱신한다. 호출 도중 만료되는 일이 없도록 한다. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(10);

    private final RestClient rest;
    private final KisProperties properties;
    private final Clock clock;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;
    private volatile String approvalKey;
    private volatile Instant approvalKeyExpiresAt = Instant.EPOCH;

    public KisAuthClient(RestClient rest, KisProperties properties, Clock clock) {
        this.rest = rest;
        this.properties = properties;
        this.clock = clock;
    }

    /** REST 호출용 접근토큰. */
    public synchronized String accessToken() {
        if (accessToken != null && clock.instant().plus(REFRESH_MARGIN).isBefore(accessTokenExpiresAt)) {
            return accessToken;
        }
        Map<?, ?> body = post("/oauth2/tokenP", Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appKey(),
                "appsecret", properties.appSecret()));

        Object token = body.get("access_token");
        if (token == null) {
            throw new IllegalStateException("KIS 접근토큰 발급 실패: " + describe(body));
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 86400L;
        this.accessToken = token.toString();
        this.accessTokenExpiresAt = clock.instant().plusSeconds(expiresIn);
        log.info("KIS 접근토큰 발급 완료. 만료 {}", accessTokenExpiresAt);
        return accessToken;
    }

    /**
     * WebSocket 등록용 승인키.
     *
     * <p>세션마다 새로 받을 필요는 없지만 <b>영구적이지도 않다.</b> KIS는 만료 시각을 응답에
     * 주지 않으므로 우리가 유효기간을 정해 주기적으로 다시 받는다. 만료된 키로 등록하면
     * 오류 없이 시세만 조용히 끊긴다 — 가장 찾기 어려운 형태의 장애다.
     */
    public synchronized String approvalKey() {
        if (approvalKey != null && clock.instant().isBefore(approvalKeyExpiresAt)) {
            return approvalKey;
        }
        // 이 엔드포인트만 필드 이름이 secretkey 다. appsecret 으로 보내면 실패한다.
        Map<?, ?> body = post("/oauth2/Approval", Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appKey(),
                "secretkey", properties.appSecret()));

        Object key = body.get("approval_key");
        if (key == null) {
            throw new IllegalStateException("KIS approval_key 발급 실패: " + describe(body));
        }
        this.approvalKey = key.toString();
        this.approvalKeyExpiresAt = clock.instant().plus(properties.auth().approvalKeyTtl());
        log.info("KIS approval_key 발급 완료. {} 까지 사용", approvalKeyExpiresAt);
        return approvalKey;
    }

    private Map<?, ?> post(String path, Map<String, String> payload) {
        Map<?, ?> body = rest.post()
                .uri(path)
                .body(payload)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("KIS 응답이 비어 있다: " + path);
        }
        return body;
    }

    /** 오류 응답에서 코드와 메시지만 꺼낸다. 자격증명이 섞여 나가지 않게 한다. */
    private static String describe(Map<?, ?> body) {
        return "code=" + body.get("error_code") + " message=" + body.get("error_description");
    }
}
