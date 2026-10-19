import {
  directionOf,
  formatPrice,
  formatPriceChange,
  formatSeoulTime,
  formatVolume,
} from '../../../lib/format'
import type { Stock } from '../../../lib/types'
import { useMarketStore } from '../../../stores/marketStore'
import { useUserStore } from '../../../stores/userStore'
import { WatchStar } from '../../watchlist/components/WatchStar'

interface Props {
  stock: Stock
}

/**
 * 선택 종목의 헤더와 현재가. (CLAUDE.md §28, ui-requirements §5.3)
 *
 * <p>시가·고가·저가는 표시하지 않는다. 서버가 주는 시세에 없는 값이라 화면에서 만들어낼 수 없다.
 * 대신 전일 종가·누적 거래량·시세 기준 시각을 보여준다.
 * 기준 시각은 연결이 끊겼을 때 이 값이 언제 것인지 알려주는 유일한 단서다.
 */
export function RealtimePrice({ stock }: Props) {
  const tick = useMarketStore((state) => state.prices[stock.symbol])
  const session = useUserStore((state) => state.session)
  const direction = tick ? directionOf(tick.change) : 'flat'

  return (
    <>
      <div className="quote__head">
        <div className="quote__identity">
          <span className="quote__logo" aria-hidden="true">
            {stock.name.slice(0, 1)}
          </span>
          <div>
            <h2 className="quote__name">
              {stock.name}
              {session && <WatchStar symbol={stock.symbol} name={stock.name} />}
            </h2>
            <span className="quote__symbol">
              {stock.symbol} · {stock.market}
            </span>
          </div>
        </div>
        <span className="badge">국내주식</span>
      </div>

      {tick ? (
        <>
          <div className="quote__price-line">
            <strong className={`quote__price price--${direction}`}>
              {formatPrice(tick.price)}원
            </strong>
            <span className={`quote__change price--${direction}`}>
              {formatPriceChange(tick.change, tick.changeRate)}
            </span>
            {/* 실제 시세를 받지 않는 종목은 모의 시세다. 숨기지 않는다 (CLAUDE.md §62.2, §67). 표시 방식은 Figma를 따른다 */}
            {tick.source === 'SIMULATED' && (
              <span className="badge" title="실제 시세를 받지 않는 종목이라 서비스가 만든 모의 시세입니다">
                모의 시세
              </span>
            )}
          </div>

          <dl className="quote__stats">
            <div>
              <dt>전일 종가</dt>
              {/* 전일 종가 = 현재가 − 전일 대비. 서버가 따로 내려주지 않는다 (§25) */}
              <dd className="numeric">{formatPrice(tick.price - tick.change)}원</dd>
            </div>
            <div>
              <dt>누적 거래량</dt>
              <dd className="numeric">{formatVolume(tick.volume)}주</dd>
            </div>
            <div>
              <dt>시세 기준</dt>
              <dd className="numeric">{formatSeoulTime(tick.timestamp)}</dd>
            </div>
          </dl>
        </>
      ) : (
        <div className="quote__price-line">
          <strong className="quote__price price--flat">—</strong>
          <span className="quote__change">시세를 기다리는 중…</span>
        </div>
      )}
    </>
  )
}
