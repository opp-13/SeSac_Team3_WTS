package com.team.wts.gateway.websocket;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.socket.server.WebSocketService;

/**
 * Spring Cloud Gateway의 WebSocket 핸드셰이크 서비스를 DRAIN을 끼워 넣을 수 있는 것으로 감싼다. (CLAUDE.md §68)
 *
 * <p>Gateway 자동 설정의 {@code webSocketService}는 조건 없이 등록되므로 바꿔치지 않고 감싼다.
 * 감싼 빈을 {@code @Primary}로 두어 Gateway의 WebSocket 라우팅 필터가 이것을 주입받게 한다.
 * 업그레이드 방식(Reactor Netty, Gateway의 websocket 설정)은 Gateway가 만든 원래 서비스가 그대로 맡는다.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayWebSocketConfig {

    @Bean
    @Primary
    public WebSocketService drainingWebSocketService(@Qualifier("webSocketService") WebSocketService gatewayDefault,
            WebSocketDrainRegistry registry) {
        return new DrainingWebSocketService(gatewayDefault, registry);
    }
}
