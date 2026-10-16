package com.team.wts.market.stock.adapter.out.master;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.team.wts.market.config.MarketProperties;
import com.team.wts.market.stock.adapter.out.master.KisStockMasterParser.Layout;
import com.team.wts.market.stock.domain.Stock;
import com.team.wts.market.stock.domain.StockMasterSource;

/**
 * 한국투자증권 종목정보 파일을 가져온다. (CLAUDE.md §57.1)
 *
 * <p>내려받는 파일과 저장소의 스냅샷은 같은 zip이다. 그래서 둘 다 같은 파서를 탄다.
 * 스냅샷은 {@code infra/scripts/update-stock-master-snapshot.sh}로 다시 받는다.
 *
 * <p>인증이 필요 없는 공개 파일이다. KIS 앱키를 쓰지 않는다.
 */
@Component
public class KisStockMasterFiles implements StockMasterSource {

    private static final Logger log = LoggerFactory.getLogger(KisStockMasterFiles.class);
    private static final String SNAPSHOT_DIRECTORY = "master/";
    private static final String ZIP_SUFFIX = ".zip";

    private final KisStockMasterParser parser = new KisStockMasterParser();
    private final MarketProperties.Master config;
    private final HttpClient http;

    public KisStockMasterFiles(MarketProperties properties) {
        this.config = properties.master();
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.downloadTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<Stock> download() {
        List<Stock> stocks = new ArrayList<>();
        for (Layout layout : Layout.values()) {
            URI uri = URI.create(config.downloadBaseUrl() + layout.fileName + ZIP_SUFFIX);
            try (InputStream zip = get(uri)) {
                stocks.addAll(readZip(zip, layout));
            } catch (IOException e) {
                throw new UncheckedIOException("종목정보 파일 다운로드 실패: " + uri, e);
            }
        }
        return stocks;
    }

    @Override
    public List<Stock> snapshot() {
        List<Stock> stocks = new ArrayList<>();
        for (Layout layout : Layout.values()) {
            ClassPathResource resource = new ClassPathResource(SNAPSHOT_DIRECTORY + layout.fileName + ZIP_SUFFIX);
            try (InputStream zip = resource.getInputStream()) {
                stocks.addAll(readZip(zip, layout));
            } catch (IOException e) {
                throw new UncheckedIOException("종목정보 스냅샷을 읽지 못했다: " + resource.getPath(), e);
            }
        }
        return stocks;
    }

    private InputStream get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.downloadTimeout()).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("다운로드가 중단됐다", e);
        }
    }

    /**
     * zip 안의 .mst 하나를 읽는다. zip이 아니거나(오류 페이지 등) 기대한 파일이 없으면 실패다.
     * 잘린 파일은 ZipInputStream이 CRC · EOF 오류로 잡는다.
     */
    private List<Stock> readZip(InputStream in, Layout layout) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(layout.fileName)) {
                    List<Stock> stocks = parser.parse(zip, layout);
                    if (stocks.isEmpty()) {
                        throw new IllegalStateException(layout.fileName + "에 주권이 하나도 없다");
                    }
                    log.info("종목정보 {}: 주권 {}개", layout.fileName, stocks.size());
                    return stocks;
                }
            }
        }
        throw new IOException("zip 안에 " + layout.fileName + " 이 없다");
    }
}
