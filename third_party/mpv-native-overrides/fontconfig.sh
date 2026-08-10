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

unset CC CXX
meson setup "$build" --cross-file "$prefix_dir/crossfile.txt" \
	-Ddoc=disabled -Ddoc-txt=disabled -Ddoc-man=disabled \
	-Ddoc-pdf=disabled -Ddoc-html=disabled -Dnls=disabled \
	-Dtests=disabled -Dtools=disabled -Dcache-build=disabled \
	-Diconv=disabled -Dxml-backend=expat -Dfontations=disabled \
	-Dadditional-fonts-dirs=no

ninja -C "$build" -j"$cores"
DESTDIR="$prefix_dir" ninja -C "$build" install
