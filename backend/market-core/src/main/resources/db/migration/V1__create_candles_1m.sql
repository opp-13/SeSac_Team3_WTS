-- 1분봉 (CLAUDE.md §22, §23)
--
-- market.price.updated 이벤트를 집계해 만든다.
-- 분이 바뀌는 순간 직전 분의 봉이 확정되어 여기 저장된다.
CREATE TABLE candles_1m (
    symbol    VARCHAR(20)    NOT NULL,
    open_time DATETIME(6)    NOT NULL COMMENT '해당 분의 시작 시각. UTC (CLAUDE.md §43)',
    open      DECIMAL(19, 4) NOT NULL,
    high      DECIMAL(19, 4) NOT NULL,
    low       DECIMAL(19, 4) NOT NULL,
    close     DECIMAL(19, 4) NOT NULL,
    -- 해당 분 동안의 거래량. 누적 거래량의 차이로 계산하므로 이벤트가 중복돼도 부풀지 않는다 (§17).
    volume    BIGINT         NOT NULL,
    PRIMARY KEY (symbol, open_time),
    CONSTRAINT ck_candles_1m_high_is_highest CHECK (high >= open AND high >= close AND high >= low),
    CONSTRAINT ck_candles_1m_low_is_lowest CHECK (low <= open AND low <= close),
    CONSTRAINT ck_candles_1m_volume_non_negative CHECK (volume >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
