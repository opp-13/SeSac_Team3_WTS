import { create } from 'zustand'

import type { OrderBookSnapshot, PriceTick } from '../lib/types'

/** 실시간 시세 상태. (CLAUDE.md §29 – marketStore) */
interface MarketState {
  prices: Record<string, PriceTick>
  orderBooks: Record<string, OrderBookSnapshot>
  /** 사용자가 보고 있는 종목. */
  selectedSymbol: string | null
  applyPrice: (price: PriceTick) => void
  applyOrderBook: (orderBook: OrderBookSnapshot) => void
  selectSymbol: (symbol: string) => void
}

/**
 * 가진 값보다 오래된 시세는 버린다. 서버를 옮겨 가는 동안(DRAIN, CLAUDE.md §68) 두 연결이 겹쳐
 * 같은 종목의 시세가 순서 없이 두 번 올 수 있기 때문이다. timestamp가 같으면 같은 값이다.
 */
function isOlder(incoming: { timestamp: string }, current: { timestamp: string } | undefined): boolean {
  return current !== undefined && Date.parse(incoming.timestamp) < Date.parse(current.timestamp)
}

export const useMarketStore = create<MarketState>((set) => ({
  prices: {},
  orderBooks: {},
  selectedSymbol: null,

  applyPrice: (price) =>
    set((state) =>
      isOlder(price, state.prices[price.symbol])
        ? state
        : { prices: { ...state.prices, [price.symbol]: price } },
    ),

  applyOrderBook: (orderBook) =>
    set((state) =>
      isOlder(orderBook, state.orderBooks[orderBook.symbol])
        ? state
        : { orderBooks: { ...state.orderBooks, [orderBook.symbol]: orderBook } },
    ),

  selectSymbol: (symbol) => set({ selectedSymbol: symbol }),
}))
