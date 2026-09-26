#!/usr/bin/env python3
"""Material Symbols（Google Fonts 配信）を Android vector drawable に変換して取得する。

Material Symbols は Jetpack Compose 専用の material-icons-extended に依存せず、
Java + XML の Android アプリで公式アイコンを取り込むためのツール。

使い方:
    python3 tools/fetch_material_symbols.py --fill

    - 引数なし: outline（アウトライン）版のみ取得。
    - --fill   : タブ選択用の fill（塗り）版も併せて取得。

取得元（HTTP GET、タイムアウト30秒）:
    outline: https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{name}/default/24px.svg
    fill:    https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{name}/fill1/24px.svg

出力:
    app/src/main/res/drawable/ic_{name}_24.xml        （outline 版）
    app/src/main/res/drawable/ic_{name}_fill_24.xml   （fill 版）

配信される SVG は viewBox="0 -960 960 960"（960 グリッド・y 軸が負）。
Android vector には viewBox の最小座標が無いため viewportWidth/Height を 960 とし、
全 <path> を <group android:translateY="960"> で囲んで y を 0..960 へ移す。
（viewportWidth=24 にすると座標が -960..0 のまま残り何も描画されない点に注意。）

先に全 SVG をメモリへ取得し、1つでも失敗したら何も書かずに非0で終了する
（既存の出力ファイルを壊さない）。書き込み後は「XML として妥当か / path が1つ以上あるか」
を自己検証する。
"""

import argparse
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

# 既定で全部取得するアイコン（outline）。--fill 時は下の FILL_ICONS も取る。
OUTLINE_ICONS = [
    "home", "group", "notifications",
    "badge", "content_copy", "share", "delete",
    "sos", "timer", "history",
    "location_on", "check",
    "check_circle", "error",
    "sync", "bluetooth_disabled",
    "person", "arrow_forward",
]

# --fill でタブ選択時に使う塗り版アイコン。
FILL_ICONS = ["home", "group", "notifications"]

BASE_OUTLINE = (
    "https://fonts.gstatic.com/s/i/short-term/release/"
    "materialsymbolsoutlined/{name}/default/24px.svg"
)
BASE_FILL = (
    "https://fonts.gstatic.com/s/i/short-term/release/"
    "materialsymbolsoutlined/{name}/fill1/24px.svg"
)
TIMEOUT = 30

NAMESPACES = {  # SVG の属性名（viewBox, d）はデフォルト名前空間子には属さない。
    "svg": "http://www.w3.org/2000/svg",
}


def svg_to_vector(svg_text, auto_mirrored):
    """SVG 文字列を Android vector drawable の XML 文字列に変換する。"""
    root = ET.fromstring(svg_text)
    # <path> を SVG 名前空間で収集。
    paths = root.findall(".//svg:path", NAMESPACES)
    path_elems = []
    for p in paths:
        d = p.attrib.get("d")
        if d:
            path_elems.append(d)
    if not path_elems:
        raise ValueError("SVG に d 属性を持つ <path> が1つもありません")

    parts = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp" android:height="24dp"',
        '    android:viewportWidth="960" android:viewportHeight="960">',
        '    <group android:translateY="960">',
    ]
    for d in path_elems:
        parts.append(
            '        <path android:fillColor="#FF000000" '
            'android:pathData="{}" />'.format(d)
        )
    parts.append("    </group>")
    parts.append("</vector>")

    body = "\n".join(parts)
    # autoMirrored はルート <vector> に付ける（arrow_forward）。
    if auto_mirrored:
        body = body.replace(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
            "\n    android:autoMirrored=\"true\"",
        )
    return body, len(path_elems)


def fetch(url):
    """URL を GET し、UTF-8 テキストを返す。失敗時は例外送出。"""
    req = urllib.request.Request(url, headers={"User-Agent": "crosspath-dev-tool"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        data = resp.read()
    return data.decode("utf-8")


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--fill", action="store_true",
        help="タブ選択用の fill（塗り）版も併せて取得する",
    )
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(repo_root, "app", "src", "main", "res", "drawable")

    jobs = []  # (url, out_filename, auto_mirrored)
    for name in OUTLINE_ICONS:
        jobs.append(
            (BASE_OUTLINE.format(name=name), "ic_{}_24.xml".format(name),
             name == "arrow_forward")
        )
    if args.fill:
        for name in FILL_ICONS:
            jobs.append(
                (BASE_FILL.format(name=name), "ic_{}_fill_24.xml".format(name),
                 False)
            )

    # 1) まず全 SVG をメモリへ取得。失敗したら何も書かず非0終了。
    contents = {}
    for url, out_name, _ in jobs:
        sys.stdout.write("fetching {} ...".format(out_name))
        sys.stdout.flush()
        try:
            contents[out_name] = fetch(url)
            print(" ok")
        except Exception as exc:
            print(" FAILED")
            print(
                "[ERROR] 取得に失敗しました（既存ファイルは変更していません）: {} -> {}".format(
                    out_name, exc
                ),
                file=sys.stderr,
            )
            return 1

    # 2) 全成功したので書き込み。
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for url, out_name, auto_mirrored in jobs:
        out_path = os.path.join(out_dir, out_name)
        try:
            body, _ = svg_to_vector(contents[out_name], auto_mirrored)
        except Exception as exc:
            print(
                "[ERROR] 変換に失敗したため、成果物を削除して終了します: {} -> {}".format(
                    out_name, exc
                ),
                file=sys.stderr,
            )
            for w in written:
                try:
                    os.remove(os.path.join(out_dir, w))
                except OSError:
                    pass
            return 1
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(body + "\n")
        written.append(out_name)

    # 3) 自己検証: XML として妥当 + path が1つ以上。
    errors = []
    for out_name in written:
        out_path = os.path.join(out_dir, out_name)
        try:
            tree = ET.parse(out_path)
            paths = tree.findall(".//path")
            translate = tree.findall(".//group")
            if not paths:
                errors.append("{}: path が0個".format(out_name))
            if not translate:
                errors.append("{}: <group translateY> が無い".format(out_name))
        except ET.ParseError as exc:
            errors.append("{}: XML パース失敗 ({})".format(out_name, exc))

    if errors:
        print("[ERROR] 自己検証に失敗:", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1

    print("OK: {} ファイルを書き込みました（{}）".format(len(written), ", ".join(written)))
    return 0


if __name__ == "__main__":
    sys.exit(main())