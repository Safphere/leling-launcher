# -*- coding: utf-8 -*-
"""v3.0.0 全功能回归 D-H 段"""
import os
import subprocess, os, re, time
import xml.etree.ElementTree as ET

os.environ['MSYS_NO_PATHCONV'] = '1'
ADB = os.environ.get("ADB", "adb")
DIR = "shots/"
P, F = [], []

def sh(*a):
    return subprocess.run([ADB, "-s", "emulator-554".replace("554","5554"), "shell"] + list(a),
                          capture_output=True, text=True)

def dump(f, retry=3):
    for _ in range(retry + 1):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        r = subprocess.run([ADB, "-s", "emulator-5554", "pull", "/sdcard/ui.xml", DIR + f],
                           capture_output=True)
        if r.returncode == 0:
            try:
                return ET.parse(DIR + f).getroot()
            except ET.ParseError:
                pass
        time.sleep(2)
    raise RuntimeError("dump fail " + f)

def tap(x, y): sh("input", "tap", str(x), str(y))
def c(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2
def tx(r): return [(n.get('text') or '')[:22] for n in r.iter('node') if n.get('text')]
def has(r, k): return any(k in t for t in tx(r))
def home(): sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2.3)
def rec(n, ok, d=""):
    (P if ok else F).append(n)
    print(("✅" if ok else "❌"), n, d)

home()

print("===== D. 快捷栏→收件箱 =====")
r = dump("_d0.xml")
mb = None
for n in r.iter('node'):
    if n.get('text') == '信息' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        mb = c(n.get('bounds'))
if mb:
    tap(*mb); time.sleep(3)
    act = sh("dumpsys", "activity", "activities").stdout
    rec("D1 信息→短信应用", "messaging" in act.lower() or "Messages" in act)
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1); home()
else:
    rec("D1 信息→短信应用", False, "快捷栏无信息")

print("===== E. SOS =====")
r = dump("_e0.xml")
bar = None
for n in r.iter('node'):
    if "紧急求助" in (n.get('text') or ''):
        bar = c(n.get('bounds')); break
sh("logcat", "-c")
if bar:
    sh("input", "swipe", str(bar[0]), str(bar[1]), str(bar[0]), str(bar[1]), "3500")
time.sleep(4)
act = sh("dumpsys", "activity", "activities").stdout
soslog = sh("logcat", "-d", "-s", "SosHelper:*").stdout
rec("E1 SOS触发呼叫", "InCallActivity" in act)
rec("E2 SOS求助短信已发", "sos sms sent to" in soslog)
sh("input", "keyevent", "KEYCODE_ENDCALL"); time.sleep(1.5)
sh("input", "keyevent", "KEYCODE_ENDCALL"); time.sleep(1.5)
home(); time.sleep(2)

print("===== F. 流量 =====")
sh("emu", "sms", "send", "10086", "剩余流量为35MB，请注意使用")
d1 = None
for _ in range(8):
    time.sleep(4)
    d1 = sh("settings", "get", "global", "mobile_data").stdout.strip()
    if d1 == "0": break
rec("F1 低流量自动关数据", d1 == "0", f"data={d1}")
sh("emu", "sms", "send", "10086", "剩余流量为2.5GB，请放心使用")
d2 = None
for _ in range(8):
    time.sleep(4)
    d2 = sh("settings", "get", "global", "mobile_data").stdout.strip()
    if d2 == "1": break
rec("F2 充足自动恢复", d2 == "1", f"data={d2}")
home()

print("===== G. AI =====")
sh("input", "swipe", "880", "1200", "200", "1200", "400"); time.sleep(2.5)
r = dump("_g0.xml")
ai = None
for n in r.iter('node'):
    if "语音问答" in (n.get('text') or ''):
        ai = c(n.get('bounds')); break
if ai:
    tap(*ai); time.sleep(2.5)
    edit = None
    for n in dump("_g1.xml").iter('node'):
        if n.get('class','').endswith('EditText'):
            edit = c(n.get('bounds')); break
    if edit:
        tap(*edit); time.sleep(1.2)
        sh("input", "text", "hello"); time.sleep(0.5)
        sh("input", "keyevent", "111"); time.sleep(1)
        for m in dump("_g2.xml").iter('node'):
            if m.get('text') == '发送':
                tap(*c(m.get('bounds'))); break
    time.sleep(22)
    r = dump("_g3.xml")
    rec("G1 真实模型回复", any(len(t) > 15 for t in tx(r)),
        str([t[:24] for t in tx(r) if len(t) > 15][:2]))
    # 离线
    edit = None
    for n in dump("_g4.xml").iter('node'):
        if n.get('class','').endswith('EditText'):
            edit = c(n.get('bounds')); break
    if edit:
        tap(*edit); time.sleep(1.2)
        sh("input", "text", "what%sis%sthe%stime"); time.sleep(0.5)
        sh("input", "keyevent", "111"); time.sleep(1)
        for m in dump("_g5.xml").iter('node'):
            if m.get('text') == '发送':
                tap(*c(m.get('bounds'))); break
    time.sleep(3)
    r = dump("_g6.xml")
    rec("G2 离线答时间", has(r, "现在是"))
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
else:
    rec("G1 AI入口可见", False, "生活页无AI入口")
home()

print("===== H. 日历 =====")
sh("input", "swipe", "880", "1200", "200", "1200", "400"); time.sleep(2.5)
r = dump("_h0.xml")
d15 = None
for n in r.iter('node'):
    if n.get('text') == '15':
        d15 = c(n.get('bounds')); break
if d15:
    tap(*d15); time.sleep(2)
    r = dump("_h1.xml")
    rec("H1 日历日期详情(农历)", has(r, "2026年") and has(r, "星期"))
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
else:
    rec("H1 日历日期详情", False, "未找到15号")
home()

print(f"\nD-H 通过 {len(P)}/{len(P)+len(F)}")
