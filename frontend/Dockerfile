# React WTS 정적 배포 이미지. (CLAUDE.md §47 Phase 7)
#
#   docker build -t wts/frontend frontend
#
# 프론트엔드는 빌드 결과가 정적 파일이다. nginx가 서빙하면서 /api 와 /ws 를 Gateway로 넘긴다.
# 개발 중 Vite 개발 서버가 하던 역할과 같다.

# 정적 파일은 아키텍처와 무관하다. 멀티 아키텍처 빌드에서도 이 단계는 네이티브로 한 번만 돈다.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app

# 의존성 설치를 소스 변경과 분리해 레이어 캐시를 살린다.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# CI 와 같은 명령을 쓴다 (tsc -b && vite build). 이미지에서만 타입 검사를 건너뛰면
# "CI 는 통과했는데 이미지는 다른 결과" 가 생긴다.
#
# vite.config.ts 의 envDir 은 저장소 루트를 가리키는데 빌드 컨텍스트 밖이다.
# 번들에 넣을 환경변수가 없어 빈 결과가 되고, 그래서 문제가 되지 않는다.
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
