package com.team.wts.market.quote.synthetic;

import java.nio.charset.StandardCharsets;

/**
 * 입력이 같으면 어느 JVM · OS · 실행에서도 같은 값을 내는 64비트 해시. (CLAUDE.md §67)
 *
 * <p>모의 시세의 모든 "무작위"는 여기서 나온다. {@link java.util.Random}이나 {@link String#hashCode()}에
 * 기대지 않는다. 전자는 상태가 있어 호출 순서에 따라 값이 바뀌고, 후자는 분포가 나쁘다.
 * 종목코드는 FNV-1a 64로, 나머지 성분은 SplitMix64 마무리 함수로 섞는다. long 연산뿐이라 비트 단위로 재현된다.
 */
final class StableHash {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private StableHash() {
    }

    /** (seed, 종목코드, 성분들)의 해시. 성분의 순서가 다르면 다른 값이다. */
    static long of(long seed, String symbol, long... parts) {
        long hash = mix(seed ^ fnv1a64(symbol));
        for (long part : parts) {
            hash = mix(hash + GOLDEN_GAMMA + mix(part));
        }
        return hash;
    }

    /** [0, bound) */
    static long below(long hash, long bound) {
        return Math.floorMod(hash, bound);
    }

    /** [-amplitude, amplitude] */
    static long symmetric(long hash, long amplitude) {
        return Math.floorMod(hash, 2 * amplitude + 1) - amplitude;
    }

    static long fnv1a64(String text) {
        long hash = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** SplitMix64 마무리 함수 (Steele, Lea, Flood 2014). */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
