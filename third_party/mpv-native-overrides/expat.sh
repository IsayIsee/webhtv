#!/bin/bash -e

. ../../include/path.sh

build=_build$ndk_suffix

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf "$build"
	exit 0
else
	exit 255
fi

case "$ndk_triple" in
	aarch64-linux-android) android_abi=arm64-v8a ;;
	arm-linux-androideabi) android_abi=armeabi-v7a ;;
	*) echo "Unsupported Expat Android ABI: $ndk_triple" >&2; exit 1 ;;
esac

cmake -S expat -B "$build" -G Ninja \
	-DCMAKE_TOOLCHAIN_FILE="$DIR/sdk/android-ndk-$v_ndk/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI="$android_abi" \
	-DANDROID_PLATFORM=android-24 \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_INSTALL_PREFIX=/usr/local \
	-DCMAKE_POSITION_INDEPENDENT_CODE=ON \
	-DEXPAT_SHARED_LIBS=OFF \
	-DEXPAT_BUILD_TOOLS=OFF \
	-DEXPAT_BUILD_EXAMPLES=OFF \
	-DEXPAT_BUILD_TESTS=OFF \
	-DEXPAT_BUILD_DOCS=OFF \
	-DEXPAT_BUILD_FUZZERS=OFF \
	-DEXPAT_BUILD_PKGCONFIG=ON \
	-DEXPAT_ENABLE_INSTALL=ON

cmake --build "$build" --parallel "$cores"
DESTDIR="$prefix_dir" cmake --install "$build"
