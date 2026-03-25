# ADR-0001: Multi-Module Runtime Structure

- Status: Accepted
- Date: 2026-03-25

## Context

이 저장소는 하나의 애플리케이션이 아니라 여러 실행 단위를 함께 보관하는 구조다.

- `front/` 는 React + Vite 웹 클라이언트다.
- `android/` 는 Kotlin 기반 Android 앱이다.
- `book/` 은 로컬에서 빠르게 실행 가능한 Spring Boot 예매 백엔드다.
- `tingting/` 은 Swagger, Actuator, Prometheus를 포함한 API 서버다.

팀은 같은 도메인을 공유하는 여러 클라이언트와 서버를 동시에 개발해야 했고, 각 실행 단위를 완전히 분리된 저장소로
운영하기보다 하나의 저장소 안에서 관리했다.

## Decision

실행 단위를 모듈 단위 디렉터리로 분리한 멀티 모듈 저장소 구조를 유지한다.

## Consequences

### Positive

- 클라이언트와 서버 변경을 하나의 저장소에서 함께 추적할 수 있다.
- Android, Web, Backend가 같은 도메인 용어와 API 계약을 공유하기 쉽다.
- 로컬 개발 시 `book/` 같은 단순 실행 모듈을 별도로 다룰 수 있다.

### Negative

- 루트 README만으로는 실제 실행 방법을 이해하기 어렵다.
- 모듈별 환경 설정 방식이 달라 신규 참여자가 혼동할 수 있다.
- 백엔드 모듈이 둘 이상이라 역할 경계가 문서로 보강되지 않으면 중복으로 보일 수 있다.

## Notes

이 ADR의 후속 작업으로 루트 README에 모듈별 실행 방법과 아키텍처 다이어그램을 추가한다.
