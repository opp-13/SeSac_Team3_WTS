import { Link, NavLink, Outlet, useLocation } from 'react-router'

import { LearnstockLogo } from '../components/LearnstockLogo'
import { Toasts } from '../components/Toasts'
import { AccountSummary } from '../features/trading/components/AccountSummary'
import { ConnectionStatus } from '../features/market/components/ConnectionStatus'
import { useMarketConnection } from '../features/market/useMarketConnection'
import { useUserStore } from '../stores/userStore'

/** 주 내비게이션은 세 가지로 단순화한다 (ui-requirements §5.1). */
const NAV = [
  { to: '/wts', label: '트레이딩' },
  { to: '/portfolio', label: '내 자산' },
  { to: '/orders', label: '거래내역' },
]

/** 경로별 페이지 머리말. */
const HEADING: Record<string, { title: string; subtitle: string }> = {
  '/wts': {
    title: '오늘도, 한 걸음 더.',
    subtitle: '시장을 살펴보고 나만의 투자를 연습해 보세요.',
  },
  '/portfolio': {
    title: '나의 투자를 한눈에.',
    subtitle: '가상 자산과 보유종목의 변화를 확인하세요.',
  },
  '/orders': {
    title: '기록으로 배우는 투자.',
    subtitle: '주문부터 체결까지, 나의 투자 과정을 살펴보세요.',
  },
}

/** 모든 화면이 공유하는 머리말과 껍데기. (CLAUDE.md §27, ui-requirements §5.1) */
export function AppLayout() {
  // 시세 연결은 화면과 상관없이 하나다. 머리말의 연결 상태가 어느 화면에서든 실제 연결을 보여 준다.
  useMarketConnection()
  const session = useUserStore((state) => state.session)
  const logout = useUserStore((state) => state.logout)
  const { pathname } = useLocation()

  // /wts/005930 처럼 하위 경로도 같은 머리말을 쓴다.
  const headingKey = Object.keys(HEADING).find(
    (key) => pathname === key || pathname.startsWith(`${key}/`),
  )
  const heading = headingKey ? HEADING[headingKey] : null

  return (
    <>
      <header className="header">
        <LearnstockLogo />

        <nav className="header__nav" aria-label="주 메뉴">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `header__link${isActive ? ' header__link--active' : ''}`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="header__right">
          <ConnectionStatus />
          {session ? (
            <div className="header__user">
              <span className="header__avatar" aria-hidden="true">
                {session.nickname.slice(0, 1)}
              </span>
              <span className="header__nickname">{session.nickname}</span>
              <button type="button" className="btn btn--sm" onClick={logout}>
                로그아웃
              </button>
            </div>
          ) : (
            <Link className="btn btn--sm btn--primary" to="/login">
              모의투자 시작하기
            </Link>
          )}
        </div>
      </header>

      <div className="shell">
        {heading && (
          <section className="page-heading">
            <div>
              <h1 className="page-heading__title">{heading.title}</h1>
              <p className="page-heading__subtitle">{heading.subtitle}</p>
            </div>
            <AccountSummary />
          </section>
        )}

        <Outlet />

        <footer className="footer">
          <span>가상 자금으로 진행되는 모의투자입니다. 실제 주문·결제는 발생하지 않습니다.</span>
          <span>주문금액은 주문가격 × 수량으로 계산합니다.</span>
        </footer>
      </div>

      <Toasts />
    </>
  )
}
