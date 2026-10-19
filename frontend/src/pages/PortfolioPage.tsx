import { useId, useState } from 'react'
import { Link } from 'react-router'

import { Pair } from '../components/Pair'
import { Panel } from '../components/Panel'
import { PanelState } from '../components/PanelState'
import {
  directionOf,
  formatChange,
  formatPrice,
  formatQuantity,
  formatRate,
  formatSeoulTime,
  formatWon,
} from '../lib/format'
import { usePortfolio } from '../features/portfolio/usePortfolio'
import { useStockNames } from '../features/market/useStockNames'

/**
 * 용어 도움말. 처음 보는 사람이 막히는 단어에만 붙인다.
 *
 * <p>브라우저 기본 툴팁(title)은 1초 가까이 기다려야 뜨고, 터치 · 키보드로는 아예 뜨지 않는다.
 * 그래서 말풍선을 직접 띄운다. 마우스를 올리면, 키보드로 초점을 옮기면, 누르면 보인다.
 * 다른 곳을 누르거나(초점이 빠지면) Esc를 누르면 닫힌다.
 */
function Hint({ text }: { text: string }) {
  const id = useId()
  const [open, setOpen] = useState(false)

  return (
    <span className={`hint${open ? ' hint--open' : ''}`}>
      <button
        type="button"
        className="hint-mark"
        aria-label="용어 설명"
        aria-describedby={id}
        onClick={() => setOpen((value) => !value)}
        onBlur={() => setOpen(false)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') setOpen(false)
        }}
      >
        ?
      </button>
      <span role="tooltip" id={id} className="hint__bubble">
        {text}
      </span>
    </span>
  )
}

/**
 * `/portfolio` — 내 자산 (CLAUDE.md §26, §28, ui-requirements §7)
 *
 * <p>모든 금액은 서버가 한 시점의 시세로 계산한 값이다. 화면에서 다시 더하거나 빼지 않는다.
 * 예약금은 예수금에 포함되어 있고, 매도 예약수량도 보유수량에 포함되어 있다.
 * 총 자산에서 예약금을 한 번 더 빼지 않는 이유다.
 *
 * <p>평가손익과 실현손익은 다른 값이다. 하나로 합쳐 보여주지 않는다.
 */
export function PortfolioPage() {
  const { data: portfolio, isPending, isError } = usePortfolio()
  const nameOf = useStockNames((portfolio?.positions ?? []).map((item) => item.symbol))

  if (isPending) {
    return (
      <div className="page">
        <Panel>
          <PanelState tone="loading">자산을 불러오는 중…</PanelState>
        </Panel>
      </div>
    )
  }

  if (isError || !portfolio) {
    return (
      <div className="page">
        <Panel>
          <PanelState tone="error" hint="잠시 후 다시 확인해 주세요.">
            자산을 불러오지 못했습니다.
          </PanelState>
        </Panel>
      </div>
    )
  }

  const valuation = directionOf(portfolio.valuationProfit)
  const realized = directionOf(portfolio.realizedProfit)

  return (
    <div className="page">
      <div className="asset-cards">
        <article className="panel asset-card asset-card--primary">
          <span className="asset-card__label">나의 총 자산</span>
          <strong className="asset-card__value">{formatWon(portfolio.totalAssets)}</strong>
          <p className="asset-card__note">예수금 + 보유종목 평가금액</p>
        </article>

        <article className="panel asset-card">
          <span className="asset-card__label">예수금</span>
          <strong className="asset-card__value">{formatWon(portfolio.cashBalance)}</strong>
          <p className="asset-card__note">주문 예약금 포함</p>
        </article>

        <article className="panel asset-card">
          <span className="asset-card__label">보유종목 평가금액</span>
          <strong className="asset-card__value">
            {formatWon(portfolio.totalEvaluationAmount)}
          </strong>
          <p className="asset-card__note">매입금액 {formatWon(portfolio.totalPurchaseAmount)}</p>
        </article>

        <article className="panel asset-card">
          <span className="asset-card__label">
            평가손익
            <Hint text="아직 팔지 않은 보유종목의 손익입니다. 현재가와 평균매입단가의 차이로 계산합니다." />
          </span>
          <strong className={`asset-card__value price--${valuation}`}>
            {formatChange(portfolio.valuationProfit)}원
          </strong>
          <p className="asset-card__note">수익률 {formatRate(portfolio.valuationProfitRate)}</p>
        </article>
      </div>

      <section className="panel asset-breakdown">
        <Pair label="전체 예수금" value={formatWon(portfolio.cashBalance)} />
        <Pair label="주문 예약금" value={formatWon(portfolio.reservedCash)} />
        <Pair
          label={
            <>
              주문가능금액
              <Hint text="예수금에서 미체결 매수 주문이 잡아 둔 예약금을 뺀 금액입니다." />
            </>
          }
          value={formatWon(portfolio.availableCash)}
          emphasis
        />
        <Pair
          label={
            <>
              실현손익
              <Hint text="이미 매도해서 확정된 손익입니다. 평가손익과는 별개입니다." />
            </>
          }
          value={`${formatChange(portfolio.realizedProfit)}원`}
          valueClassName={`price--${realized}`}
        />
      </section>

      <Panel
        title="보유종목"
        meta={
          <span className="panel__meta">
            {formatSeoulTime(portfolio.evaluatedAt)} 기준 · 종목을 누르면 거래 화면으로 갑니다
          </span>
        }
      >
        {portfolio.positions.length === 0 ? (
          <PanelState
            hint="종목을 골라 가상 자금으로 첫 주문을 내보세요."
            action={
              <Link className="btn btn--primary" to="/wts">
                첫 모의투자 시작하기
              </Link>
            }
          >
            아직 보유한 종목이 없어요.
          </PanelState>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">종목</th>
                  <th scope="col">보유수량</th>
                  <th scope="col">매도 예약수량</th>
                  <th scope="col">매도가능수량</th>
                  <th scope="col">
                    평균매입단가
                  </th>
                  <th scope="col">현재가</th>
                  <th scope="col">평가금액</th>
                  <th scope="col">평가손익</th>
                </tr>
              </thead>
              <tbody>
                {portfolio.positions.map((item) => (
                  <tr key={item.symbol}>
                    <th scope="row">
                      <Link className="table__link" to={`/wts/${item.symbol}`}>
                        {nameOf(item.symbol)}
                      </Link>
                      <span className="table__sub numeric">{item.symbol}</span>
                    </th>
                    <td className="numeric">{formatQuantity(item.quantity)}</td>
                    <td className="numeric">{formatQuantity(item.reservedQuantity)}</td>
                    <td className="numeric">{formatQuantity(item.availableQuantity)}</td>
                    <td className="numeric">{formatPrice(item.averagePrice)}원</td>
                    <td className="numeric">
                      {/* 시세를 못 읽으면 매입가로 평가된다. 현재가는 비워 둔다. */}
                      {item.currentPrice === null ? '—' : `${formatPrice(item.currentPrice)}원`}
                    </td>
                    <td className="numeric">{formatPrice(item.evaluationAmount)}원</td>
                    <td className={`numeric price--${directionOf(item.valuationProfit)}`}>
                      {formatChange(item.valuationProfit)}원
                      <span className="table__sub">{formatRate(item.valuationProfitRate)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </div>
  )
}
