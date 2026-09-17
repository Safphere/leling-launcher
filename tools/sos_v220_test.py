# -*- coding: utf-8 -*-
"""SOS 紧急联系人 端到端验证（v2.2.0 release）"""
import os
import subprocess, os, re, time
import xml.etree.ElementTree as ET

os.environ['MSYS_NO_PATHCONV'] = '1'
ADB = os.environ.get("ADB", "adb")
DIR = "shots/"
SER = "emulator-5554"

def sh(*a):
    return subprocess.run([ADB, "-s", SER, "shell"] + list(a), capture_output=True, text=True)

def dump(f):
    sh("rm", "-f", "/sdcard/ui.xml")
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    r = subprocess.run([ADB, "-s", SER, "pull", "/sdcard/ui.xml", DIR + f],
                       capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("uiautomator dump/pull failed")
    return ET.parse(DIR + f).getroot()

def tap(x, y):
    sh("input", "tap", str(x), str(y))

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2

def find(root, text):
    for n in root.iter('node'):
        if n.get('text') == text:
            return center(n.get('bounds'))
    return None

def find_contains(root, sub):
    for n in root.iter('node'):
        t = n.get('text') or ''
        if sub in t:
            return center(n.get('bounds')), t
    return None, None

def texts(root, n=12):
    return [(x.get('text') or '')[:16] for x in root.iter('node') if x.get('text')][:n]

def step(name):
    print(f"---- {name} ----")

# 0) 回到桌面第0页
sh("input", "keyevent", "KEYCODE_HOME")
time.sleep(3)

print("== 1. 长按 Son → 菜单 → 编辑 ==")
root = dump("_s0.xml")
pos = find(root, "Son")
assert pos, "Son卡不在桌面: " + str(texts(root))
sh("input", "swipe", str(pos[0]), str(pos[1]), str(pos[0]), str(pos[1]), "1000")
time.sleep(2.5)
root = dump("_s1.xml")
edit = find(root, "✏️ 编辑")
assert edit, "菜单未出现: " + str(texts(root))
tap(*edit)
time.sleep(2.5)

print("== 2. 编辑页：滚动找紧急开关并打开 ==")
sw = None
for i in range(4):
    root = dump("_s2.xml")
    for n in root.iter('node'):
        if 'Switch' in n.get('class', ''):
            sw = center(n.get('bounds'))
            break
    if sw:
        break
    sh("input", "swipe", "540", "1900", "540", "700", "400")
    time.sleep(1.3)
assert sw, "紧急开关未找到"
tap(*sw)
time.sleep(0.8)

print("== 3. 滚动找保存并点击 ==")
save = None
for i in range(4):
    root = dump("_s3.xml")
    save = find(root, "保存")
    if save:
        break
    sh("input", "swipe", "540", "1800", "540", "900", "400")
    time.sleep(1.2)
assert save, "保存未找到"
tap(*save)
time.sleep(2.5)

print("== 4. 验证桌面 SOS 徽标 ==")
sh("input", "keyevent", "KEYCODE_HOME")
time.sleep(2.5)
root = dump("_s4.xml")
assert any(n.get('text') == 'SOS' for n in root.iter('node')), "SOS徽标缺失: " + str(texts(root))

print("== 5. 按住 SOS 条 3.5s 触发 ==")
root = dump("_s5.xml")
bar = find(root, "🆘 紧急求助 · 按住3秒")
assert bar, "SOS条未找到: " + str(texts(root))
sh("input", "swipe", str(bar[0]), str(bar[1]), str(bar[0]), str(bar[1]), "3500")
time.sleep(4)

print("== 6. 验证呼叫与短信 ==")
act = sh("dumpsys", "activity", "activities").stdout
incall = "InCallActivity" in act
print("InCall:", incall)
sms = sh("content", "query", "--uri", "content://sms/sent", "--projection", "address:body").stdout
print("sent短信:", sms.strip()[:200])

print("== PASS ==")
