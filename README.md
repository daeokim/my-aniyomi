<div align="center">

<img src="./personal/branding/dtoon-icon.svg" alt="Dtoon icon" title="Dtoon icon" width="96"/>

# Dtoon

### 폴더블 화면에 맞춘 개인용 영상·만화 앱

[Aniyomi](https://github.com/aniyomiorg/aniyomi)를 기반으로 접은 화면과 펼친 화면 모두에서 편하게 보도록 다듬은 개인 포크입니다.

[![Latest release](https://img.shields.io/github/v/release/daeokim/my-aniyomi?label=Release&labelColor=27303D&color=6444F4)](https://github.com/daeokim/my-aniyomi/releases/latest)
[![Release build](https://img.shields.io/github/actions/workflow/status/daeokim/my-aniyomi/release.yml?label=Build&labelColor=27303D)](https://github.com/daeokim/my-aniyomi/actions/workflows/release.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-00A9EF?labelColor=27303D)](/LICENSE)

## 다운로드

[**최신 APK 받기 →**](https://github.com/daeokim/my-aniyomi/releases/latest)

*Android 8.0 이상, arm64-v8a 전용*

</div>

## 주요 기능

- **통합 홈**: 최근 본 영상·만화 이어보기, 서재 작품, 여러 소스의 인기·최신 첫 페이지를 한 화면에 모아 보여 줍니다.
- **4탭 카탈로그**: 소스마다 인기 / 최신 / 요일 / 완결 탭을 스와이프로 오가며 3:4 그리드로 탐색합니다. 탭·요일·필터마다 스크롤 위치를 따로 기억합니다.
- **폴더블 대응**: 접으면 위아래, 펼치면 좌우로 목록을 배치하고, 화면을 바꿔도 스크롤 위치를 유지합니다.
- **만화 두 쪽 보기**: 넓은 화면에서 서로 다른 두 페이지를 나란히 보여 줍니다. 표지 단독 표시, 우→좌·좌→우 읽기, 장 경계와 넓은 이미지 처리를 지원합니다.
- **연관 추천**: 만화 상세 화면에서 AniList 추천작과 같은 장르 작품을 보여 주고, 누르면 설치된 소스에서 바로 검색합니다.
- **이어보기 우선**: 실제 최근 기록과 재생 위치를 기준으로 이어보기를 정렬합니다.
- 기존 Aniyomi 기능(로컬 감상, mpv 기반 플레이어, 트래커, 카테고리, 백업 등)을 그대로 쓸 수 있습니다.

## 설치 전 확인

- 앱 ID가 원본 애니요미와 같은 `xyz.jmir.tachiyomi.mi`라서 **함께 설치되지 않고 교체**됩니다.
- 공식 애니요미나 다른 포크와 서명이 달라 그 위에 바로 덮어 설치할 수 없습니다. **백업 생성 → 다운로드 폴더 따로 보관 → 기존 앱 삭제 → Dtoon 설치 → 백업 복원** 순서로 진행하세요.
- 이전 Dtoon 버전 위에는 그대로 업데이트 설치됩니다.
- Dtoon8.9부터는 **설정 → 정보 → 업데이트 확인**에서 새 버전을 받을 수 있고, 앱 시작 시에도 최대 3일에 한 번 확인합니다. 이전 버전은 [Releases](https://github.com/daeokim/my-aniyomi/releases)에서 직접 받으세요.

## 릴리스 만들기 (저장소 관리자용)

`app/build.gradle.kts`의 `versionName`(예: `Dtoon8.9`)과 `versionCode`를 올리고 [README.personal.md](./README.personal.md)에 같은 이름의 `## Dtoon8.9` 섹션(릴리스 노트)을 추가해 `main`에 푸시하면 [Dtoon release](.github/workflows/release.yml) 워크플로가 서명된 APK를 빌드해 Releases에 올립니다. 이미 릴리스된 버전이면 건너뜁니다. Actions 탭에서 직접 실행할 수도 있습니다.

처음 한 번은 **Settings → Secrets and variables → Actions**에 아래 4개를 등록해야 합니다. 값은 로컬 `personal/signing/`의 파일에서 가져옵니다.

| Secret | 값 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 personal/signing/personal.jks` 출력 |
| `SIGNING_STORE_PASSWORD` | `signing.properties`의 `storePassword` |
| `SIGNING_KEY_ALIAS` | `signing.properties`의 `keyAlias` |
| `SIGNING_KEY_PASSWORD` | `signing.properties`의 `keyPassword` |

서명 키를 잃어버리면 기존 설치본에 업데이트할 수 없습니다. 키 파일은 git에 올리지 말고 안전하게 따로 보관하세요. 로컬 빌드 방법과 세부 동작은 [README.personal.md](./README.personal.md)를 참고하세요.

## 출처와 고지

- 기반: [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi) (Mihon/Tachiyomi 계열). 원 개발자와 기여자 여러분께 감사드립니다.
- 아이콘: [Pinyon Script](https://github.com/SorkinType/Pinyon) 글꼴의 D·O 윤곽, SIL Open Font License 1.1 ([세부 정보](./personal/branding/README.md))
- 이 앱은 콘텐츠를 호스팅하지 않으며, 앱에서 연결하는 각 콘텐츠 제공자와 관계가 없습니다. 각 서비스의 이용 조건은 별도로 적용됩니다.
- 이 저장소는 개인 포크이며 공식 Aniyomi 프로젝트가 아닙니다.

## License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Aniyomi Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
