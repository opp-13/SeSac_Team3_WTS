package com.team.wts.market.quote.adapter.out.provider.kis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KisRealtimeFrameTest {

    @Test
    @DisplayName("평문 실시간 프레임을 나눈다")
    void parsesPlainFrame() {
        KisRealtimeFrame frame = KisRealtimeFrame.parse("0|H0STCNT0|002|005930^140525^277750")
                .orElseThrow();

        assertThat(frame.encrypted()).isFalse();
        assertThat(frame.trId()).isEqualTo("H0STCNT0");
        assertThat(frame.count()).isEqualTo(2);
        assertThat(frame.body()).isEqualTo("005930^140525^277750");
    }

    @Test
    @DisplayName("암호화 표시를 읽는다. 시세 TR은 평문이지만 체결통보는 암호화된다")
    void readsEncryptedFlag() {
        assertThat(KisRealtimeFrame.parse("1|H0STCNI0|001|abc").orElseThrow().encrypted()).isTrue();
    }

    @Test
    @DisplayName("JSON 제어 메시지는 실시간 프레임이 아니다")
    void ignoresControlMessages() {
        assertThat(KisRealtimeFrame.parse("{\"header\":{\"tr_id\":\"PINGPONG\"}}")).isEmpty();
        assertThat(KisRealtimeFrame.parse("")).isEmpty();
        assertThat(KisRealtimeFrame.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("형식이 깨진 프레임은 버린다")
    void ignoresMalformedFrames() {
        assertThat(KisRealtimeFrame.parse("0|H0STCNT0")).isEmpty();
        assertThat(KisRealtimeFrame.parse("0|H0STCNT0|abc|body")).isEmpty();
        assertThat(KisRealtimeFrame.parse("0|H0STCNT0|0|body")).isEmpty();
    }

    @Test
    @DisplayName("한 프레임에 들어온 여러 레코드를 균등하게 자른다")
    void splitsMultipleRecords() {
        // 필드 3개짜리 레코드가 2개
        KisRealtimeFrame frame = KisRealtimeFrame.parse("0|X|002|a^b^c^d^e^f").orElseThrow();

        String[][] records = frame.records(3);

        assertThat(records).hasDimensions(2, 3);
        assertThat(records[0]).containsExactly("a", "b", "c");
        assertThat(records[1]).containsExactly("d", "e", "f");
    }

    @Test
    @DisplayName("필드 수가 모자라면 아무것도 돌려주지 않는다")
    void rejectsShortBody() {
        assertThat(KisRealtimeFrame.parse("0|X|002|a^b^c").orElseThrow().records(3)).isEmpty();
    }
}
