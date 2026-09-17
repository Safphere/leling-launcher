# -*- coding: utf-8 -*-
"""v2.6.0 最终全量回归（正式包）"""
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

def wake():
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.6)
    sh("wm", "dismiss-keyguard")
    time.sleep(0.8)

def dump(f, retry=4):
    for i in range(retry + 1):
        sh("rm", "-f", "/sdcard/ui.xml")
        out = sh("uiautomator", "dump", "/sdcard/ui.xml").stdout
        if "null root" in out or "idle" in out:
            wake()   # 息屏/锁屏导致无节点，唤醒后重试
        r = subprocess.run([ADB, "-s", SER, "pull", "/sdcard/ui.xml", DIR + f],
                           capture_output=True)
        if r.returncode == 0:
            try:
                return ET.parse(DIR + f).getroot()
            except ET.ParseError:
                pass
        time.sleep(2.2)
    raise RuntimeError("dump failed " + f)

def tap(x, y):
    sh("input", "tap", str(x), str(y))

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2

def texts(root):
    return [(n.get('text') or '').strip() for n in root.iter('node') if (n.get('text') or '').strip()]

def has(root, key):
    return any(key in t for t in texts(root))

def find(root, text):
    for n in root.iter('node'):
        if n.get('text') == text:
            return center(n.get('bounds'))
    return None

def rec(name, ok, detail=""):
    (PASS if ok else FAIL).append((name, detail))
    print(("✅" if ok else "❌"), name, detail)

def home():
    sh("input", "keyevent", "KEYCODE_HOME")
    time.sleep(2.5)

def cur_act():
    return sh("dumpsys", "activity", "activities").stdout

def swipe_left():
    sh("input", "swipe", "880", "1200", "200", "1200", "400")
    time.sleep(2.5)

def swipe_right():
    sh("input", "swipe", "200", "1200", "880", "1200", "400")
    time.sleep(2.5)

wake()
sh("svc", "power", "stayon", "true")   # 防息屏
sh("logcat", "-c")
home()

def goto_call():
    """回到呼叫页（页面状态可能停留在任意应用子页；dump异常时重试）"""
    for _ in range(6):
        try:
            r = dump("_nav.xml")
        except RuntimeError:
            time.sleep(2)
            continue
        if has(r, "Son") and has(r, "添加联系人"):
            return r
        swipe_right()
    return dump("_nav.xml")

print("===== A. 三页结构与导航 =====")
r = goto_call()
rec("A1 呼叫页(SOS条+Son+添加)", has(r, "🆘 紧急求助") and has(r, "Son") and has(r, "添加联系人"))
swipe_left()
r = dump("fb.xml")
rec("A2 生活页(时钟+农历+天气+日历+AI入口)",
    has(r, "农历") and has(r, "点我听天气") and has(r, "语音问答") and has(r, "2026年"))
swipe_left()
r = dump("fc.xml")
apps1 = [t for t in texts(r) if t not in ("常用应用",)]
rec("A3 应用页1有内容", len(apps1) >= 6, str(apps1[:4]))
home()

print("===== B. 联系人直呼+挂断 =====")
r = goto_call()
p = find(r, "Son")
tap(p[0], p[1] - 50)
time.sleep(3.5)
rec("B1 照片直呼(InCall)", "InCallActivity" in cur_act())
home(); time.sleep(3.5)
r = dump("fbb.xml")
chip = None
for n in r.iter('node'):
    if "通话中" in (n.get('text') or ''):
        chip = center(n.get('bounds')); break
if chip:
    tap(*chip); time.sleep(3)
    rec("B2 挂断条结束通话", "InCallActivity" not in cur_act())
else:
    rec("B2 挂断条出现", False, "未找到(可能对方已挂断)")

print("===== C. 长按菜单→发短信 =====")
r = goto_call()
p = find(r, "Son")
sh("input", "swipe", str(p[0]), str(p[1]), str(p[0]), str(p[1]), "1000")
time.sleep(2.5)
r = dump("fcb.xml")
m = find(r, "✉️ 发短信")
assert m, "菜单未出现"
tap(*m); time.sleep(2.2)
r = dump("fcc.xml")
ph = find(r, "到家了")
if not ph:
    ph = find(r, "我很好，勿念")
assert ph, "短语未找到"
tap(*ph); time.sleep(0.8)
r = dump("fcd.xml")
sd = None
for n in r.iter('node'):
    if "发送短信" in (n.get('text') or ''):
        sd = center(n.get('bounds')); break
tap(*sd); time.sleep(3)
sms = sh("content", "query", "--uri", "content://sms/sent", "--projection", "body").stdout
rec("C1 发短信落库", ("到家了" in sms) or ("勿念" in sms))
home()

print("===== D. 快捷栏 Messages→收件箱 =====")
r = goto_call()
mb = None
for n in r.iter('node'):
    if n.get('text') == 'Messages' and int(re.findall(r'\d+', n.get('bounds'))[1]) > 2000:
        mb = center(n.get('bounds')); break
if mb is None:
    r = dump("fdb.xml")
    mb = find(r, "Messages")
tap(*mb); time.sleep(3)
rec("D1 Messages→短信应用", "messaging" in cur_act().lower())
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1); home()

print("===== E. SOS 紧急求助 =====")
r = goto_call()
bar = None
for n in r.iter('node'):
    if "紧急求助" in (n.get('text') or ''):
        bar = center(n.get('bounds')); break
sh("svc", "power", "stayon", "true")   # 防息屏
sh("logcat", "-c")
sh("input", "swipe", str(bar[0]), str(bar[1]), str(bar[0]), str(bar[1]), "3500")
time.sleep(4)
incall = "InCallActivity" in cur_act()
soslog = sh("logcat", "-d", "-s", "SosHelper:*").stdout
rec("E1 SOS触发呼叫", incall)
rec("E2 SOS求助短信已发", "sos sms sent to" in soslog, soslog.strip().splitlines()[-1][:60] if soslog else "")
sh("input", "keyevent", "KEYCODE_ENDCALL"); time.sleep(1.5)
sh("input", "keyevent", "KEYCODE_ENDCALL"); time.sleep(1.5)
home(); time.sleep(2)

print("===== F. 流量真实短信链路 =====")
sh("emu", "sms", "send", "10086", "您本月剩余流量为38MB，请注意使用")
d1 = None
for _ in range(6):
    time.sleep(4)
    d1 = sh("settings", "get", "global", "mobile_data").stdout.strip()
    if d1 == "0":
        break
sh("emu", "sms", "send", "10086", "您本月剩余流量为2.6GB，请放心使用")
d2 = None
for _ in range(6):
    time.sleep(4)
    d2 = sh("settings", "get", "global", "mobile_data").stdout.strip()
    if d2 == "1":
        break
rec("F1 低流量自动关数据", d1 == "0", f"mobile_data={d1}")
rec("F2 充足自动恢复", d2 == "1", f"mobile_data={d2}")

print("===== G. AI 真实问答（Anthropic中转） =====")
goto_call(); swipe_left()
r = dump("fga.xml")
ai = None
for n in r.iter('node'):
    if "语音问答" in (n.get('text') or ''):
        ai = center(n.get('bounds')); break
tap(*ai); time.sleep(2.5)
r = dump("fgb.xml")
for n in r.iter('node'):
    if n.get('class', '').endswith('EditText'):
        cx, cy = center(n.get('bounds'))
        tap(cx, cy); time.sleep(1.2)
        sh("input", "text", "hello")
        time.sleep(0.5)
        sh("input", "keyevent", "111"); time.sleep(1)
        r2 = dump("fgc.xml")
        send = None
        for m in r2.iter('node'):
            if m.get('text') == '发送':
                send = center(m.get('bounds')); break
        tap(*send)
        break
time.sleep(22)
r = dump("fgd.xml")
ts = texts(r)
rec("G1 真实模型回复", any(("小乐" in t and len(t) > 15) or ("您" in t and len(t) > 15) for t in ts),
    str([t[:30] for t in ts if len(t) > 15][:2]))
# 离线技能
r = dump("fge.xml")
for n in r.iter('node'):
    if n.get('class', '').endswith('EditText'):
        cx, cy = center(n.get('bounds'))
        tap(cx, cy); time.sleep(1.2)
        sh("input", "text", "what%sis%sthe%stime")
        time.sleep(0.5)
        sh("input", "keyevent", "111"); time.sleep(1)
        r2 = dump("fgf.xml")
        for m in r2.iter('node'):
            if m.get('text') == '发送':
                tap(*center(m.get('bounds'))); break
        break
time.sleep(3)
r = dump("fgg.xml")
rec("G2 离线技能答时间", has(r, "现在是"))
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5); home()

print("===== H. 日历点日期 =====")
goto_call(); swipe_left()
r = dump("fha.xml")
d15 = find(r, "15")
assert d15, "15号未找到"
tap(*d15); time.sleep(2)
r = dump("fhb.xml")
rec("H1 日期详情(农历)", has(r, "2026年") and has(r, "星期"))
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
swipe_right(); home()

print("===== I. 应用页多页内容 =====")
goto_call(); swipe_left(); swipe_left()
r = dump("fia.xml")
p1 = set(t for t in texts(r) if t not in ("常用应用",))
swipe_left()
r = dump("fib.xml")
p2 = set(t for t in texts(r) if t not in ("常用应用",))
rec("I1 应用页2内容不同", bool(p2) and p1 != p2,
    f"p1样本{sorted(p1)[:3]} p2样本{sorted(p2)[:3]}")
home()

print("===== J. 拖拽换快捷栏 =====")
goto_call(); swipe_left(); swipe_left()
r = dump("fja.xml")
chrome = None
for n in r.iter('node'):
    if n.get('text') == 'Chrome':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        chrome = ((x1 + x2) // 2, (y1 + y2) // 2 - 55)
        break
if chrome:
    sh("input", "draganddrop", str(chrome[0]), str(chrome[1]), "700", "2268", "2500")
    time.sleep(3)
    r = dump("fjb.xml")
    dockt = [t for n in r.iter('node')
             if re.match(r'\[\d+,2[0-3]\d\d', n.get('bounds', '')) for t in [(n.get('text') or '')] if t]
    rec("J1 Chrome拖入快捷栏", "Chrome" in dockt, str(dockt[:4]))
else:
    rec("J1 Chrome拖入快捷栏", False, "图标未找到")
home()

print("===== K. 子女模式与权限页 =====")
r = goto_call()
dots = None
for n in r.iter('node'):
    if (n.get('resource-id') or '').endswith('/dotsRow'):
        dots = center(n.get('bounds')); break
for _ in range(5):
    tap(*dots); time.sleep(0.4)
time.sleep(2.2)
r = dump("fkb.xml")
rec("K1 5连击→PIN门", has(r, "请输入 PIN 密码"))
for k in ['1', '2', '3', '4']:
    for n in r.iter('node'):
        if n.get('text') == k and n.get('clickable') == 'true':
            tap(*center(n.get('bounds'))); time.sleep(0.4); break
for n in r.iter('node'):
    if n.get('text') == '✓':
        tap(*center(n.get('bounds'))); break
time.sleep(2.2)
r = dump("fkc.xml")
rec("K2 紧急联系人行(Son)", has(r, "紧急联系人：1位"))
# 权限自检页
perm = None
for i in range(4):
    for n in r.iter('node'):
        if n.get('text', '').startswith('权限自检'):
            perm = center(n.get('bounds')); break
    if perm: break
    sh("input", "swipe", "540", "1800", "540", "900", "400"); time.sleep(1.2)
    r = dump("fkd.xml")
assert perm, "权限行未找到"
tap(*perm); time.sleep(2.2)
r = dump("fke.xml")
rec("K3 权限自检页(已授权项)", sum(1 for t in texts(r) if t == '✓') >= 8,
    f"✓数量={sum(1 for t in texts(r) if t == '✓')}")
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
home()

print("===== L. 外部地震推送（签名权限接口） =====")
home()
sh("am", "start", "-n", "com.leling.test.alertsender/.MainActivity")
time.sleep(3.5)
act = cur_act()
if "AlertActivity" in act:
    r = dump("fla.xml")
    rec("L1 地震警报全屏页", has(r, "地震预警"))
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
else:
    sh("cmd", "statusbar", "expand-notifications")
    time.sleep(2)
    r = dump("flb.xml")
    ok = has(r, "地震预警")
    if ok:
        nt = None
        for n in r.iter('node'):
            if n.get('text') == '🌊 地震预警':
                nt = center(n.get('bounds')); break
        if nt:
            tap(*nt); time.sleep(2.5)
            r = dump("flc.xml")
            rec("L1 地震警报页(经通知)", has(r, "地震预警"))
            sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
    else:
        rec("L1 地震推送", False, "通知与页面均未见")
home()

print("===== M. 稳定性与内存 =====")
crash = sh("logcat", "-d").stdout
rec("M1 零崩溃", crash.count("FATAL EXCEPTION") == 0,
    f"count={crash.count('FATAL EXCEPTION')}")
mem = sh("dumpsys", "meminfo", "com.safphere.launcher").stdout
for line in mem.splitlines():
    if line.strip().startswith("TOTAL PSS"):
        print("   内存:", line.strip()[:60])
        break

print()
print("========== 回归总览 ==========")
for n, d in PASS:
    print("✅", n, d)
for n, d in FAIL:
    print("❌", n, d)
print(f"通过 {len(PASS)} / 共 {len(PASS) + len(FAIL)}")
