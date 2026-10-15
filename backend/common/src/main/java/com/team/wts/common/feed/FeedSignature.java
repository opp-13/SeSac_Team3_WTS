package com.team.wts.common.feed;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * tier 간 시세 중계 요청의 서명. (CLAUDE.md §65.4, ADR-0021)
 *
 * <p>헤더 형식: {@code t=<epoch seconds>,v1=<base64url(HMAC-SHA256(key, "<t>." + body))>}
 *
 * <p>본문 바이트 전체와 시각을 함께 서명한다. 본문이 1바이트라도 바뀌면 틀리고,
 * 허용 오차 밖의 오래된 요청은 서명이 맞아도 거절한다 (재전송 방지).
 * {@link com.team.wts.common.auth.MockAuthToken}과 같은 HmacSHA256 · 상수 시간 비교다.
 */
public final class FeedSignature {

    /** 서명을 싣는 HTTP 헤더. */
    public static final String HEADER = "X-Wts-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public FeedSignature(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("시세 중계 서명 키가 비어 있다.");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** {@code body}에 대한 헤더 값을 만든다. */
    public String sign(byte[] body, Instant now) {
        long t = now.getEpochSecond();
        return "t=" + t + ",v1=" + ENCODER.encodeToString(mac(t, body));
    }

    /**
     * 헤더가 이 본문에 대한 올바른 서명이고, 시각이 {@code now} 기준 {@code maxSkew} 안인지 본다.
     *
     * <p>실패 사유를 구분해 돌려주지 않는다. 호출자는 401 하나로 답한다.
     */
    public boolean verify(String header, byte[] body, Instant now, Duration maxSkew) {
        if (header == null || body == null) {
            return false;
        }
        Long t = null;
        byte[] presented = null;
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            try {
                if (name.equals("t")) {
                    t = Long.parseLong(value);
                } else if (name.equals("v1")) {
                    presented = DECODER.decode(value);
                }
            } catch (IllegalArgumentException e) {
                // NumberFormatException 도 여기로 온다. 형식이 깨진 헤더다.
                return false;
            }
        }
        if (t == null || presented == null) {
            return false;
        }
        long skew = Math.abs(now.getEpochSecond() - t);
        if (skew > maxSkew.toSeconds()) {
            return false;
        }
        return MessageDigest.isEqual(mac(t, body), presented);
    }

    private byte[] mac(long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            // HmacSHA256은 모든 JDK에 있고 키도 생성자에서 검증했다. 여기 오면 설정 문제다.
            throw new IllegalStateException("시세 중계 서명에 실패했다.", e);
        }
    }
}
