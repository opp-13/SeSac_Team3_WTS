package com.team.wts.market.quote.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 모의 시세의 해시. (CLAUDE.md §67) 값이 실행 · 플랫폼마다 같아야 시세가 재현된다. */
class StableHashTest {

    @Test
    @DisplayName("FNV-1a 64 표준 값과 같다")
    void fnv1aMatchesReferenceVectors() {
        // 표준 테스트 벡터 (http://www.isthe.com/chongo/tech/comp/fnv/)
        assertThat(StableHash.fnv1a64("")).isEqualTo(0xcbf29ce484222325L);
        assertThat(StableHash.fnv1a64("a")).isEqualTo(0xaf63dc4c8601ec8cL);
        assertThat(StableHash.fnv1a64("foobar")).isEqualTo(0x85944171f73967e8L);
    }

    @Test
    @DisplayName("골든 값: 섞는 방식이 바뀌면 깨진다")
    void goldenValue() {
        assertThat(StableHash.of(0L, "005930", 1L, 2L, 3L)).isEqualTo(GOLDEN);
    }

    private static final long GOLDEN = -4115943328679079723L;

    @Test
    @DisplayName("성분이나 순서가 다르면 다른 값이다")
    void distinguishesPartsAndOrder() {
        long base = StableHash.of(0L, "005930", 1L, 2L);
        assertThat(StableHash.of(0L, "005930", 2L, 1L)).isNotEqualTo(base);
        assertThat(StableHash.of(1L, "005930", 1L, 2L)).isNotEqualTo(base);
        assertThat(StableHash.of(0L, "000660", 1L, 2L)).isNotEqualTo(base);
        assertThat(StableHash.of(0L, "005930", 1L, 2L)).isEqualTo(base);
    }

    @Test
    @DisplayName("범위 함수는 음수 해시에서도 범위 안이다")
    void boundedHelpers() {
        for (long h : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) {
            assertThat(StableHash.below(h, 990L)).isBetween(0L, 989L);
            assertThat(StableHash.symmetric(h, 600L)).isBetween(-600L, 600L);
        }
    }
}
