package com.team.wts.market.quote.adapter.out.provider.kis;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import com.team.wts.market.config.KisProperties;

/**
 * KIS 실시간 WebSocket 연결. (CLAUDE.md §20)
 *
 * <p>고려해야 할 것이 §20에 그대로 적혀 있다.
 * <ul>
 *   <li>Approval Key – 등록 메시지 헤더에 넣는다</li>
 *   <li>다중 종목 구독 – 종목 하나에 체결가·호가 두 건이 등록된다</li>
 *   <li>heartbeat – 서버가 보내는 {@code PINGPONG}을 <b>그대로 되돌려</b>야 연결이 유지된다</li>
 *   <li>reconnect / backoff – 끊기면 지수 백오프로 다시 붙는다</li>
 *   <li>subscription restore – 다시 붙으면 구독을 전부 재등록한다</li>
 * </ul>
 *
 * <p>암호화 프레임(체결통보 등)은 다루지 않는다. 시세 TR은 평문으로 온다.
 */
public class KisWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KisWebSocketClient.class);

    /** 등록 / 해제 */
    private static final String REGISTER = "1";
    private static final String UNREGISTER = "2";

    /** {@code "rt_cd":"0"} = 정상. 그 외는 등록 실패다. */
    private static final Pattern OK_RESULT = Pattern.compile("\"rt_cd\"\\s*:\\s*\"0\"");

    /** 구독 성공 응답에 실려 오는 암호화 키. 로그에 남기지 않는다 (CLAUDE.md §40). */
    private static final Pattern SECRET_FIELD =
            Pattern.compile("\"(iv|key)\"\\s*:\\s*\"[^\"]*\"");

    /** 종목 하나에 등록하는 실시간 TR. 등록 건수는 이 개수만큼 늘어난다. */
    private static final List<String> TR_IDS =
            List.of(KisRealtimeDecoder.TR_PRICE, KisRealtimeDecoder.TR_ORDER_BOOK);

    private final KisProperties properties;
    private final KisAuthClient auth;
    private final FrameHandler frameHandler;
    private final StandardWebSocketClient client;
    private final Clock clock;

    private final Set<String> symbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile WebSocketSession session;
    private volatile ScheduledExecutorService reconnector;
    private volatile long reconnectDelayMillis;
    /** 연결 시도가 진행 중인지. 같은 앱키로 두 세션을 열면 KIS가 하나를 끊는다. */
    private final AtomicBoolean connecting = new AtomicBoolean();
    /** 마지막으로 무언가 받은 시각. 데이터든 heartbeat든 pong이든 상관없다. */
    private volatile Instant lastMessageAt = Instant.EPOCH;
    /** 마지막으로 <b>시세</b>를 받은 시각. 연결 생존과 데이터 흐름을 구분해서 본다. */
    private volatile Instant lastFrameAt = Instant.EPOCH;

    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong heartbeatCount = new AtomicLong();
    private volatile long lastReportedFrameCount;

    /** 수신한 실시간 프레임을 넘겨받는 쪽. */
    public interface FrameHandler {
        void onFrame(KisRealtimeFrame frame);
    }

    public KisWebSocketClient(KisProperties properties, KisAuthClient auth,
                              FrameHandler frameHandler, Clock clock) {
        this.properties = properties;
        this.auth = auth;
        this.frameHandler = frameHandler;
        this.clock = clock;
        this.reconnectDelayMillis = properties.websocket().reconnectInitialDelay().toMillis();

        // 기본 텍스트 버퍼는 8KB다. 체결이 몰리면 KIS가 여러 건을 한 프레임에 묶어 보내는데
        // 그때 버퍼를 넘겨 연결이 1009로 끊긴다. 컨테이너 기본값을 올려 둔다.
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(
                (int) properties.websocket().maxTextMessageSize().toBytes());
        this.client = new StandardWebSocketClient(container);
    }

    public void start(Collection<String> initialSymbols) {
        symbols.addAll(initialSymbols);
        requireWithinSubscriptionLimit(symbols.size());
        // 승인키를 미리 받아 둔다. 연결 콜백 안에서 받으면 REST 호출이 핸드셰이크 완료를
        // 막아 연결이 실패한 것처럼 보이고, 그 결과 두 번째 세션이 열린다.
        auth.approvalKey();
        running.set(true);
        reconnector = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kis-ws-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        lastMessageAt = clock.instant();
        connect();

        // 연결이 살아 있는데 아무것도 오지 않는 상태를 스스로 알아채지 못하면 영원히 멈춘다.
        long checkMillis = Math.max(1000L, properties.websocket().staleTimeout().toMillis() / 3);
        reconnector.scheduleWithFixedDelay(
                this::reconnectIfSilent, checkMillis, checkMillis, TimeUnit.MILLISECONDS);

        long healthMillis = properties.websocket().healthLogInterval().toMillis();
        reconnector.scheduleWithFixedDelay(
                this::logStreamHealth, healthMillis, healthMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 스트림 상태를 주기적으로 남긴다.
     *
     * <p>끊긴 뒤에 원인을 찾으려면 "끊기기 전에 무엇이 오고 있었는지"가 있어야 한다.
     * 연결 여부, 프레임 유입량, 마지막 수신 경과를 한 줄로 남긴다.
     */
    private void logStreamHealth() {
        if (!running.get()) {
            return;
        }
        WebSocketSession current = this.session;
        long total = frameCount.get();
        long delta = total - lastReportedFrameCount;
        lastReportedFrameCount = total;

        Instant now = clock.instant();
        log.info("KIS 스트림: 연결={} 구독={}종목 프레임={}건(+{}) heartbeat={}건"
                        + " 마지막수신={}초전 마지막시세={}",
                current != null && current.isOpen(), symbols.size(), total, delta,
                heartbeatCount.get(), Duration.between(lastMessageAt, now).toSeconds(),
                lastFrameAt.equals(Instant.EPOCH)
                        ? "없음"
                        : Duration.between(lastFrameAt, now).toSeconds() + "초전");
    }

    /**
     * 조용한 연결을 끊는다. (CLAUDE.md §20 – heartbeat)
     *
     * <p>TCP는 살아 있는데 서버가 더 이상 보내지 않는 상태(half-open, 세션 좀비)는
     * close 이벤트가 오지 않아 재연결 로직이 돌지 않는다. 직접 끊어 재연결을 일으킨다.
     *
     * <p>판단 기준을 "데이터"가 아니라 "메시지"로 잡은 이유가 있다. 장 마감 후에는 시세가
     * 없지만 서버의 PINGPONG은 계속 온다. 데이터 기준으로 보면 밤새 재연결만 반복한다.
     */
    private void reconnectIfSilent() {
        WebSocketSession current = this.session;
        if (!running.get() || current == null || !current.isOpen()) {
            return;
        }
        Duration silence = Duration.between(lastMessageAt, clock.instant());
        if (silence.compareTo(properties.websocket().staleTimeout()) < 0) {
            return;
        }
        log.warn("KIS WebSocket이 {}초째 조용하다(프레임 {}건, heartbeat {}건 수신 후). 끊고 다시 붙는다",
                silence.toSeconds(), frameCount.get(), heartbeatCount.get());
        closeQuietly();
    }

    public void stop() {
        running.set(false);
        ScheduledExecutorService executor = this.reconnector;
        if (executor != null) {
            executor.shutdownNow();
        }
        closeQuietly();
    }

    public void subscribe(String symbol) {
        if (!symbols.add(symbol)) {
            return;
        }
        requireWithinSubscriptionLimit(symbols.size());
        register(symbol, REGISTER);
    }

    public void unsubscribe(String symbol) {
        if (symbols.remove(symbol)) {
            register(symbol, UNREGISTER);
        }
    }

    /**
     * 종목 하나가 등록 두 건(체결가·호가)을 쓴다. 한도를 넘기면 뒤쪽 등록이 조용히 실패하므로
     * 미리 막는다.
     */
    private void requireWithinSubscriptionLimit(int symbolCount) {
        int required = symbolCount * TR_IDS.size();
        int limit = properties.websocket().subscriptionLimit();
        if (required > limit) {
            throw new IllegalStateException("KIS 실시간 등록 한도 초과: 종목 " + symbolCount
                    + "개에 등록 " + required + "건이 필요하지만 한도는 " + limit
                    + "건이다 (kis.websocket.subscription-limit)");
        }
    }

    /**
     * 연결을 건다. 완료를 <b>기다리지 않는다</b>.
     *
     * <p>핸드셰이크가 끝나면 {@code afterConnectionEstablished}가, 실패하면 아래 콜백이
     * 처리한다. 기다리다 시간이 지나 재연결을 걸면 이미 열린 세션과 겹쳐
     * {@code ALREADY IN USE appkey}로 둘 다 망가진다.
     */
    private void connect() {
        if (!running.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        String url = properties.websocketUrl();
        try {
            client.execute(new Handler(), null, URI.create(url))
                    .whenComplete((connected, error) -> {
                        connecting.set(false);
                        if (error != null) {
                            log.warn("KIS WebSocket 연결 실패: {} ({})", url, error.getMessage());
                            scheduleReconnect();
                        }
                    });
        } catch (RuntimeException e) {
            connecting.set(false);
            log.warn("KIS WebSocket 연결 시도 실패: {} ({})", url, e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        ScheduledExecutorService executor = this.reconnector;
        if (!running.get() || executor == null || executor.isShutdown()) {
            return;
        }
        WebSocketSession current = this.session;
        if (current != null && current.isOpen()) {
            // 이미 붙어 있다. 두 번째 세션을 열면 KIS가 앱키 중복으로 끊는다.
            return;
        }
        long delay = reconnectDelayMillis;
        log.info("KIS WebSocket {}ms 뒤 재연결", delay);
        executor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        // 지수 백오프. 서버가 내려가 있을 때 재연결로 몰아붙이지 않는다.
        reconnectDelayMillis =
                Math.min(delay * 2, properties.websocket().reconnectMaxDelay().toMillis());
    }

    /** 한 종목의 체결가·호가를 함께 등록하거나 해제한다. */
    private void register(String symbol, String type) {
        for (String trId : TR_IDS) {
            send(registrationMessage(symbol, type, trId));
        }
    }

    private void send(String payload) {
        WebSocketSession current = this.session;
        if (current == null || !current.isOpen()) {
            // 연결이 없으면 보내지 않는다. 다시 붙을 때 구독 전체가 복원된다.
            return;
        }
        try {
            // 세션은 동시 전송을 허용하지 않는다.
            synchronized (current) {
                current.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.warn("KIS WebSocket 전송 실패: {}", e.getMessage());
        }
    }

    private String registrationMessage(String symbol, String type, String trId) {
        // 승인키가 들어가므로 이 문자열은 로그에 남기지 않는다 (CLAUDE.md §40).
        return """
                {"header":{"approval_key":"%s","custtype":"P","tr_type":"%s","content-type":"utf-8"},\
                "body":{"input":{"tr_id":"%s","tr_key":"%s"}}}"""
                .formatted(auth.approvalKey(), type, trId, symbol);
    }

    private void closeQuietly() {
        WebSocketSession current = this.session;
        this.session = null;
        if (current != null && current.isOpen()) {
            try {
                current.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.debug("KIS WebSocket 종료 중 오류: {}", e.getMessage());
            }
        }
    }

    private class Handler implements WebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession established) {
            established.setTextMessageSizeLimit(
                    (int) properties.websocket().maxTextMessageSize().toBytes());
            session = established;
            lastMessageAt = clock.instant();
            reconnectDelayMillis = properties.websocket().reconnectInitialDelay().toMillis();
            log.info("KIS WebSocket 연결됨: {} (종목 {}개 등록)",
                    properties.websocketUrl(), symbols.size());
            // 재연결이면 여기서 구독이 복원된다 (§20 – subscription restore).
            symbols.forEach(symbol -> register(symbol, REGISTER));
        }

        @Override
        public void handleMessage(WebSocketSession current, WebSocketMessage<?> message) {
            // 텍스트가 아닌 프레임(pong 등)도 연결이 살아 있다는 증거다.
            // 텍스트만 세면 조용한 구간에 멀쩡한 연결을 끊게 된다.
            lastMessageAt = clock.instant();

            if (!(message.getPayload() instanceof String payload)) {
                return;
            }
            // heartbeat. 받은 그대로 돌려줘야 연결이 유지된다 (§20).
            if (payload.startsWith("{") && payload.contains("PINGPONG")) {
                heartbeatCount.incrementAndGet();
                send(payload);
                return;
            }
            if (payload.startsWith("{")) {
                logControlMessage(payload);
                return;
            }
            frameCount.incrementAndGet();
            lastFrameAt = lastMessageAt;
            KisRealtimeFrame.parse(payload).ifPresent(frame -> {
                if (frame.encrypted()) {
                    // 시세 TR은 평문이다. 암호화 프레임은 다루지 않는다.
                    log.warn("암호화된 실시간 프레임은 처리하지 않는다: tr_id={}", frame.trId());
                    return;
                }
                frameHandler.onFrame(frame);
            });
        }

        /**
         * 구독 응답과 오류.
         *
         * <p>구독 성공 응답에는 암호화 TR 복호화용 {@code iv}와 {@code key}가 들어 있다.
         * 그대로 남기면 로그에 비밀값이 쌓인다 (CLAUDE.md §40).
         */
        private void logControlMessage(String payload) {
            String redacted = SECRET_FIELD.matcher(payload).replaceAll("\"$1\":\"***\"");
            String trimmed = redacted.length() > 300 ? redacted.substring(0, 300) + "…" : redacted;
            if (OK_RESULT.matcher(payload).find()) {
                // 구독 성공은 종목 × TR 수만큼 쏟아진다. 평소에는 요약 한 줄이면 된다.
                log.debug("KIS 제어 메시지: {}", trimmed);
            } else {
                // 등록 실패는 시세가 조용히 끊기는 원인이 된다. 반드시 눈에 띄어야 한다.
                log.warn("KIS 제어 메시지(실패): {}", trimmed);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession current, Throwable exception) {
            log.warn("KIS WebSocket 오류: {}", exception.getMessage());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession current, CloseStatus status) {
            session = null;
            log.info("KIS WebSocket 끊김: {}", status);
            scheduleReconnect();
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
