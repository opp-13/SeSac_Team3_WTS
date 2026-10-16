package com.team.wts.market.quote.adapter.in.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.wts.market.quote.adapter.ws.MarketSessionRegistry;
import com.team.wts.market.quote.domain.MarketPrice;
import com.team.wts.market.quote.domain.OrderBook;
import com.team.wts.market.quote.fanout.QuoteFanoutChannels;

/**
 * market-ingest가 Valkey pub/sub로 내보낸 시세 · 호가를 받아 이 인스턴스의 WebSocket 세션에 보낸다.
 * (CLAUDE.md §66.3)
 *
 * <p>market-service 인스턴스마다 하나씩 구독한다. pub/sub은 구독자 모두에게 가므로 인스턴스가 늘고 줄어도
 * 따로 맞출 것이 없다.
 *
 * <p>메시지는 구독 스레드에서 순서대로 처리한다({@link SyncTaskExecutor}). 기본 실행기는 메시지마다
 * 스레드를 만들어 같은 종목의 시세가 뒤바뀌어 나갈 수 있다. 세션 전송은 버퍼에 쌓고 바로 돌아오므로
 * 순서대로 처리해도 밀리지 않는다 ({@link MarketSessionRegistry}).
 */
@Configuration(proxyBeanMethods = false)
public class QuoteFanoutSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteFanoutSubscriber.class);

    private final MarketSessionRegistry sessions;
    private final ObjectMapper objectMapper;

    public QuoteFanoutSubscriber(MarketSessionRegistry sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Bean
    RedisMessageListenerContainer quoteFanoutListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.addMessageListener(this, List.of(
                new ChannelTopic(QuoteFanoutChannels.PRICE),
                new ChannelTopic(QuoteFanoutChannels.ORDER_BOOK)));
        return container;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        try {
            if (QuoteFanoutChannels.PRICE.equals(channel)) {
                sessions.broadcastPrice(objectMapper.readValue(message.getBody(), MarketPrice.class));
            } else if (QuoteFanoutChannels.ORDER_BOOK.equals(channel)) {
                sessions.broadcastOrderBook(objectMapper.readValue(message.getBody(), OrderBook.class));
            }
        } catch (IOException e) {
            // 두 앱이 market-core의 같은 클래스로 읽고 쓴다. 어긋나는 것은 배포 중 버전이 섞였을 때 정도다.
            log.warn("fan-out 메시지 형식 오류, 건너뜀: channel={} ({})", channel, e.getMessage());
        }
    }
}
