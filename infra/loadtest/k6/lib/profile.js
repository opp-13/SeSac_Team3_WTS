// 시간에 따른 부하 모양(계획서 §7 회차). [기간, 배수] 목록을 돌려준다. 배수 1 = 평균 부하(N).
// ramping-vus의 stage는 "그 기간 동안 목표까지 직선으로 간다"이므로, 유지 구간은 같은 배수를 한 번 더 쓴다.
import { num, str } from './config.js';

export function profileStages(profile) {
  const peak = num('PEAK', 3);
  switch (profile) {
    case 'steady': // T3 평균 부하, T9 회귀
      return [['2m', 1], [str('DURATION', '30m'), 1], ['1m', 0]];
    case 'stress': // T4: 1 → 1.5 → 2 → 3 → 4배, 계단마다 10분
      return [
        ['2m', 1], ['5m', 1],
        ['1m', 1.5], ['9m', 1.5],
        ['1m', 2], ['9m', 2],
        ['1m', 3], ['9m', 3],
        ['1m', 4], ['9m', 4],
        ['1m', 0],
      ];
    case 'breakpoint': { // T5: 2분마다 +10%, 중단 조건(abortOnFail)에 걸릴 때까지
      const out = [['2m', 1]];
      for (let m = 1.1; m <= num('MAX_MULT', 6); m *= 1.1) out.push(['30s', m], ['90s', m]);
      return out;
    }
    case 'autoscale': // T6: 평균 → 30초 만에 3배 → 15분 유지 → 평균 → 20분 관찰
      return [['2m', 1], ['5m', 1], ['30s', peak], ['15m', peak], ['30s', 1], ['20m', 1], ['30s', 0]];
    case 'spike': // T7: 유휴 → 60초 만에 피크 → 30분 유지
      return [['1m', peak], [str('DURATION', '30m'), peak], ['1m', 0]];
    case 'soak': // T8: 평균 2시간 30분
      return [['2m', 1], [str('DURATION', '2h30m'), 1], ['1m', 0]];
    default:
      throw new Error(`알 수 없는 PROFILE=${profile} (steady|stress|breakpoint|autoscale|spike|soak)`);
  }
}

export function durationMs(d) {
  const re = /(\d+(?:\.\d+)?)(h|m|s)/g;
  let ms = 0;
  let m;
  while ((m = re.exec(d)) !== null) ms += Number(m[1]) * { h: 3600000, m: 60000, s: 1000 }[m[2]];
  return ms;
}

export function totalMs(stages) {
  return stages.reduce((sum, [d]) => sum + durationMs(d), 0);
}
