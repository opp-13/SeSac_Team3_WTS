import type { OrderBookSnapshot, PriceTick } from './types'

/**
 * /ws/market 클라이언트. (CLAUDE.md §25, §68)
 *
 * - 끊기면 지수 백오프(지터 포함)로 재연결하고, 재연결 후에는 기존 구독을 복구한다.
 * - 서버가 곧 내려간다고 알리면(DRAIN) 지금 연결을 쥔 채 새 연결을 맺고, 새 연결이 구독 확인(SUBSCRIBED)을
 *   받은 뒤에 옛 연결을 닫는다(make-before-break). 그래서 계획된 종료(축소 · 배포)에 시세 공백이 없다.
 *   두 연결이 겹치는 동안 같은 시세가 두 번 올 수 있다. 오래된 값은 marketStore가 timestamp로 거른다.
 * - 시세가 흐르지 않는 화면에서도 프록시 · ALB의 유휴 시간 제한에 끊기지 않게 PING을 보낸다.
 */

export type SocketStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

interface Handlers {
  onStatus: (status: SocketStatus) => void
  onPrice: (price: PriceTick) => void
  onOrderBook: (orderBook: OrderBookSnapshot) => void
}

const INITIAL_RETRY_DELAY_MS = 500
const MAX_RETRY_DELAY_MS = 10_000
/** ALB 유휴 시간 제한(기본 60초)보다 넉넉히 짧게. */
const PING_INTERVAL_MS = 25_000

function endpoint(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/market`
}

export class MarketSocket {
  private readonly handlers: Handlers
  /** 지금 시세를 받는 연결. */
  private socket: WebSocket | null = null
  /** DRAIN을 받고 옮겨 가는 중인 새 연결. 구독 확인을 받으면 socket 자리로 올라간다. */
  private standby: WebSocket | null = null
  private readonly symbols = new Set<string>()
  private retryDelayMs = INITIAL_RETRY_DELAY_MS
  private retryTimer: number | null = null
  private pingTimer: number | null = null
  /** 사용자가 의도적으로 끊은 경우에는 재연결하지 않는다. */
  private closedByUs = false

  constructor(handlers: Handlers) {
    this.handlers = handlers
  }

  connect(): void {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) return

    this.closedByUs = false
    this.handlers.onStatus(this.retryDelayMs === INITIAL_RETRY_DELAY_MS ? 'connecting' : 'reconnecting')

    const socket = new WebSocket(endpoint())
    this.socket = socket
    this.startPing()

    socket.onopen = () => {
      this.retryDelayMs = INITIAL_RETRY_DELAY_MS
      this.handlers.onStatus('open')
      // 재연결이라면 끊기기 전 구독을 되살린다.
      this.subscribeAll(socket)
    }

    socket.onmessage = (event) => this.dispatch(event.data, socket)

    socket.onclose = () => this.onPrimaryClosed(socket)

    // onerror 직후 onclose가 이어지므로 재연결은 onclose 한 곳에서만 처리한다.
    socket.onerror = () => socket.close()
  }

  disconnect(): void {
    this.closedByUs = true
    if (this.retryTimer !== null) {
      window.clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    this.stopPing()
    this.discardStandby()
    this.socket?.close()
    this.socket = null
    this.handlers.onStatus('closed')
  }

  subscribe(symbols: string[]): void {
    symbols.forEach((symbol) => this.symbols.add(symbol))
    this.send('SUBSCRIBE', symbols)
  }

  unsubscribe(symbols: string[]): void {
    symbols.forEach((symbol) => this.symbols.delete(symbol))
    this.send('UNSUBSCRIBE', symbols)
  }

  // ── 옮겨 가기 (DRAIN, CLAUDE.md §68) ─────────────────────

  /** 지금 연결은 그대로 두고 새 연결을 맺는다. 서버는 이미 이 인스턴스로 새 연결을 보내지 않는다. */
  private migrate(): void {
    if (this.standby !== null || this.closedByUs) return

    const standby = new WebSocket(endpoint())
    this.standby = standby

    standby.onopen = () => {
      this.subscribeAll(standby)
      // 옛 연결이 먼저 끊겼거나 구독이 없으면 구독 확인을 기다릴 이유가 없다. 바로 올린다.
      if (this.socket === null || this.symbols.size === 0) this.promote(standby)
    }

    // 올라가기 전에도 받은 시세는 바로 반영한다. 겹친 값은 marketStore가 timestamp로 거른다.
    standby.onmessage = (event) => this.dispatch(event.data, standby)

    standby.onclose = () => {
      if (this.standby !== standby) return
      this.standby = null
      // 옛 연결이 아직 살아 있으면 그대로 쓴다. 서버가 정리하면 그때 평소처럼 재연결한다.
      if (this.socket === null && !this.closedByUs) {
        this.handlers.onStatus('reconnecting')
        this.scheduleReconnect()
      }
    }

    standby.onerror = () => standby.close()
  }

  /** 새 연결을 주 연결로 올리고 옛 연결을 닫는다. */
  private promote(standby: WebSocket): void {
    if (this.standby !== standby) return
    const previous = this.socket
    this.standby = null
    this.socket = standby
    standby.onclose = () => this.onPrimaryClosed(standby)
    this.retryDelayMs = INITIAL_RETRY_DELAY_MS
    this.handlers.onStatus('open')

    if (previous !== null) {
      // 옛 연결의 닫힘은 재연결을 일으키지 않는다. 우리가 닫는 것이다.
      previous.onclose = null
      previous.onmessage = null
      previous.close(1000, 'migrated')
    }
  }

  private onPrimaryClosed(socket: WebSocket): void {
    if (this.socket !== socket) return
    this.socket = null
    if (this.closedByUs) {
      this.handlers.onStatus('closed')
      return
    }
    // 옮겨 가는 중이면 새 연결이 이어받는다. 열려 있으면 바로, 아니면 열리는 순간(onopen) 올린다.
    if (this.standby !== null) {
      if (this.standby.readyState === WebSocket.OPEN) this.promote(this.standby)
      return
    }
    this.handlers.onStatus('reconnecting')
    this.scheduleReconnect()
  }

  private discardStandby(): void {
    const standby = this.standby
    this.standby = null
    if (standby !== null) {
      standby.onclose = null
      standby.onmessage = null
      standby.close()
    }
  }

  // ── 송수신 ───────────────────────────────────────────────

  private subscribeAll(socket: WebSocket): void {
    if (this.symbols.size > 0) this.sendTo(socket, { type: 'SUBSCRIBE', symbols: [...this.symbols] })
  }

  /** 열려 있는 연결 모두에 보낸다. 옮겨 가는 중이면 새 연결도 같은 구독을 갖게 된다. */
  private send(type: 'SUBSCRIBE' | 'UNSUBSCRIBE', symbols: string[]): void {
    if (symbols.length === 0) return
    // 아직 연결 전이면 보내지 않는다. onopen에서 전체 구독을 다시 보낸다.
    for (const socket of [this.socket, this.standby]) {
      if (socket !== null) this.sendTo(socket, { type, symbols })
    }
  }

  private sendTo(socket: WebSocket, message: object): void {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message))
  }

  private dispatch(raw: unknown, from: WebSocket): void {
    if (typeof raw !== 'string') return
    let message: { type?: string }
    try {
      message = JSON.parse(raw)
    } catch {
      return
    }

    switch (message.type) {
      case 'PRICE':
        this.handlers.onPrice(message as unknown as PriceTick)
        break
      case 'ORDERBOOK':
        this.handlers.onOrderBook(message as unknown as OrderBookSnapshot)
        break
      case 'DRAIN':
        // market-service와 gateway가 각각 보낼 수 있다. 주 연결에서 온 첫 번째만 따른다.
        if (from === this.socket) this.migrate()
        break
      case 'SUBSCRIBED':
        // 새 연결의 구독이 서버에 등록됐다. 이제 옛 연결을 닫아도 시세가 끊기지 않는다.
        if (from === this.standby) this.promote(from)
        break
      default:
        // PONG / ERROR 는 상태 표시에 쓰지 않는다. 필요해지면 여기서 처리한다.
        break
    }
  }

  // ── 유휴 연결 유지 ───────────────────────────────────────

  private startPing(): void {
    if (this.pingTimer !== null) return
    this.pingTimer = window.setInterval(() => {
      if (this.socket !== null) this.sendTo(this.socket, { type: 'PING' })
    }, PING_INTERVAL_MS)
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      window.clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private scheduleReconnect(): void {
    if (this.retryTimer !== null) return
    // 지터: 서버 인스턴스가 내려가면(HPA 축소 · 배포) 그 인스턴스의 클라이언트가 한꺼번에 끊긴다.
    // 모두 같은 간격으로 다시 붙으면 남은 인스턴스에 몰리므로 0.5~1.5배로 흩뜨린다 (CLAUDE.md §66.6).
    const delay = Math.round(this.retryDelayMs * (0.5 + Math.random()))
    this.retryDelayMs = Math.min(this.retryDelayMs * 2, MAX_RETRY_DELAY_MS)
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, delay)
  }
}
