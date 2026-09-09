#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd "$(dirname "$0")" && pwd)"
bluray_source="${1:?usage: bash test_bluray_menu_routing.sh <libbluray source directory>}"
test_work="$(mktemp -d /tmp/p9-menu-routing.XXXXXX)"
awk '/^#define VK_IS_NUMERIC/ { emit=1 } /^static void _set_button_page/ { exit } emit { print }' \
    "$bluray_source/src/libbluray/decoders/graphics_controller.c" > "$test_work/menu_route_under_test.h"
awk '/^static int _mouse_move/ { emit=1 } /^static int _animate/ { exit } emit { print }' \
    "$bluray_source/src/libbluray/decoders/graphics_controller.c" >> "$test_work/menu_route_under_test.h"
test -s "$test_work/menu_route_under_test.h"
cc -std=c11 -Wall -Wextra -Werror -I "$test_work" -I "$bluray_source/src" -I "$bluray_source/src/libbluray" \
    "$test_dir/bluray_menu_routing_test.c" -o "$test_work/menu_route_test"
"$test_work/menu_route_test"
