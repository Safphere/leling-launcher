#!/bin/bash
# 用法: ./shot.sh <名字>  —— 截图 + 导出UI层级到 docs/shots/
export MSYS_NO_PATHCONV=1
ADB="ADB"
SHOTS="${SHOTS:-shots}"
NAME="${1:-shot}"
mkdir -p "$SHOTS"
"$ADB" -s emulator-5554 exec-out screencap -p > "$SHOTS/${NAME}.png"
# 先删除设备上的旧dump，避免uiautomator失败时pull到残留
"$ADB" -s emulator-5554 shell rm -f /sdcard/ui.xml
OUT=$("$ADB" -s emulator-5554 shell uiautomator dump /sdcard/ui.xml 2>&1)
if echo "$OUT" | grep -q "dumped"; then
    "$ADB" -s emulator-5554 pull /sdcard/ui.xml "$SHOTS/${NAME}_ui.xml" >/dev/null 2>&1
else
    echo "!!! uiautomator dump 失败: $OUT"
    rm -f "$SHOTS/${NAME}_ui.xml"
    exit 1
fi
python - "$SHOTS/${NAME}_ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
for node in tree.iter('node'):
    t = node.get('text', '')
    d = node.get('content-desc', '')
    cls = node.get('class', '').split('.')[-1]
    clickable = node.get('clickable', 'false') == 'true'
    if t or d:
        print(f"[{cls}{'|C' if clickable else ''}] bounds={node.get('bounds')} text='{t}' desc='{d}'")
EOF
echo "--- $NAME done ---"
