import { useEffect, useRef } from 'react'

import { useWebSocketStore } from '../../stores/websocketStore'

/**
 * 주어진 종목만 시세를 구독한다. (CLAUDE.md §25, §29, §57.3)
 *
 * <p>연결 자체는 앱 껍데기가 맡는다 (`useMarketConnection`). 여기서 맺고 끊으면 이 화면을 떠난 뒤
 * 다른 화면의 연결 상태가 "끊김"으로 보인다.
 *
 * <p>목록이 바뀌면 새 종목만 구독하고 <b>빠진 종목은 구독을 푼다</b>. 검색어를 바꿀 때마다
 * 구독이 쌓이면 서버가 화면에 없는 종목까지 계속 보낸다.
 *
 * 연결 전에 구독을 요청해도 괜찮다. 소켓이 종목을 기억했다가 연결되는 순간 한꺼번에 보낸다
 * (websocketStore가 소켓을 먼저 만들어 둔다 - 첫 로드에는 이 effect가 앱 껍데기의 connect보다 먼저 돈다).
 */
export function useMarketStream(symbols: readonly string[]): void {
  const subscribe = useWebSocketStore((state) => state.subscribe)
  const unsubscribe = useWebSocketStore((state) => state.unsubscribe)
  const subscribed = useRef<Set<string>>(new Set())

  // 배열 참조가 아니라 내용이 바뀔 때만 다시 계산한다.
  const key = [...new Set(symbols)].sort().join(',')
  useEffect(() => {
    const next = new Set(key.length > 0 ? key.split(',') : [])
    const previous = subscribed.current
    const removed = [...previous].filter((symbol) => !next.has(symbol))
    const added = [...next].filter((symbol) => !previous.has(symbol))
    if (removed.length > 0) unsubscribe(removed)
    if (added.length > 0) subscribe(added)
    subscribed.current = next
  }, [key, subscribe, unsubscribe])

  // 화면을 떠나면 다음 연결 때 되살아나지 않게 소켓에 남은 구독도 지운다.
  useEffect(
    () => () => {
      unsubscribe([...subscribed.current])
      subscribed.current = new Set()
    },
    [unsubscribe],
  )
}
