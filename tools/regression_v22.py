# -*- coding: utf-8 -*-
"""v2.2.0 SOS 功能回归：长按菜单→编辑页紧急开关→保存→徽标→SOS条→呼叫+短信"""
import os
import subprocess, os, re, time, xml.etree.ElementTree as ET

os.environ['MSYS_NO_PATHCONV'] = '1'
ADB = os.environ.get("ADB", "adb")
DIR = "shots/"
SERIAL = "emulator-5554"

def sh(*a):
    return subprocess.run([ADB, "-s", SERIAL, "shell"] + list(a), capture_output=True, text=True)

def dump(f):
    sh("rm", "-f", "/sdcard/ui.xml")
    r = sh("uiautomator", "dump", "/sdcard/ui.xml")
    p = subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/ui.xml", DIR + f],
                       capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("dump failed: " + r.stdout.decode(errors="ignore"))
    return ET.parse(DIR + f).getroot()

def tap(x, y):
    sh("input", "tap", str(x), str(y))

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2

def texts(root):
    return [(n.get('text') or '') for n in root.iter('node') if n.get('text')]

def find(root, text, clickable=None):
    for n in root.iter('node'):
        if n.get('text') == text:
            if clickable is None or n.get('clickable') == clickable:
                return center(n.get('bounds'))
    return None

print("== 1. 长按 Son 卡 ==")
root = dump("_r_a.xml")
pos = find(root, "Son")
assert pos, "Son tile not found"
sh("input", "swipe", str(pos[0]), str(pos[1]), str(pos[0]), str(pos[1]), "1000")
time.sleep(2.5)

root = dump("_r_b.xml")
menu = find(root, "✏️ 编辑")
assert menu, "编辑菜单未出现: " + str(texts(root)[:6])
tap(*menu)
time.sleep(2.2)

print("== 2. 编辑页：打开紧急开关并保存 ==")
sw = None
for attempt in range(3):
    root = dump("_r_c.xml")
    for n in root.iter('node'):
        if 'SwitchCompat' in n.get('class', ''):
            sw = center(n.get('bounds'))
            break
    if sw is not None:
        break
    sh("input", "swipe", "540", "1900", "540", "900", "400")
    time.sleep(1.2)
root = dump("_r_s.xml")
save = find(root, "保存")
assert sw is not None, "紧急开关未找到（需滚动？）: " + str(texts(root)[:8])
tap(*sw)
time.sleep(0.8)
root = dump("_r_d.xml")
save = find(root, "保存")
assert save, "保存按钮未找到"
tap(*save)
time.sleep(2.2)

print("== 3. 验证桌面 SOS 徽标 ==")
root = dump("_r_e.xml")
has_sos = any(n.get('text') == 'SOS' for n in root.iter('node'))
has_son = any(n.get('text') == 'Son' for n in root.iter('node'))
print("SOS徽标:", has_sos, "| Son卡:", has_son)
assert has_sos and has_son, "SOS 徽标或卡片缺失"

print("== 4. 按住 SOS 条 3.5 秒触发 ==")
root = dump("_r_f.xml")
bar = find(root, "🆘 紧急求助 · 按住3秒", clickable="true")
assert bar, "SOS条未找到: " + str(texts(root)[:6])
sh("input", "swipe", str(bar[0]), str(bar[1]), str(bar[0]), str(bar[1]), "3500")
time.sleep(4)

print("== 5. 验证真实呼叫与求助短信 ==")
act = sh("dumpsys", "activity", "activities").stdout
print("InCall:", "InCallActivity" in act)
sms = sh("content", "query", "--uri", "content://sms/sent",
         "--projection", "address:body").stdout
print("已发短信:", sms.strip()[:160])

r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                   capture_output=True)
open(DIR + "T5_sos_triggered.png", "wb").write(r.stdout)
print("== 回归完成 ==")
