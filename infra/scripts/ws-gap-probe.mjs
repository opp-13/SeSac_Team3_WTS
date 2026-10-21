#!/usr/bin/env node
// 배포된 사이트에 실제 프론트 코드(frontend/src/lib/marketSocket.ts)로 붙어, 시세가 끊기는지 잰다. (CLAUDE.md §68)
//
//   node infra/scripts/ws-gap-probe.mjs [host] [symbol]
//   node infra/scripts/ws-gap-probe.mjs d3871z9wh0c98z.cloudfront.net 105560
//
// 켜 둔 채 다른 터미널에서 rollout restart 를 한다. 연결 교체(DRAIN → 새 연결 → 옛 연결 1000)와
// 시세 사이 최대 간격을 보여 준다. 모의 시세 종목(고정 5종목 밖, 예: 105560)은 1초마다 오므로
// 간격이 1초 근처로 유지되면 끊김이 없는 것이다. Ctrl+C 로 끝내면 요약을 출력한다.
// Node 22.6+ (타입 스트리핑, 내장 WebSocket)가 필요하다.
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve } from 'node:path'

const host = process.argv[2] ?? 'd3871z9wh0c98z.cloudfront.net'
const symbol = process.argv[3] ?? '105560'
const GAP_WARN_MS = 1_500

const started = Date.now()
const t = () => new Date().toISOString().slice(11, 23)
const log = (message) => console.log(`${t()}  ${message}`)

// marketSocket.ts 가 쓰는 브라우저 전역
globalThis.window = {
  location: { protocol: host.startsWith('localhost') ? 'http:' : 'https:', host },
  setTimeout, clearTimeout, setInterval, clearInterval,
}

// 연결마다 번호를 붙여 열림 · 닫힘 · DRAIN · SUBSCRIBED 를 기록한다. 동작은 바꾸지 않는다.
let nextId = 1
const NativeWebSocket = globalThis.WebSocket
globalThis.WebSocket = class extends NativeWebSocket {
  constructor(url) {
    super(url)
    const id = nextId++
    log(`ws#${id} 연결 시도`)
    this.addEventListener('open', () => log(`ws#${id} 열림`))
    this.addEventListener('close', (e) => log(`ws#${id} 닫힘 code=${e.code}${e.reason ? ` (${e.reason})` : ''}`))
    this.addEventListener('message', (e) => {
      const type = JSON.parse(e.data).type
      if (type === 'DRAIN' || type === 'SUBSCRIBED') log(`ws#${id} ← ${type}`)
    })
  }
}

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const { MarketSocket } = await import(pathToFileURL(`${root}/frontend/src/lib/marketSocket.ts`).href)

let prices = 0
let lastAt = null
let maxGap = 0
let reconnects = 0
const socket = new MarketSocket({
  onStatus: (status) => {
    if (status === 'reconnecting') reconnects++
    log(`상태: ${status}`)
  },
  onPrice: (price) => {
    if (price.symbol !== symbol) return
    const now = Date.now()
    if (lastAt !== null) {
      const gap = now - lastAt
      maxGap = Math.max(maxGap, gap)
      if (gap > GAP_WARN_MS) log(`⚠ 시세 공백 ${(gap / 1000).toFixed(2)}s`)
    }
    lastAt = now
    prices++
  },
  onOrderBook: () => {},
})

log(`접속: wss://${host}/ws/market, 종목 ${symbol}`)
socket.subscribe([symbol])
socket.connect()

const summary = setInterval(() => {
  log(`시세 ${prices}건, 최대 간격 ${(maxGap / 1000).toFixed(2)}s, 재연결 ${reconnects}회`)
}, 10_000)

process.on('SIGINT', () => {
  clearInterval(summary)
  socket.disconnect()
  const verdict = reconnects === 0 && maxGap <= GAP_WARN_MS ? '끊김 없음' : '끊김 있음'
  console.log(`\n${Math.round((Date.now() - started) / 1000)}초 동안 시세 ${prices}건, 최대 간격 ${(maxGap / 1000).toFixed(2)}s, 재연결 ${reconnects}회 → ${verdict}`)
  process.exit(0)
})
