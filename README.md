# 배틀팩토리 (BW2 Battle Factory) — Android

HGSS 배틀팩토리를 BW2(5세대) 배틀 시스템으로 떼어낸 단독 실행 안드로이드 게임. 개인 학습/소장용 팬 프로젝트입니다. 배포 금지.

- 앱을 켜면 바로 배틀팩토리 1라운드 렌탈 선택부터 시작 (저장된 도전이 있으면 이어서)
- 싱글배틀 · 오픈레벨(Lv.100)만 지원, 7연전 = 1라운드, 패배 전까지 무한 반복
- 승리 후 상대 포켓몬과 교환, 21전·49전에 팩토리헤드 등장
- 진행 상황(연승, 최고 기록, 교환 횟수, 파티)은 매 단계 자동 저장

## 설치

`release/bw2-battle-factory-v1.1.apk`를 휴대폰에 받아 설치합니다(“출처를 알 수 없는 앱” 허용 필요, Android 7.0 이상).

## v1.1 변경점

- 포켓몬을 고르기 전에 다음 상대 정보를 미리 알려줌: 1라운드 3마리 전부, 2라운드 2마리, 3라운드 선두 1마리와 그 기술 1개, 4라운드부터 주로 쓰는 타입
- 배틀 배경을 BW2 스프라이트(시설 스테이지·팩토리헤드 밤 스테이지)로 교체
- 배틀 시작 시 앱이 종료되던 버그 수정 (배틀 애니메이션이 Compose 프레임 클록 없이 돌도록 변경)

## 출처

| 내용 | 출처 |
|---|---|
| 팩토리 세트(600개, 351–950번)·트레이너 데이터 | `pokeheartgold` `files/a/2/0/3`, `files/a/2/0/2` (NARC) |
| 라운드/트레이너/세트 선택 규칙 | `pokeheartgold` `asm/overlay_80_02236B78.s`, `overlay_80_0222BDF4.s`, `src/unk_0204B538.c` |
| 배틀 메커니즘(데미지·급소·명중·스피드·상성표) | `pokebw2` `src/ov167/btl_server_flow.c`, `btl_calc.c` |
| 종족치·기술·도구 데이터, 포켓몬 스프라이트 | `pokebw2` `data/pokemon`, `data/moves`, `data/items` |
| 트레이너 스프라이트 | [The Spriters Resource – Pokémon Black/White "Trainers"](https://www.spriters-resource.com/ds_dsi/pokemonblackwhite/) (Wayback Machine 경유) |
| 한국어 기술/도구/특성 이름·설명 | PokeAPI CSV |

## 빌드

```sh
python3 tools/extract.py              # 두 저장소가 ../pokeheartgold, ../pokebw2 에 있어야 함
python3 tools/trainer_sprites.py      # Pillow 필요
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

## 구조

- `engine/` — 배틀 엔진(순수 Kotlin): `Battle.kt`(턴 진행·기술·특성·도구), `Ai.kt`(상대 AI), `Core.kt`(상성표·능력치·한국어 조사)
- `factory/` — 팩토리 규칙(`FactoryRules.kt`)과 진행/저장 상태(`FactoryState.kt`)
- `ui/` — Jetpack Compose 화면(렌탈, 대전 전, 배틀, 교환, 라운드 클리어, 패배)
