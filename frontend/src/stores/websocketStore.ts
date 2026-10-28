import { create } from 'zustand'

import { MarketSocket, type SocketStatus } from '../lib/marketSocket'
import { useMarketStore } from './marketStore'

/** WebSocket 연결 상태. (CLAUDE.md §29 – websocketStore) */
interface WebSocketState {
  status: SocketStatus
  connect: () => void
  disconnect: () => void
  subscribe: (symbols: string[]) => void
  unsubscribe: (symbols: string[]) => void
}

/**
 * 소켓 인스턴스는 스토어 상태가 아니라 모듈 스코프에 둔다.
 * 렌더링에 쓰이지 않는 값이라 상태에 넣으면 불필요한 리렌더만 유발한다.
 */
let socket: MarketSocket | null = null

/**
 * 소켓은 처음 필요할 때 만든다. 만들기만 하고 연결은 열지 않는다 - 연결은 앱 껍데기의 connect만 연다.
 *
 * <p>구독은 화면(자식)의 effect가, 연결은 앱 껍데기(부모)의 effect가 맡는데 React는 자식 effect를 먼저
 * 돌린다. 그래서 첫 로드에서는 subscribe가 connect보다 먼저 온다. 그때 소켓이 없다고 구독을 버리면 처음
 * 보이는 종목은 끝까지 시세가 오지 않는다. 소켓이 종목을 기억했다가 연결되는 순간 보낸다.
 */
function getSocket(set: (state: Partial<WebSocketState>) => void): MarketSocket {
  socket ??= new MarketSocket({
    onStatus: (status) => set({ status }),
    onPrice: (price) => useMarketStore.getState().applyPrice(price),
    onOrderBook: (orderBook) => useMarketStore.getState().applyOrderBook(orderBook),
  })
  return socket
}

export const useWebSocketStore = create<WebSocketState>((set) => ({
  status: 'idle',

  connect: () => getSocket(set).connect(),

  disconnect: () => socket?.disconnect(),

  subscribe: (symbols) => getSocket(set).subscribe(symbols),

  unsubscribe: (symbols) => socket?.unsubscribe(symbols),
}))
