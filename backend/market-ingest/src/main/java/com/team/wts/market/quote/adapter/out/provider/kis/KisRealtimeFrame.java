package com.team.wts.market.quote.adapter.out.provider.kis;

import java.util.Optional;

/**
 * KIS 실시간 WebSocket 프레임. (CLAUDE.md §20)
 *
 * <pre>
 * 0|H0STCNT0|002|005930^140525^277750^2^3750^...
 * │ │        │   └ 본문. 레코드가 여러 개면 ^ 로 이어 붙어 있다
 * │ │        └ 레코드 건수
 * │ └ TR ID
 * └ 암호화 여부 (0 평문 / 1 암호화)
 * </pre>
 *
 * <p>제어 메시지(구독 응답, PINGPONG)는 JSON이라 {@code {} 로 시작한다. 여기서 다루지 않는다.
 */
public record KisRealtimeFrame(boolean encrypted, String trId, int count, String body) {

    private static final int PARTS = 4;

    /** 실시간 데이터 프레임이 아니면 빈 값. */
    public static Optional<KisRealtimeFrame> parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) == '{') {
            return Optional.empty();
        }
        String[] parts = raw.split("\\|", PARTS);
        if (parts.length < PARTS) {
            return Optional.empty();
        }
        int count;
        try {
            count = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (count <= 0) {
            return Optional.empty();
        }
        return Optional.of(new KisRealtimeFrame("1".equals(parts[0]), parts[1], count, parts[3]));
    }

    /**
     * 본문을 레코드 단위로 자른다.
     *
     * <p>한 프레임에 같은 종류의 레코드가 여러 개 들어온다. 필드 개수가 고정이라
     * 전체를 {@code ^}로 나눈 뒤 균등 분할하면 된다.
     *
     * @param fieldsPerRecord 레코드 하나의 필드 수
     * @return 레코드별 필드 배열. 길이가 맞지 않으면 빈 배열
     */
    public String[][] records(int fieldsPerRecord) {
        String[] fields = body.split("\\^", -1);
        if (fields.length < fieldsPerRecord * count) {
            return new String[0][];
        }
        String[][] records = new String[count][];
        for (int i = 0; i < count; i++) {
            String[] record = new String[fieldsPerRecord];
            System.arraycopy(fields, i * fieldsPerRecord, record, 0, fieldsPerRecord);
            records[i] = record;
        }
        return records;
    }
}
