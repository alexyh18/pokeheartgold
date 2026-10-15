# 배틀트리 (USUM Battle Tree) — Android

포켓몬 울트라썬·울트라문의 배틀트리(싱글)를 떼어낸 단독 실행 안드로이드 게임. 개인 학습/소장용 팬 프로젝트입니다. 배포 금지.

- 앱을 켜면 바로 렌탈 포켓몬 6마리 중 3마리를 고르는 화면부터 시작 (저장된 도전이 있으면 이어서)
- 싱글배틀 · Lv.50 고정, 7연전 = 1라운드, 패배 전까지 무한 반복
- 포켓몬을 고르기 전 다음 상대 정보 공개: 1라운드 3마리 전부, 2라운드 2마리, 3라운드 선두와 기술 1개, 4라운드부터 주 사용 타입
- 승리 후 쓰러뜨린 트레이너의 포켓몬과 1마리 교환 가능
- 연승·최고 기록·교환 횟수·파티는 매 단계 자동 저장
- 포켓몬 풀은 **배틀트리에 실제로 등장하는 세트**(947세트 · 245종). 트레이너는 각자 정해진 연승 구간에서만 등장
- 7세대 메커니즘: 페어리 타입 포함 18타입 상성, Gen7 종족치/기술/특성/도구

## 설치

`release/usum-battle-tree-v1.0.apk`를 휴대폰에 받아 설치합니다(“출처를 알 수 없는 앱” 허용 필요, Android 7.0 이상).

## 출처

| 내용 | 출처 |
|---|---|
| 배틀트리 세트·트레이너 데이터 | [cristiannomartins/Tree-Sets](https://github.com/cristiannomartins/Tree-Sets) (SM/USUM Battle Tree 카탈로그, CSV/TSV) |
| 종족치·기술 수치·타입상성·한국어 이름·스프라이트 | [PokéAPI](https://pokeapi.co/) (7세대) · PokeAPI CSV 덤프 |
| 배틀 엔진(데미지·급소·명중·스피드·상성·특성·도구) | 자작 코틀린 엔진 (bw2 기반 Gen5 엔진을 Gen7/페어리로 각색) |

## 빌드 / 데이터 생성

```sh
source tools/env.sh                 # JDK17 + Android SDK 경로 설정
python3 tools/extract_usum.py       # Tree-Sets + PokeAPI -> app/src/main/assets/{data,sprites}
./gradlew assembleRelease           # release/usum-battle-tree-vX.Y.apk
```
`tools/apicache/`에 PokeAPI 응답이 캐시되므로 재실행은 네트워크 없이 빠르게 됩니다.

## 구현 참고

- bw2 배틀서브웨이 프로젝트(`../bw2-factory`)와 **완전히 독립**된 별도 프로젝트입니다.
- Gen5 엔진을 가져오면서 **페어리 타입(id 17)**을 타입 상성표(18×18)에 추가했고, 도구 id를 PokeAPI 체계로 재정의했습니다.
- Mega 세트(49개)와 Z크리스탈 효과는 v1에서 제외/미구현(세트는 지닌 도구 없이 동작). Rotom·Oricorio·Lycanroc의 폼 세트는 기본 폼으로 매핑.

## v1.0

- 첫 릴리스: USUM 배틀트리 포켓몬 풀, Gen7 페어리 타입, 7연전 렌탈 챌린지.
