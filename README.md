# 배틀서브웨이 (BW2 Battle Subway) — Android

BW2 배틀서브웨이의 슈퍼싱글을 떼어낸 단독 실행 안드로이드 게임. 개인 학습/소장용 팬 프로젝트입니다. 배포 금지.

- 앱을 켜면 바로 슈퍼싱글 1번째 열차의 렌탈 선택부터 시작 (저장된 도전이 있으면 이어서)
- 싱글배틀 · Lv.50 고정, 7연전 = 1열차, 패배 전까지 무한 반복
- 포켓몬을 고르기 전 다음 상대 정보 공개: 1라운드 3마리 전부, 2라운드 2마리, 3라운드 선두와 기술 1개, 4라운드부터 주 사용 타입
- 승리 후 상대 포켓몬과 교환, 7열차(49전째) 마지막 칸에 배틀서브웨이 보스(Ingo) 등장
- 연승·최고 기록·교환 횟수·파티는 매 단계 자동 저장
- 1~5세대 포켓몬 296종, 기술 414개·특성 124개·도구 106개 구현

## 설치

`release/bw2-battle-subway-v2.0.apk`를 휴대폰에 받아 설치합니다(“출처를 알 수 없는 앱” 허용 필요, Android 7.0 이상).

## 출처

| 내용 | 출처 |
|---|---|
| 포켓몬 세트(1000개)·트레이너(315명) | `pokebw2` `data/facilities/battle_subway/` (JSON) |
| 열차/트레이너/세트 선택·IV 규칙, 보스 등장 | `pokebw2` `src/ov033/bsubway_scr.c`, `src/ov012/fld_btl_inst_tool.c` |
| 배틀 메커니즘(데미지·급소·명중·스피드·상성표) | `pokebw2` `src/ov167/btl_server_flow.c`, `btl_calc.c` |
| 종족치·기술·도구·특성 데이터, 포켓몬 스프라이트 | `pokebw2` `data/pokemon`, `data/moves`, `data/items`, `data/abilities` |
| 트레이너 스프라이트 | [The Spriters Resource – Pokémon Black/White "Trainers"](https://www.spriters-resource.com/ds_dsi/pokemonblackwhite/) (가장 비슷한 직업에 매핑, Wayback 경유) |
| 배틀 배경 | [Pokemon B2W2 Battle Backgrounds by Snivy101 (DeviantArt)](https://www.deviantart.com/snivy101/art/Pokemon-B2W2-Battle-Backgrounds-319706977) |
| 한국어 기술/도구/특성 이름·설명 | PokeAPI CSV |

## v2.0 변경점

- 포켓몬을 HGSS 배틀팩토리 세트에서 **BW2 배틀서브웨이 슈퍼싱글** 세트로 전면 교체 (4세대 한정 → 1~5세대 전체)
- 배틀 엔진에 5세대 기술·특성을 대거 추가 (매직가드/위협/자기과신/하복/의욕/하늘의은총/모래먼지 가속 등, 신규 기술 효과 다수)
- 팩토리헤드(Thorton) → 배틀서브웨이 보스(Ingo)

## 빌드

```sh
python3 tools/extract.py              # ../pokebw2 저장소가 있어야 함
python3 tools/trainer_sprites.py      # Pillow 필요
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

## 구조

- `engine/` — 배틀 엔진(순수 Kotlin): `Battle.kt`(턴 진행·기술·특성·도구), `Ai.kt`(상대 AI), `Core.kt`(상성표·능력치·한국어 조사)
- `factory/` — 슈퍼싱글 규칙(`FactoryRules.kt`)과 진행/저장 상태(`FactoryState.kt`)
- `ui/` — Jetpack Compose 화면(렌탈, 대전 전, 배틀, 교환, 라운드 클리어, 패배)
