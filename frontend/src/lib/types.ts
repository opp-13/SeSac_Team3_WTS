/** 백엔드 계약 (CLAUDE.md §24, §25). 금액은 숫자로 내려온다. */

/** 종목 마스터 (CLAUDE.md §57.1). 종목코드에는 영문 대문자가 섞일 수 있다 (예: 0001A0). */
export interface Stock {
  symbol: string
  standardCode: string
  name: string
  market: 'KOSPI' | 'KOSDAQ'
  /** 기준가. 신규 상장 직후처럼 모르면 null */
  basePrice: number | null
  /** 거래정지 · 상장폐지면 false */
  tradable: boolean
}

/** 종목 목록 한 페이지. 전체를 한 번에 받지 않는다 (§57.3). */
export interface StockPage {
  items: Stock[]
  page: number
  size: number
  totalElements: number
}

/**
 * WebSocket `PRICE` 메시지 (CLAUDE.md §25).
 *
 * <p>전일 종가는 여기 없다. 서버가 보내는 것은 전일 대비 변화량(change)까지다.
 * 전일 종가가 필요하면 `price - change`로 구한다. 정의상 정확히 같은 값이다.
 */
export interface PriceTick {
  symbol: string
  price: number
  change: number
  changeRate: number
  volume: number
  /** UTC ISO-8601. 표시할 때 Asia/Seoul로 변환한다 (CLAUDE.md §43). */
  timestamp: string
  /**
   * 시세 출처. SIMULATED는 서비스가 만든 결정형 모의 시세다 (CLAUDE.md §67).
   * 실제 시세처럼 보이게 하지 않는다 (§62.2). 이 필드가 없던 서버는 실제 시세만 보냈다.
   */
  source?: QuoteSource
}

export type QuoteSource = 'REALTIME' | 'SIMULATED'

export interface OrderBookLevel {
  price: number
  quantity: number
}

export interface OrderBookSnapshot {
  symbol: string
  /** 매도호가. 최우선(가장 낮은 가격)부터. */
  asks: OrderBookLevel[]
  /** 매수호가. 최우선(가장 높은 가격)부터. */
  bids: OrderBookLevel[]
  timestamp: string
}

/** 공통 에러 응답 (CLAUDE.md §39). */
export interface ApiError {
  code: string
  message: string
  traceId: string | null
  timestamp: string
}

// ── 거래 (CLAUDE.md §24, Phase 3) ──────────────────────────────

export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT'
export type OrderStatus =
  | 'RECEIVED'
  | 'VALIDATED'
  | 'ACCEPTED'
  | 'FILLED'
  | 'CANCELLED'
  | 'REJECTED'

/** POST /api/users/mock-login 응답. */
export interface MockLoginResult {
  userId: string
  email: string
  nickname: string
  accessToken: string
  expiresInSeconds: number
}

export interface Account {
  accountId: number
  userId: string
  /** 예수금. 예약은 여기서 빠지지 않는다. */
  cashBalance: number
  /** 미체결 매수 주문이 묶어둔 금액. */
  reservedCash: number
  /** 주문 가능 금액 = cashBalance - reservedCash (CLAUDE.md §9.2) */
  availableCash: number
  updatedAt: string
}

export interface Position {
  symbol: string
  quantity: number
  /** 미체결 매도 주문이 묶어둔 수량. */
  reservedQuantity: number
  /** 매도 가능 수량 = quantity - reservedQuantity (CLAUDE.md §9.3) */
  availableQuantity: number
  averagePrice: number
  updatedAt: string
}

export interface Order {
  orderId: number
  symbol: string
  side: OrderSide
  orderType: OrderType
  quantity: number
  /** 지정가 주문에만 있다. */
  limitPrice?: number
  filledQuantity: number
  status: OrderStatus
  /** REJECTED 주문에만 있다. ErrorCode 이름. */
  rejectReason?: string
  createdAt: string
  updatedAt: string
}

export interface PlaceOrderRequest {
  symbol: string
  side: OrderSide
  orderType: OrderType
  quantity: number
  limitPrice?: number
}

/** 체결 기록 (CLAUDE.md §23, §24). 체결가는 주문이 아니라 여기에만 있다. */
export interface Execution {
  executionId: number
  orderId: number
  symbol: string
  side: OrderSide
  price: number
  quantity: number
  /** 체결 대금 = price × quantity. 수수료는 포함하지 않는다. */
  amount: number
  fee: number
  /** 매도 체결에만 있다. (체결가 - 체결 시점 평균단가) × 수량 */
  realizedProfit?: number
  executedAt: string
}

/** 관심종목 (CLAUDE.md §24). 종목 마스터는 market-service가 갖는다. */
export interface WatchlistItem {
  symbol: string
  createdAt: string
}

/** 1분봉 (CLAUDE.md §22, §24). 마지막 원소는 아직 확정되지 않은 현재 봉일 수 있다. */
export interface Candle {
  /** UTC ISO-8601 */
  openTime: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface PortfolioItem {
  symbol: string
  quantity: number
  reservedQuantity: number
  availableQuantity: number
  averagePrice: number
  /** 시세를 읽지 못하면 null. 이때 평가금액은 매입금액과 같다. */
  currentPrice: number | null
  purchaseAmount: number
  evaluationAmount: number
  valuationProfit: number
  valuationProfitRate: number
}

export interface Portfolio {
  accountId: number
  cashBalance: number
  reservedCash: number
  availableCash: number
  totalPurchaseAmount: number
  totalEvaluationAmount: number
  /** 아직 팔지 않은 이익. */
  valuationProfit: number
  valuationProfitRate: number
  /** 이미 팔아서 확정된 이익. */
  realizedProfit: number
  /** 예수금 + 평가금액 */
  totalAssets: number
  positions: PortfolioItem[]
  evaluatedAt: string
}
