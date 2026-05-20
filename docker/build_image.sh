#! /bin/bash

set -euo pipefail
cd "$(dirname "$0")"

# --help, -h 옵션 (옵션 파싱보다 먼저 처리)
for arg in "$@"; do
    case "$arg" in
        --help|-h)
            echo "사용법: $0 [--tag <VERSION>] [--uid <UID>] [--gid <GID>] [--host-user]"
            echo ""
            echo "옵션:"
            echo "  --tag, -t <VERSION>  버전 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
            echo "  --uid <UID>          mdt 사용자 UID 지정 (기본값은 Dockerfile의 ARG UID)"
            echo "  --gid <GID>          mdt 그룹 GID 지정 (기본값은 Dockerfile의 ARG GID)"
            echo "  --host-user, -H      호스트 사용자 UID/GID로 빌드 (볼륨 마운트 시 권한 맞추기)"
            echo "  --help, -h           도움말 출력"
            echo ""
            echo "예제:"
            echo "  $0                    # 기본 버전, Dockerfile 기본 UID로 빌드"
            echo "  $0 --tag 1.3.0        # 1.3.0 버전으로 빌드"
            echo "  $0 --host-user        # 호스트 사용자 UID/GID로 빌드"
            echo "  $0 -H --tag 1.3.0     # 호스트 UID/GID + 1.3.0 버전으로 빌드"
            exit 0
            ;;
    esac
done

# --tag(-t), --uid, --gid, --host-user 옵션 처리
MDT_VERSION=""
BUILD_UID=""
BUILD_GID=""
USE_HOST_USER=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag|-t)
            MDT_VERSION="$2"
            shift 2
            ;;
        --uid)
            BUILD_UID="$2"
            shift 2
            ;;
        --gid)
            BUILD_GID="$2"
            shift 2
            ;;
        --host-user|-H)
            USE_HOST_USER=true
            shift 1
            ;;
        *)
            echo "오류: 알 수 없는 옵션 '$1'" >&2
            echo "사용법은 '$0 --help' 참고" >&2
            exit 1
            ;;
    esac
done

# --host-user 옵션 시 호스트 사용자 UID/GID 사용
if [ "$USE_HOST_USER" = true ]; then
    BUILD_UID=$(id -u)
    BUILD_GID=$(id -g)
fi

# MDT_VERSION이 지정되지 않았으면 환경변수 fallback 후 검증
if [ -z "$MDT_VERSION" ]; then
    MDT_VERSION="${MDT_BUILD_VERSION:-}"
fi
if [ -z "$MDT_VERSION" ]; then
    echo "오류: --tag 또는 \$MDT_BUILD_VERSION 둘 중 하나는 지정해야 합니다." >&2
    exit 1
fi
REPOSITORY="mdt-manager:$MDT_VERSION"

# MDT_HOME 환경변수 가드
if [ -z "${MDT_HOME:-}" ]; then
    echo "오류: \$MDT_HOME 환경변수가 설정되어 있어야 합니다." >&2
    exit 1
fi
MDT_MANAGER_HOME="$MDT_HOME/mdt-manager"

# 빌드 종료 시 임시 jar 정리 (성공/실패 모두)
trap 'rm -f mdt-manager-all.jar mdt-instance-all.jar' EXIT

# 기존 이미지 삭제 (없으면 무시)
docker image rmi -f "$REPOSITORY" 2>/dev/null || true

echo "==> Docker 이미지 빌드 시작: $REPOSITORY"
cp "$MDT_MANAGER_HOME/mdt-manager-all.jar"  mdt-manager-all.jar
cp "$MDT_MANAGER_HOME/mdt-instance-all.jar" mdt-instance-all.jar

# Docker 이미지 빌드 (UID/GID가 지정된 경우 --build-arg 전달)
BUILD_ARGS=(-t "$REPOSITORY")
if [ -n "$BUILD_UID" ]; then
    BUILD_ARGS+=(--build-arg "UID=$BUILD_UID")
fi
if [ -n "$BUILD_GID" ]; then
    BUILD_ARGS+=(--build-arg "GID=$BUILD_GID")
fi
docker build "${BUILD_ARGS[@]}" .

# 성공 메시지 (실패 시 set -e가 트리거되어 여기까지 도달하지 않음)
echo "==> 빌드 완료: $REPOSITORY"
