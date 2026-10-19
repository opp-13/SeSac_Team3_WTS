import { useEffect } from 'react'

import { useWebSocketStore } from '../../stores/websocketStore'

/**
 * 시세 WebSocket 연결을 앱 전체에서 하나로 유지한다. (CLAUDE.md §25, §29)
 *
 * <p>연결 상태 표시(ConnectionStatus)는 모든 화면의 머리말에 있다. 연결을 시세를 쓰는 화면(/wts)이
 * 맺고 끊으면, 그 화면을 떠나는 순간 다른 화면에서는 늘 "연결 끊김"으로 보인다. 그래서 연결은 앱 껍데기가
 * 맡고, 화면은 무엇을 구독할지만 정한다 (`useMarketStream`). 구독한 종목이 없으면 서버도 보내지 않는다.
 */
export function useMarketConnection(): void {
  const connect = useWebSocketStore((state) => state.connect)
  const disconnect = useWebSocketStore((state) => state.disconnect)

  useEffect(() => {
    connect()
    return () => disconnect()
  }, [connect, disconnect])
}
