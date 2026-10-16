package com.team.wts.market.stock.adapter.out.master;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.team.wts.common.market.StockSymbol;
import com.team.wts.market.stock.domain.Market;
import com.team.wts.market.stock.domain.Stock;

/**
 * 한국투자증권 종목정보 파일(kospi_code.mst, kosdaq_code.mst) 파서. (CLAUDE.md §57.1)
 *
 * <p>한 줄은 두 부분이다.
 * <pre>
 *   앞부분 (가변)  단축코드 9 · 표준코드 12 · 한글종목명 (가변)
 *   뒷부분 (고정)  KOSPI 227자, KOSDAQ 221자. 증권그룹 · 기준가 · 거래정지 …
 * </pre>
 * 종목명 길이가 제각각이라 <b>줄 끝에서부터 고정 길이를 잘라낸다</b>. 뒷부분은 ASCII라 문자 수가 곧 바이트 수다.
 *
 * <p>길이와 오프셋은 KIS open-trading-api 저장소의 stocks_info 예제(kis_kospi_code_mst.py,
 * kis_kosdaq_code_mst.py)와 헤더(종목마스터정보(코스피).h)로 확인했다. 예제의 228 · 222는
 * 줄바꿈 문자까지 센 값이다. 줄바꿈을 뗀 데이터는 227 · 221이다.
 *
 * <p>형식이 예상과 다르면 예외를 던진다. 잘못 자른 값을 마스터에 넣느니 동기화를 멈추고
 * 기존 마스터를 지키는 편이 낫다.
 */
final class KisStockMasterParser {

    /** 파이썬 예제의 cp949. 자바에서는 MS949 (x-windows-949). */
    static final Charset CP949 = Charset.forName("MS949");

    private static final int SYMBOL_END = 9;
    private static final int STANDARD_CODE_END = 21;
    private static final int BASE_PRICE_LENGTH = 9;
    private static final int MARKET_CAP_LENGTH = 9;
    private static final int STANDARD_CODE_LENGTH = 12;
    /** 증권그룹구분코드. ST = 주권. EF(ETF) · EN(ETN) · RT(리츠) 등은 1차 범위 밖이다. */
    private static final String STOCK_GROUP = "ST";

    enum Layout {
        KOSPI(Market.KOSPI, "kospi_code.mst", 227, 41, 60, 212),
        KOSDAQ(Market.KOSDAQ, "kosdaq_code.mst", 221, 36, 55, 206);

        final Market market;
        final String fileName;
        final int tailLength;
        final int basePriceOffset;
        final int haltedOffset;
        /** 전일 기준 시가총액(억원) */
        final int marketCapOffset;

        Layout(Market market, String fileName, int tailLength, int basePriceOffset, int haltedOffset,
               int marketCapOffset) {
            this.market = market;
            this.fileName = fileName;
            this.tailLength = tailLength;
            this.basePriceOffset = basePriceOffset;
            this.haltedOffset = haltedOffset;
            this.marketCapOffset = marketCapOffset;
        }
    }

    /** 주권만 돌려준다. */
    List<Stock> parse(InputStream in, Layout layout) {
        List<Stock> stocks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, CP949))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                Stock stock = parseLine(line, layout, lineNumber);
                if (stock != null) {
                    stocks.add(stock);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(layout.fileName + " 읽기 실패", e);
        }
        return stocks;
    }

    private Stock parseLine(String line, Layout layout, int lineNumber) {
        if (line.length() <= STANDARD_CODE_END + layout.tailLength) {
            throw invalid(layout, lineNumber, "줄이 너무 짧다 (" + line.length() + "자)");
        }
        String head = line.substring(0, line.length() - layout.tailLength);
        String tail = line.substring(line.length() - layout.tailLength);

        if (!tail.startsWith(STOCK_GROUP)) {
            return null;
        }

        String symbol = head.substring(0, SYMBOL_END).strip();
        String standardCode = head.substring(SYMBOL_END, STANDARD_CODE_END).strip();
        String name = head.substring(STANDARD_CODE_END).strip();
        String basePrice = tail.substring(layout.basePriceOffset, layout.basePriceOffset + BASE_PRICE_LENGTH);
        char halted = tail.charAt(layout.haltedOffset);
        String marketCap = tail.substring(layout.marketCapOffset, layout.marketCapOffset + MARKET_CAP_LENGTH);

        if (!StockSymbol.isValid(symbol)) {
            throw invalid(layout, lineNumber, "종목코드 형식이 아니다: '" + symbol + "'");
        }
        if (standardCode.length() != STANDARD_CODE_LENGTH) {
            throw invalid(layout, lineNumber, "표준코드가 12자가 아니다: '" + standardCode + "'");
        }
        if (name.isEmpty()) {
            throw invalid(layout, lineNumber, "종목명이 비었다: " + symbol);
        }
        if (!basePrice.chars().allMatch(Character::isDigit)) {
            throw invalid(layout, lineNumber, "기준가가 숫자가 아니다: '" + basePrice + "'");
        }
        if (!marketCap.chars().allMatch(Character::isDigit)) {
            throw invalid(layout, lineNumber, "시가총액이 숫자가 아니다: '" + marketCap + "'");
        }
        if (halted != 'Y' && halted != 'N') {
            throw invalid(layout, lineNumber, "거래정지 값이 Y/N이 아니다: '" + halted + "'");
        }

        BigDecimal price = new BigDecimal(basePrice);
        // 신규 상장 직후처럼 기준가가 0으로 오는 종목이 있다. 0원으로 두면 가격제한폭 계산이 무너진다.
        BigDecimal knownPrice = price.signum() == 0 ? null : price;
        long cap = Long.parseLong(marketCap);
        return Stock.listed(symbol, standardCode, name, layout.market, knownPrice,
                cap == 0 ? null : cap, halted == 'Y');
    }

    private static IllegalStateException invalid(Layout layout, int lineNumber, String reason) {
        return new IllegalStateException(
                "종목정보 파일 형식이 예상과 다르다: " + layout.fileName + " " + lineNumber + "행 – " + reason);
    }
}
