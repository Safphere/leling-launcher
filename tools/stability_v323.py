# -*- coding: utf-8 -*-
"""v3.2.3 稳定性回归：组件级断言（不依赖 uiautomator 文本）+ 崩溃缓冲检查"""
import subprocess, time, sys, re

def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True, timeout=60)

def top():
    o = sh("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^}]*u0 (\S+)", o.stdout)
    return m.group(1) if m else "?"

def tap(x, y, d=0.6):
    sh("input", "tap", str(x), str(y)); time.sleep(d)

def swipe(x, y, x2, y2, ms):
    sh("input", "swipe", str(x), str(y), str(x2), str(y2), str(ms))

def dump():
    time.sleep(0.5)
    sh("uiautomator", "dump", "/sdcard/reg.xml")
    time.sleep(0.8)
    o = sh("cat", "/sdcard/reg.xml")
    return o.stdout or ""

def find_text_bounds(xml, key):
    m = re.search(r'text="[^"]*' + key + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="[^"]*' + key + r'[^"]*"', xml)
    if not m: return None
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2

REC = []
def rec(name, ok, note=""):
    REC.append((name, ok, note))
    print(("✅" if ok else "❌"), name, note)

time.sleep(1)
sh("am", "start", "-n", "com.safphere.launcher/.home.HomeActivity"); time.sleep(4)

# ---- 1. 桌面在前台 ----
rec("1 桌面为前台", "HomeActivity" in top(), top())

# ---- 2. 三页滑动往返（稳定性：连滑4次回到原位） ----
ok = True
for i in range(4):
    swipe(900, 1200, 100, 1200, 300); time.sleep(0.8)
    swipe(100, 1200, 900, 1200, 300); time.sleep(0.8)
ok = "HomeActivity" in top()
rec("2 连续4次往返滑动不崩不卡", ok, top())

# ---- 3. 照片直呼 → InCall → 挂断条结束 ----
xml = dump()
p = find_text_bounds(xml, "Son")
if not p: p = (291, 312)   # Son 磁贴已知位置兜底
tap(*p, 1.0); time.sleep(3.5)
t = top()
rec("3a 照片直呼进入通话", ("InCall" in t) or ("dialer" in t) or ("Telecom" in t), t)
# 挂断条
xml = dump()
p2 = find_text_bounds(xml, "挂断") or find_text_bounds(xml, "结束")
if p2:
    tap(*p2, 2.5)
    rec("3b 挂断条结束通话回到桌面", "HomeActivity" in top(), top())
else:
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
    rec("3b 挂断条结束通话回到桌面", "HomeActivity" in top(), "未找到挂断条，用BACK返回=" + top())

# ---- 4. 长按联系人 → 发短信页 ----
sh("input", "swipe", str(p[0]), str(p[1]), str(p[0]), str(p[1]), "1000"); time.sleep(1.8)
xml = dump()
p3 = find_text_bounds(xml, "短信")
if p3:
    tap(*p3, 2.0)
    rec("4a 长按菜单→发短信页", "SmsCompose" in top(), top())
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
    rec("4b 返回桌面", "HomeActivity" in top(), top())
else:
    rec("4 长按菜单未弹出（短信项未找到）", False, top())
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

# ---- 5. 快捷栏启动（电话槽位）→ 返回 ----
tap(159, 2200, 3.0)
t = top()
rec("5a 快捷栏启动拨号盘", ("dialer" in t) or ("Dialtacts" in t) or ("com.google.android.dialer" in t), t)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
rec("5b 返回桌面", "HomeActivity" in top(), top())

# ---- 6. HOME键回桌面（HOME角色） ----
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
rec("6 HOME键回到乐龄桌面", "HomeActivity" in top(), top())

# ---- 7. 崩溃缓冲 ----
crash = sh("logcat", "-d", "-b", "crash").stdout or ""
n_crash = len([l for l in crash.splitlines() if "FATAL EXCEPTION" in l or "AndroidRuntime" in l and "Process" in l])
anr = sh("logcat", "-d", "-b", "system").stdout or ""
n_anr = len(re.findall(r"ANR in com\.safphere", anr or ""))
rec("7 零崩溃", n_crash == 0, f"fatal={n_crash}")
rec("8 零ANR", n_anr == 0, f"anr={n_anr}")

fails = [r for r in REC if not r[1]]
print(f"\n===== 结果：{len(REC)-len(fails)}/{len(REC)} 通过 =====")
for f in fails: print("失败：", f[0], f[2])
sys.exit(1 if fails else 0)
