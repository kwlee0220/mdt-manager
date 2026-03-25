#! /bin/bash

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
            break
            ;;
    esac
done

# --host-user 옵션 시 호스트 사용자 UID/GID 사용
if [ "$USE_HOST_USER" = true ]; then
    BUILD_UID=$(id -u)
    BUILD_GID=$(id -g)
fi

# MDT_VERSION이 지정되지 않았으면 기본값 사용
if [ -z "$MDT_VERSION" ]; then
    MDT_VERSION="$MDT_BUILD_VERSION"
fi
REPOSITORY="mdt-manager:$MDT_VERSION"

# 사용법 출력 (--help 옵션)
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    echo "사용법: $0 [--tag <VERSION>] [--uid <UID>] [--gid <GID>] [--host-user]"
    echo ""
    echo "옵션:"
    echo "  --tag, -t <VERSION>  버전 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
    echo "  --uid <UID>          mdt 사용자 UID 지정 (기본값: 1000)"
    echo "  --gid <GID>          mdt 그룹 GID 지정 (기본값: 1000)"
    echo "  --host-user, -H      호스트 사용자 UID/GID로 빌드 (볼륨 마운트 시 권한 맞추기)"
    echo "  --help, -h           도움말 출력"
    echo ""
    echo "예제:"
    echo "  $0                    # 기본 버전, UID 1000으로 빌드"
    echo "  $0 --tag 1.3.0        # 1.3.0 버전으로 빌드"
    echo "  $0 --host-user        # 호스트 사용자 UID/GID로 빌드"
    echo "  $0 -H --tag 1.3.0     # 호스트 UID/GID + 1.3.0 버전으로 빌드"
    exit 0
fi

# 기존 이미지 삭제
docker image rmi -f $REPOSITORY

echo "==> Docker 이미지 빌드 시작: $REPOSITORY"
MDT_MANAGER_HOME=$MDT_HOME/mdt-manager
cp $MDT_MANAGER_HOME/mdt-manager-all.jar mdt-manager-all.jar
cp $MDT_MANAGER_HOME/mdt-instance-all.jar mdt-instance-all.jar

# Docker 이미지 빌드 (UID/GID가 지정된 경우 --build-arg 전달)
BUILD_ARGS=(-t "$REPOSITORY")
[ -n "$BUILD_UID" ] && BUILD_ARGS+=(--build-arg "UID=$BUILD_UID")
[ -n "$BUILD_GID" ] && BUILD_ARGS+=(--build-arg "GID=$BUILD_GID")
docker build "${BUILD_ARGS[@]}" .

# 성공 메시지
if [ $? -eq 0 ]; then
    echo "==> 빌드 완료: $REPOSITORY"
else
    echo "==> 빌드 실패!"
    exit 1
fi

# 클론한 디렉토리 정리
rm mdt-manager-all.jar
rm mdt-instance-all.jar
