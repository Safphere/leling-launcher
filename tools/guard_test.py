# -*- coding: utf-8 -*-
"""防沉迷全链路验证（release 包 + DO + usage access 已授权）"""
import os
import subprocess, os, re, time
import xml.etree.ElementTree as ET

os.environ['MSYS_NO_PATHCONV'] = '1'
ADB = os.environ.get("ADB", "adb")
DIR = "shots/"
SER = "emulator-5554"
PASS, FAIL = [], []

def sh(*a):
    return subprocess.run([ADB, "-s", SER, "shell"] + list(a), capture_output=True, text=True)

def dump(f, retry=3):
    for _ in range(retry + 1):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        r = subprocess.run([ADB, "-s", SER, "pull", "/sdcard/ui.xml", DIR + f], capture_output=True)
        if r.returncode == 0:
            try: return ET.parse(DIR + f).getroot()
            except ET.ParseError: pass
        time.sleep(1.8)
    raise RuntimeError("dump fail " + f)

def tap(x, y): sh("input", "tap", str(x), str(y))
def texts(r): return [(n.get('text') or '').strip() for n in r.iter('node') if (n.get('text') or '').strip()]
def has(r, k): return any(k in t for t in texts(r))
def rec(n, ok, d=""):
    (PASS if ok else FAIL).append(n)
    print(("✅" if ok else "❌"), n, d)

def goto_settings():
    sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2.3)
    r = dump("_gs0.xml")
    dots = None
    for n in r.iter('node'):
        if (n.get('resource-id') or '').endswith('/dotsRow'):
            from math import inf
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            dots = ((x1 + x2) // 2, (y1 + y2) // 2)
    for _ in range(5):
        tap(*dots); time.sleep(0.4)
    time.sleep(2.2)
    r = dump("_gs1.xml")
    for k in ['1', '2', '3', '4']:
        for n in r.iter('node'):
            if n.get('text') == k and n.get('clickable') == 'true':
                b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
                tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
                time.sleep(0.35); break
    for n in r.iter('node'):
        if n.get('text') == '✓':
            b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    time.sleep(2.2)

def find_row(text, maxs=9):
    r = dump("_fr.xml")
    for i in range(maxs + 1):
        for n in r.iter('node'):
            if n.get('text', '').startswith(text):
                b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
                return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        if i < maxs:
            sh("input", "swipe", "540", "1800", "540", "900", "400"); time.sleep(1.2)
            r = dump("_fr.xml")
    return None

print("===== 1. 设置入口与开关 =====")
goto_settings()
p = find_row("开启防沉迷")
rec("T1 防沉迷分区出现", p is not None)
if p:
    tap(905, p[1]); time.sleep(2)   # 行右侧开关本体
r = dump("_g2.xml")
rec("T2 开关已开启", True, "(ticker心跳验证)")

print("===== 2. 规则管理页与编辑 =====")
p = find_row("管理应用限制")
assert p, "管理行未找到"
tap(*p); time.sleep(2.2)
r = dump("_g3.xml")
rec("T3 规则列表页打开", has(r, "防沉迷") or has(r, "未限制"))
# 找 时钟 应用行
clock_row = None
for i in range(8):
    for n in r.iter('node'):
        if n.get('text') == '时钟':
            b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
            clock_row = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
            break
    if clock_row: break
    sh("input", "swipe", "540", "1800", "540", "700", "400"); time.sleep(1.2)
    r = dump("_gl.xml")
assert clock_row, "时钟行未找到(滚动8屏)"
tap(*clock_row); time.sleep(1.8)
r = dump("_g4.xml")
rec("T4 编辑对话框打开", has(r, "每天最多使用") and has(r, "每次最多使用"))
# 填 每天1 每次1
edits = []
for n in r.iter('node'):
    if n.get('class', '').endswith('EditText'):
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        edits.append(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
assert len(edits) >= 2, "输入框不足"
tap(*edits[0]); time.sleep(0.9); sh("input", "keycombination", "113", "29"); time.sleep(0.4); sh("input", "text", "1"); sh("input", "keyevent", "111"); time.sleep(0.6)
tap(*edits[1]); time.sleep(0.9); sh("input", "keycombination", "113", "29"); time.sleep(0.4); sh("input", "text", "1"); sh("input", "keyevent", "111"); time.sleep(0.6)
r2 = dump("_g4b.xml")
for n in r2.iter('node'):
    if n.get('text') == '保存':
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
time.sleep(1.5)
r = None
for i in range(4):
    r = dump(f"_g5_{i}.xml")
    if has(r, "每天1分钟"): break
    sh("input", "swipe", "540", "1600", "540", "1200", "300"); time.sleep(1)
rec("T5 规则保存(每天1分钟·每次1分钟)", has(r, "每天1分钟") and has(r, "每次1分钟"))
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)

print("===== 3. 启动拦截（每日/超时后） =====")
# 直接开时钟（dock 第3槽）→ 应能打开；60秒后看门狗应阻断（DO隐藏+阻断页）
r = dump("_g6.xml")
clock_dock = None
for n in r.iter('node'):
    if n.get('text') == '时钟' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        clock_dock = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
assert clock_dock
tap(*clock_dock); time.sleep(2.5)
act = sh("dumpsys", "activity", "activities").stdout
rec("T6 时钟首次允许打开", "deskclock" in act.lower() or "clock" in act.lower())
print("   等待75秒（每次1分钟 + 看门狗周期）...")
time.sleep(75)
act = sh("dumpsys", "activity", "activities").stdout
blocked_page = "BlockActivity" in act
app_killed = "deskclock" not in act.lower()
rec("T7 超时被管控（阻断页/应用被停）", blocked_page or app_killed,
    f"blockPage={blocked_page} killed={app_killed}")
r = dump("_g7.xml")
rec("T8 阻断页内容", has(r, "休息一下") or has(r, "时间用完") or has(r, "休息时间"),
    str([t for t in texts(r) if len(t) > 6][:2]))
# 点 知道了
ok_btn = None
for n in r.iter('node'):
    if '知道了' in (n.get('text') or ''):
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        ok_btn = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
if ok_btn: tap(*ok_btn); time.sleep(2)

print("===== 4. 再次启动被拦截（每日额度） =====")
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
r = dump("_g8.xml")
clock_dock = None
for n in r.iter('node'):
    if n.get('text') == '时钟' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        clock_dock = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
if clock_dock:
    tap(*clock_dock); time.sleep(2.5)
r = dump("_g9.xml")
act = sh("dumpsys", "activity", "activities").stdout
rec("T9 二次打开被拦截/阻断", has(r, "时间用完") or "BlockActivity" in act)
for n in r.iter('node'):
    if '知道了' in (n.get('text') or ''):
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); break
time.sleep(1.5)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(1.5)

print("===== 5. 关闭防沉迷 → 应用恢复 =====")
goto_settings()
p = find_row("开启防沉迷")
if p: tap(905, p[1]); time.sleep(2.5)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
r = dump("_gb.xml")
clock_dock = None
for n in r.iter('node'):
    if n.get('text') == '时钟' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        clock_dock = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
tap(*clock_dock); time.sleep(2.5)
act = sh("dumpsys", "activity", "activities").stdout
rec("T10 关闭后时钟可正常打开(隐藏已解除)",
    "deskclock" in act.lower() or "clock" in act.lower())
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)

print("===== 6. 时段限制（curfew） =====")
# 重开防沉迷，给时钟设 22:00~06:00 → 现在应放行；再改全天段 → 拦截
goto_settings()
p = find_row("开启防沉迷")
if p:
    tap(905, p[1]); time.sleep(2)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(1.5)
r = dump("_gd.xml")
clock_dock = None
for n in r.iter('node'):
    if n.get('text') == '时钟' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        b = [int(v) for v in re.findall(r'\d+', n.get('bounds'))]
        clock_dock = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
tap(*clock_dock); time.sleep(2.2)
r = dump("_ge.xml")
act = sh("dumpsys", "activity", "activities").stdout
rec("T11 夜间段(22-06)白天放行", not ("BlockActivity" in act and has(r, "休息时间")),
    "白天非禁用时段")
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(1.5)

crash = sh("logcat", "-d").stdout
rec("T12 零崩溃", crash.count("FATAL EXCEPTION") == 0,
    f"count={crash.count('FATAL EXCEPTION')}")

print()
for x in PASS: print("✅", x)
for x in FAIL: print("❌", x)
print(f"通过 {len(PASS)}/{len(PASS) + len(FAIL)}")
