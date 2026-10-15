package com.team.wts.common.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedSignatureTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final byte[] BODY = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

    private final FeedSignature signature = new FeedSignature("test-feed-secret");

    @Test
    @DisplayName("서명한 본문은 검증을 통과한다")
    void signsAndVerifies() {
        String header = signature.sign(BODY, NOW);

        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThat(signature.verify(header, BODY, NOW, SKEW)).isTrue();
    }

    @Test
    @DisplayName("본문이 1바이트라도 바뀌면 거절한다")
    void rejectsTamperedBody() {
        String header = signature.sign(BODY, NOW);
        byte[] tampered = "{\"events\":[ ]}".getBytes(StandardCharsets.UTF_8);

        assertThat(signature.verify(header, tampered, NOW, SKEW)).isFalse();
    }

    @Test
    @DisplayName("다른 키로 서명하면 거절한다")
    void rejectsAnotherKey() {
        String header = new FeedSignature("another-secret").sign(BODY, NOW);

        assertThat(signature.verify(header, BODY, NOW, SKEW)).isFalse();
    }

    @Test
    @DisplayName("시각이 허용 오차 경계 안이면 통과하고, 밖이면 거절한다")
    void enforcesClockSkew() {
        String header = signature.sign(BODY, NOW);

        assertThat(signature.verify(header, BODY, NOW.plusSeconds(60), SKEW)).isTrue();
        assertThat(signature.verify(header, BODY, NOW.minusSeconds(60), SKEW)).isTrue();
        assertThat(signature.verify(header, BODY, NOW.plusSeconds(61), SKEW)).isFalse();
        assertThat(signature.verify(header, BODY, NOW.minusSeconds(61), SKEW)).isFalse();
    }

    @Test
    @DisplayName("시각을 바꿔 붙이면 서명이 맞지 않는다")
    void rejectsReplacedTimestamp() {
        String header = signature.sign(BODY, NOW);
        String replaced = header.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 30));

        assertThat(signature.verify(replaced, BODY, NOW, SKEW)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("형식이 깨진 헤더는 예외 없이 거절한다")
    @ValueSource(strings = {"", "t=", "v1=", "t=abc,v1=xx", "t=1", "v1=AAAA", "garbage", "t=1,v1=!!!", ",,"})
    void rejectsMalformedHeader(String header) {
        assertThat(signature.verify(header, BODY, NOW, SKEW)).isFalse();
    }

    @Test
    @DisplayName("헤더가 없으면 거절한다")
    void rejectsMissingHeader() {
        assertThat(signature.verify(null, BODY, NOW, SKEW)).isFalse();
    }

    @Test
    @DisplayName("빈 키로는 만들 수 없다")
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new FeedSignature(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
