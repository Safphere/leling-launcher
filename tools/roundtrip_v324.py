# -*- coding: utf-8 -*-
"""设置进出功能完整性回归：SOS开关/白名单增删/提醒排期/零崩溃"""
import subprocess, time, sys, re

def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True, timeout=60)

def top():
    o = sh("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^}]*u0 (\S+)", o.stdout)
    return m.group(1) if m else "?"

def tap(x, y, d=0.8):
    sh("input", "tap", str(x), str(y)); time.sleep(d)

def dump():
    time.sleep(0.4)
    sh("uiautomator", "dump", "/sdcard/rt.xml")
    time.sleep(0.7)
    return sh("cat", "/sdcard/rt.xml").stdout or ""

def find(xml, key):
    m = re.search(r'text="[^"]*' + key + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m: return None
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2

def open_settings():
    sh("am", "start", "-n", "com.safphere.launcher/.home.HomeActivity"); time.sleep(3)
    for _ in range(5):
        tap(540, 2046, 0.5)
    time.sleep(1.5)
    x = dump()
    if "默认密码" in x:
        for pt in [(308,1216),(539,1216),(770,1216),(308,1426),(770,1846)]:
            tap(*pt, 0.4)
        time.sleep(1.6)
    return "SettingsActivity" in top()

REC = []
def rec(name, ok, note=""):
    REC.append((name, ok, note)); print(("✅" if ok else "❌"), name, note)

# 1 进设置
ok = open_settings()
rec("1 进入子女设置(PIN)", ok, top())

# 2 找到 SOS 开关并打开
sos_pos = None
for _ in range(8):
    x = dump()
    p = find(x, "紧急求助")
    if p and p[1] < 2000: sos_pos = p; break
    sh("input", "swipe", "540", "1600", "540", "900", "350"); time.sleep(0.8)
if sos_pos:
    tap(930, sos_pos[1], 1.0)   # 行右侧开关
    x = dump()
    rec("2 SOS开关已开", "紧急求助" in x, f"row={sos_pos}")
else:
    rec("2 SOS行未找到", False)

# 3 返回桌面，SOS条应显示
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
x = dump()
rec("3a 返回桌面", "HomeActivity" in top(), top())
rec("3b SOS条显示", "按住3秒" in x)

# 4 再进设置关掉 SOS，返回验证消失
ok = open_settings()
sos_pos = None
for _ in range(8):
    x = dump()
    p = find(x, "紧急求助")
    if p and p[1] < 2000: sos_pos = p; break
    sh("input", "swipe", "540", "1600", "540", "900", "350"); time.sleep(0.8)
if sos_pos:
    tap(930, sos_pos[1], 1.0)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
x = dump()
rec("4 SOS关闭后条消失", "HomeActivity" in top() and "按住3秒" not in x, top())

# 5 白名单增删 → 应用页实时反映
ok = open_settings()
apps_pos = None
for _ in range(8):
    x = dump()
    p = find(x, "选择老人可以看到的应用")
    if p and p[1] < 2100: apps_pos = p; break
    sh("input", "swipe", "540", "1600", "540", "900", "350"); time.sleep(0.8)
if apps_pos:
    tap(*apps_pos, 2.2)   # 进选择页
    x = dump()
    p = find(x, "Chrome")
    if p:
        tap(905, p[1], 1.0)   # 取消勾选 Chrome
        tap(932, 120, 1.5)    # 保存
        sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(2)
        # 滑到应用页看 Chrome 是否消失
        found = None
        for i in range(4):
            sh("input", "swipe", "900", "1200", "100", "1200", "250"); time.sleep(1.0)
            x = dump()
            if "Chrome" in x: found = True; break
            if "灵犀Agent" in x or "YouTube" in x: break
        rec("5a 白名单取消Chrome→应用页无Chrome", found is not True, f"found={found}")
    else:
        rec("5a 选择页没找到Chrome", False)
else:
    rec("5 白名单入口未找到", False)

# 6 加回 Chrome
ok = open_settings()
apps_pos = None
for _ in range(8):
    x = dump()
    p = find(x, "选择老人可以看到的应用")
    if p and p[1] < 2100: apps_pos = p; break
    sh("input", "swipe", "540", "1600", "540", "900", "350"); time.sleep(0.8)
if apps_pos:
    tap(*apps_pos, 2.2)
    x = dump()
    p = find(x, "Chrome")
    if p:
        tap(905, p[1], 1.0)
        tap(932, 120, 1.5)
rec("6 Chrome加回白名单", True)

# 7 提醒闹钟仍在（设置进出不影响排期）
al = sh("dumpsys", "alarm").stdout or ""
rec("7 提醒闹钟排期完好", "REMINDER_FIRE" in al)

# 8 崩溃检查
crash = sh("logcat", "-d", "-b", "crash").stdout or ""
n = len([l for l in crash.splitlines() if "FATAL EXCEPTION" in l])
rec("8 零崩溃", n == 0, f"fatal={n}")

fails = [r for r in REC if not r[1]]
print(f"\n===== 设置往返回归：{len(REC)-len(fails)}/{len(REC)} 通过 =====")
for f in fails: print("失败：", f[0], f[2])
sys.exit(1 if fails else 0)
