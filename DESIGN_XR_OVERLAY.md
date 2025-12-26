# XR Segmentation Overlay Design (Jetpack XR + OpenGL ES 3.0)

## Goal
XR 시야에서 세그멘테이션 결과를 2D 패널이 아니라 실제 시야 위에 바로 오버레이로 표시한다.
이를 위해 SceneCore StereoSurfaceEntity의 Surface에 OpenGL ES 3.0으로 합성 렌더링한다.

## Scope
- Jetpack XR SceneCore 사용 (androidx.xr.scenecore)
- OpenGL ES 3.0 + EGL 사용
- 서버는 마스크를 raw bytes (8-bit, 1 channel)로 반환
- 클라이언트는 카메라 텍스처 + 마스크 텍스처를 합성

## Non-Goals
- 깊이 기반 3D 메쉬 구성은 범위 밖
- 스테레오 분리 렌더링은 범위 밖 (우선 MONO 사용)

## High-Level Architecture
1) CameraX가 프레임 캡처
2) JPEG을 서버로 전송
3) 서버에서 세그멘테이션 후 마스크 반환
4) 클라이언트가 카메라 텍스처와 마스크 텍스처 업로드
5) GL 셰이더에서 합성
6) 결과를 XR Surface에 출력

## Data Formats
### Camera -> Server
- JPEG bytes (현재 파이프라인 유지)
- Length-prefixed: [4 bytes big endian length][JPEG bytes]

### Server -> Client (Mask)
권장 (raw bytes, fixed size):
- Header: [width:int32][height:int32][format:byte]
  - format: 0 = 8-bit gray (0..255)
- Body: [width*height bytes]

프레임 크기가 고정이면 연결 직후 1회만 width/height를 보내도 됨.

## XR Rendering Path
- SceneCore Session 생성 (Session.create(activity))
- StereoSurfaceEntity 생성 (MONO)
- Surface 획득: surfaceEntity.getSurface()
- EGL 초기화 후 해당 Surface에 렌더
- 렌더 루프:
  - 카메라 프레임 텍스처 업로드
  - 마스크 텍스처 업로드
  - 풀스크린 쿼드 렌더
  - swap buffers

## Shader Logic
- 두 텍스처는 동일한 UV 좌표 사용
- mask = texture(maskTex, uv).r (0..1)
- overlayColor = vec3(1.0) (흰색)
- output = mix(cameraColor, overlayColor, mask * overlayAlpha)

## Key Classes (Client)
- XrOverlayRenderer
  - EGL context, shaders, textures 관리
  - updateCameraFrame(...) / updateMask(...) 제공
  - XR Surface에 렌더

- NetworkClient
  - 마스크 수신 로직 추가
  - 최신 마스크를 callback/flow로 제공

- MainActivity
  - SceneCore Session + StereoSurfaceEntity 생성
  - Surface를 XrOverlayRenderer에 전달
  - 카메라/네트워크 결과를 renderer에 연결

## Threading Model
- 네트워크 수신: IO coroutine
- 카메라 분석: CameraX 분석 쓰레드
- GL 렌더러: 전용 쓰레드 (EGL은 단일 쓰레드 바인딩 필요)
- 최신 프레임/마스크는 AtomicReference 또는 큐로 전달

## Risks / Pitfalls
- 카메라 해상도와 마스크 해상도 불일치 시 정렬 문제 발생
- 텍스처 업로드 비용이 크므로 해상도 고정 및 다운샘플 고려
- EGL 컨텍스트는 한 쓰레드에서만 사용 가능
- SceneCore Session 생성 시 SCENE_UNDERSTANDING 권한 필요

## Next Steps (Implementation)
1) XrOverlayRenderer 추가 (EGL + GL ES 3.0)
2) NetworkClient에 마스크 수신 기능 추가
3) CameraX 프레임을 GL 텍스처로 변환/업로드
4) MainActivity에서 렌더러 업데이트 흐름 연결

## Open Questions
- 마스크만 보낼지, 컬러 세그멘테이션을 보낼지?
- 마스크/카메라 해상도는 고정인가?
- 오버레이 색상/투명도는 어떻게 할지?
