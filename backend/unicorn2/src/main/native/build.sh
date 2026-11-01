#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCES_DIR="$SCRIPT_DIR/../resources/natives"
# The backend builds against the project's unicorn fork: upstream tag 2.1.4 plus
#   fix(apple): keep the jit write protection correct across hooks in a foreign runtime
#   feat(tcg): give instruction fetch its own view of executable memory
# The second commit is what UC_CTL_INSN_FETCH_COHERENCY (used by Unicorn2Backend) needs.
UNICORN_HOME="${UNICORN_HOME:-$HOME/git/unicorn}"
UNICORN_MARKER="UC_CTL_INSN_FETCH_COHERENCY"
IMAGE_NAME="unidbg-unicorn2-builder"
NPROC=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)

CLEAN=false
while [[ "$1" == --* ]]; do
    case "$1" in
        --clean) CLEAN=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

cd "$SCRIPT_DIR"

# --- Prepare upstream unicorn source ---

prepare_unicorn_source() {
    if [ ! -d "$UNICORN_HOME/.git" ]; then
        echo "ERROR: the unicorn fork is not at $UNICORN_HOME (override with UNICORN_HOME=...)" >&2
        echo "  clone https://github.com/unicorn-engine/unicorn at tag 2.1.4 and apply the fork commits" >&2
        exit 1
    fi
    if ! git -C "$UNICORN_HOME" diff --quiet HEAD || ! git -C "$UNICORN_HOME" diff --cached --quiet HEAD; then
        echo "ERROR: $UNICORN_HOME has uncommitted changes; commit or stash them first" >&2
        git -C "$UNICORN_HOME" status --short >&2
        exit 1
    fi
    if ! grep -q "$UNICORN_MARKER" "$UNICORN_HOME/include/unicorn/unicorn.h"; then
        echo "ERROR: $UNICORN_HOME does not carry $UNICORN_MARKER" >&2
        echo "  HEAD: $(git -C "$UNICORN_HOME" log -1 --format='%h %s' 2>&1)" >&2
        exit 1
    fi
    echo "  unicorn fork: $(git -C "$UNICORN_HOME" log -1 --format='%h %s')"

    # qemu/configure aborts without it (it only probes for the binary), and cmake does not
    # propagate that failure: the build would then die later on unrelated compile errors.
    if ! command -v "${PKG_CONFIG:-pkg-config}" > /dev/null; then
        echo "ERROR: '${PKG_CONFIG:-pkg-config}' not found, required by unicorn's qemu/configure (macOS: brew install pkgconf)" >&2
        exit 1
    fi
}

# Archive the fork for the Docker builds, which need the source inside the build context.
archive_unicorn_source() {
    prepare_unicorn_source
    git -C "$UNICORN_HOME" archive --format=tar HEAD > "$SCRIPT_DIR/unicorn-src.tar"
}

# --- Build unicorn static library ---

build_unicorn_lib() {
    local build_dir="$1"
    local extra_cmake_args="$2"

    prepare_unicorn_source

    if $CLEAN && [ -d "$build_dir" ]; then
        echo "  Cleaning $build_dir ..."
        rm -rf "$build_dir"
    fi

    local need_configure=false
    if [ ! -d "$build_dir" ]; then
        mkdir -p "$build_dir"
        need_configure=true
    elif [ ! -f "$build_dir/Makefile" ]; then
        need_configure=true
    fi

    pushd "$build_dir" > /dev/null
    if $need_configure; then
        echo "  Configuring in $build_dir ..."
        cmake .. -DCMAKE_BUILD_TYPE=Release \
              -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
              -DUNICORN_ARCH="arm;aarch64" \
              -DUNICORN_BUILD_TESTS=OFF \
              $extra_cmake_args
    fi
    echo "  Building in $build_dir ..."
    make -j"$NPROC" unicorn_archive
    popd > /dev/null
}

# --- macOS native builds ---

build_osx_arm64() {
    echo "=== Building for osx_arm64 (native) ==="

    build_unicorn_lib "$UNICORN_HOME/build_arm64" "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"

    JAVA_INC="$(realpath "$JAVA_HOME"/include)"
    JAVA_PLATFORM_INC="$(dirname "$(find "$JAVA_INC" -name jni_md.h)")"

    xcrun -sdk macosx clang -arch arm64 -o libunicorn.dylib -shared -O3 -DNDEBUG \
      -mmacosx-version-min=11.0 \
      -I "$UNICORN_HOME/include" \
      -I "$JAVA_INC" -I "$JAVA_PLATFORM_INC" \
      -fPIC -Wall -Wno-missing-braces \
      unicorn.c sample_arm.c sample_arm64.c \
      "$UNICORN_HOME/build_arm64/libunicorn.a"

    mkdir -p "$RESOURCES_DIR/osx_arm64"
    mv libunicorn.dylib "$RESOURCES_DIR/osx_arm64/"

    echo "Done: $RESOURCES_DIR/osx_arm64/libunicorn.dylib"
    ls -l "$RESOURCES_DIR/osx_arm64/libunicorn.dylib"
    echo
}

build_osx_64() {
    echo "=== Building for osx_64 (cross-compile x86_64) ==="

    build_unicorn_lib "$UNICORN_HOME/build_x86_64" \
        "-DCMAKE_C_COMPILER=$SCRIPT_DIR/cc-x86_64.sh -DCMAKE_OSX_ARCHITECTURES=x86_64 -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15"

    JAVA_INC="$(realpath "$JAVA_HOME"/include)"
    JAVA_PLATFORM_INC="$(dirname "$(find "$JAVA_INC" -name jni_md.h)")"

    xcrun -sdk macosx clang -arch x86_64 -o libunicorn.dylib -shared -O3 -DNDEBUG \
      -mmacosx-version-min=10.15 \
      -I "$UNICORN_HOME/include" \
      -I "$JAVA_INC" -I "$JAVA_PLATFORM_INC" \
      -fPIC -Wall -Wno-missing-braces \
      unicorn.c sample_arm.c sample_arm64.c \
      "$UNICORN_HOME/build_x86_64/libunicorn.a"

    mkdir -p "$RESOURCES_DIR/osx_64"
    mv libunicorn.dylib "$RESOURCES_DIR/osx_64/"

    echo "Done: $RESOURCES_DIR/osx_64/libunicorn.dylib"
    ls -l "$RESOURCES_DIR/osx_64/libunicorn.dylib"
    echo
}

# --- Docker builds (cross-compilation for Linux / Windows) ---

build_linux() {
    local platform=$1
    local output_dir=$2

    echo "=== Building for $output_dir ($platform) ==="

    archive_unicorn_source
    local docker_args=""
    if $CLEAN; then
        docker_args="--no-cache"
    fi

    docker build --platform "$platform" $docker_args -t "${IMAGE_NAME}-${output_dir}" .

    echo "Extracting libunicorn.so..."
    mkdir -p "$RESOURCES_DIR/$output_dir"
    CONTAINER_ID=$(docker create --platform "$platform" "${IMAGE_NAME}-${output_dir}")
    docker cp "$CONTAINER_ID:/build/jni/build/libunicorn.so" "$RESOURCES_DIR/$output_dir/libunicorn.so"
    docker rm "$CONTAINER_ID" > /dev/null

    echo "Done: $RESOURCES_DIR/$output_dir/libunicorn.so"
    ls -l "$RESOURCES_DIR/$output_dir/libunicorn.so"
    echo
}

build_windows() {
    echo "=== Building for windows_64 (MinGW cross-compilation) ==="

    archive_unicorn_source
    local docker_args=""
    if $CLEAN; then
        docker_args="--no-cache"
    fi

    docker build $docker_args -f Dockerfile.windows -t "${IMAGE_NAME}-windows_64" .

    echo "Extracting unicorn.dll..."
    mkdir -p "$RESOURCES_DIR/windows_64"
    CONTAINER_ID=$(docker create "${IMAGE_NAME}-windows_64")
    docker cp "$CONTAINER_ID:/build/jni/unicorn.dll" "$RESOURCES_DIR/windows_64/unicorn.dll"
    docker rm "$CONTAINER_ID" > /dev/null

    echo "Done: $RESOURCES_DIR/windows_64/unicorn.dll"
    ls -l "$RESOURCES_DIR/windows_64/unicorn.dll"
    echo
}

# --- Native linux x86_64 build (the platform this project runs the emulator on) ---

build_linux_64() {
    echo "=== Building for linux_64 (native) ==="

    build_unicorn_lib "$UNICORN_HOME/build" ""

    local jni_build="$SCRIPT_DIR/.build-jni-linux-64"
    if $CLEAN && [ -d "$jni_build" ]; then
        rm -rf "$jni_build"
    fi
    mkdir -p "$jni_build"
    (cd "$jni_build" && cmake "$SCRIPT_DIR" -DUNICORN_HOME="$UNICORN_HOME" > /dev/null && make)

    mkdir -p "$RESOURCES_DIR/linux_64"
    mv "$jni_build/libunicorn.so" "$RESOURCES_DIR/linux_64/"

    # The published jar takes linux_64/libunicorn.so from target/generated-resources
    # (src/main/resources excludes it), the same place scripts/release.py stages it.
    local generated="$SCRIPT_DIR/../../../target/generated-resources/natives/linux_64"
    mkdir -p "$generated"
    cp "$RESOURCES_DIR/linux_64/libunicorn.so" "$generated/"

    echo "Done: $RESOURCES_DIR/linux_64/libunicorn.so"
    ls -l "$RESOURCES_DIR/linux_64/libunicorn.so" "$generated/libunicorn.so"
    echo
}

# --- Main ---

TARGET=${1:-all}

case "$TARGET" in
    osx_arm64)
        build_osx_arm64
        ;;
    osx_64)
        build_osx_64
        ;;
    osx)
        build_osx_arm64
        build_osx_64
        ;;
    linux_arm64)
        build_linux linux/arm64 linux_arm64
        ;;
    linux_64)
        build_linux_64
        ;;
    windows_64)
        build_windows
        ;;
    docker)
        build_linux linux/arm64 linux_arm64
        build_linux linux/amd64 linux_64
        build_windows
        ;;
    all)
        build_osx_arm64
        build_osx_64
        build_linux linux/arm64 linux_arm64
        build_linux linux/amd64 linux_64
        build_windows
        ;;
    *)
        echo "Usage: $0 [--clean] [osx_arm64|osx_64|osx|linux_64|linux_arm64|windows_64|docker|all]"
        echo ""
        echo "Options:"
        echo "  --clean     Clean build (remove build dirs and rebuild from scratch)"
        echo ""
        echo "Targets:"
        echo "  osx_arm64   - macOS ARM64 (native build)"
        echo "  osx_64      - macOS x86_64 (cross-compile)"
        echo "  osx         - both macOS targets"
        echo "  linux_arm64 - Linux ARM64 (Docker)"
        echo "  linux_64    - Linux x86_64 (Docker)"
        echo "  windows_64  - Windows x86_64 (Docker + MinGW)"
        echo "  docker      - all Docker targets (linux + windows)"
        echo "  all         - all platforms (default)"
        exit 1
        ;;
esac
