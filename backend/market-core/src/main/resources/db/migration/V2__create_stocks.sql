-- 종목 마스터 (CLAUDE.md §57.1)
--
-- 한국투자증권 종목정보 파일(kospi_code.mst, kosdaq_code.mst)을 동기화한다.
-- 1차 범위는 주권(증권그룹 ST)이다.
CREATE TABLE stocks (
    symbol         VARCHAR(20)    NOT NULL COMMENT '단축코드. 영문 대문자가 섞일 수 있다 (예: 0001A0)',
    standard_code  VARCHAR(12)    NOT NULL COMMENT '표준코드 (ISIN)',
    name           VARCHAR(100)   NOT NULL COMMENT '한글 종목명',
    market         VARCHAR(10)    NOT NULL COMMENT 'KOSPI | KOSDAQ',
    base_price     DECIMAL(19, 4) NULL     COMMENT '기준가. 파일에 0으로 오면(신규 상장 등) NULL',
    -- 검색 정렬용. 이름순만으로는 "삼성"을 쳤을 때 삼성E&A가 삼성전자보다 앞에 온다
    -- (utf8mb4_0900_ai_ci에서 영문이 한글보다 먼저다). 같은 순위 안에서는 시가총액이 큰 종목이 먼저다.
    market_cap     BIGINT         NULL     COMMENT '전일 기준 시가총액 (억원). 파일에 0이면 NULL',
    trading_halted BOOLEAN        NOT NULL COMMENT '거래정지',
    -- 파일에서 사라지면 false. 행은 지우지 않는다. 주문·포지션·관심종목이 이 종목코드를 계속 참조한다.
    listed         BOOLEAN        NOT NULL,
    synced_at      DATETIME(6)    NOT NULL COMMENT '마지막으로 파일에서 확인한 시각. UTC (CLAUDE.md §43)',
    PRIMARY KEY (symbol),
    KEY ix_stocks_listed_market (listed, market),
    CONSTRAINT ck_stocks_market CHECK (market IN ('KOSPI', 'KOSDAQ')),
    CONSTRAINT ck_stocks_base_price_positive CHECK (base_price IS NULL OR base_price > 0),
    CONSTRAINT ck_stocks_market_cap_positive CHECK (market_cap IS NULL OR market_cap > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
