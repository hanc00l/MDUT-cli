#!/usr/bin/env bash
# build-dist.sh — 组装发布 zip（docs/2 §10 布局）
# 用法: ./cli/build-dist.sh   （先 mvn package）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="1.0.0"
DIST="mdut-cli-2.1.1-cli.${VER}-dist"
STAGE="$ROOT/MDAT-DEV/target/$DIST"

JAR="$ROOT/MDAT-DEV/target/mdut-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "先构建: mvn -f MDAT-DEV/pom.xml clean package" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$STAGE/cli"
cp "$JAR" "$STAGE/mdut.jar"
cp "$ROOT/cli/mdut" "$STAGE/cli/mdut"
chmod +x "$STAGE/cli/mdut"
cp "$ROOT/cli/SKILL.md" "$STAGE/cli/SKILL.md"
cp -r "$ROOT/MDAT-DEV/src/main/Driver" "$STAGE/Driver"
cp -r "$ROOT/MDAT-DEV/src/main/Plugins" "$STAGE/Plugins"

# 边界审计：jar 内零外置资产/零 GUI 资源
if unzip -l "$STAGE/mdut.jar" | grep -qE "javafx|\.fxml|\s+(Driver|Plugins)/|data\.db"; then
    echo "打包边界违约：外置资产进入 uber-jar" >&2
    exit 1
fi

(cd "$STAGE/.." && zip -qr "$DIST.zip" "$DIST")
echo "发布包: $STAGE.zip"
unzip -l "$STAGE.zip" | tail -3
