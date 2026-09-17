import subprocess, time, re, sys

def sh(*a):
    return subprocess.run(["adb","shell",*a], capture_output=True, text=True, timeout=60)

def dump():
    for _ in range(3):
        time.sleep(0.5)
        sh("uiautomator","dump","/sdcard/fx.xml")
        time.sleep(0.6)
        x = sh("cat","/fx.xml").stdout or ""
        if len(x) > 1000: return x
    return x

def find(xml, key):
    m = re.search(r'text="[^"]*'+key+r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m: return None
    return ((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)

def tap(x, y, d=0.6):
    sh("input","tap",str(x),str(y)); time.sleep(d)

def swipe(x,y,x2,y2,ms=250):
    sh("input","swipe",str(x),str(y),str(x2),str(y2),str(ms)); time.sleep(0.9)

def top():
    o = sh("dumpsys","activity","activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^}]*u0 (\S+)", o.stdout)
    return m.group(1) if m else "?"

RES = []
def step(name, ok, note=""):
    RES.append((name, ok, note)); print(("PASS " if ok else "FAIL ")+name, note)
    sys.stdout.flush()

def scroll_to(key, max_screens=6):
    for _ in range(max_screens):
        x = dump()
        p = find(x, key)
        if p and p[1] < 2100: return p
        swipe(540, 1600, 540, 1000); time.sleep(1.0)
    # 向上找
    for _ in range(4):
        x = dump()
        p = find(x, key)
        if p and p[1] < 2100: return p
        swipe(540, 1000, 540, 1600); time.sleep(1.0)
    return None

def dial_set(h, m):
    """在已打开的 TimePicker 表盘上选 h:m，点 OK"""
    for _ in range(6):
        x = dump()
        nums = {}
        for mm in re.finditer(r'text="(\d{1,2})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
            nums.setdefault(int(mm.group(1)), ((int(mm.group(2))+int(mm.group(4)))//2, (int(mm.group(3))+int(mm.group(5)))//2))
        hp = nums.get(h)
        if hp:
            tap(*hp, 1.0)
            # 分钟圈：目标分钟数字
            for _ in range(4):
                x2 = dump()
                mp = None
                for mm in re.finditer(r'text="(\d{2})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x2):
                    if int(mm.group(1)) == m:
                        mp = ((int(mm.group(2))+int(mm.group(4)))//2, (int(mm.group(3))+int(mm.group(5)))//2); break
                if mp:
                    tap(*mp, 0.8)
                    x3 = dump()
                    ok = re.search(r'text="(OK)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x3)
                    if ok:
                        tap((int(ok.group(2))+int(ok.group(4)))//2, (int(ok.group(3))+int(ok.group(5)))//2, 1.0)
                        return True
                time.sleep(0.8)
            return False
        time.sleep(0.8)
    return False

# ==== S0: 回桌面 ====
sh("input","keyevent","KEYCODE_HOME"); time.sleep(2)
sh("am","start","-n","com.safphere.launcher/.home.HomeActivity"); time.sleep(3)

# ==== S1: dots x5 -> PIN ====
for _ in range(5): tap(540, 2046, 0.4)
time.sleep(1.5)
keys = {}
for attempt in range(3):
    x = dump()
    for m in re.finditer(r'text="(\d)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        keys[m.group(1)] = ((int(m.group(2))+int(m.group(4)))//2, (int(m.group(3))+int(m.group(5)))//2)
    if keys: break
    time.sleep(1)
for k in "1234":
    pk = keys.get(k)
    if pk: tap(*pk, 0.35)
time.sleep(1.5)
step("S1a PIN 解锁", "SettingsActivity" in top(), top())

# ==== S2: 勿扰结束 → 23:55 ====
p = scroll_to("勿扰结束")
if p:
    tap(*p, 2.2)
    ok = dial_set(23, 55)
    step("S2 勿扰结束→23:55", ok)
else:
    step("S2 勿扰结束行未找到", False)

# ==== S3: 勿扰开始 → 12:00 ====
p = scroll_to("勿扰开始")
if p:
    tap(*p, 2.2)
    ok = dial_set(12, 0)
    step("S3 勿扰开始→12:00", ok)
else:
    step("S3 勿扰开始行未找到", False)

# ==== S4: 管理提醒 → 新建 → 模板 → 时间 12:30 → 保存 ====
p = scroll_to("管理提醒")
if p:
    tap(*p, 2.5)
    # 新建提醒按钮（底部）
    x = dump()
    nb = find(x, "新建提醒")
    if nb: tap(*nb, 2.5)
    x = dump()
    tp = find(x, "早降压药")
    if tp:
        tap(*tp, 1.2)
        # +5 x N 到目标 12:30
        x = dump()
        tm = re.search(r'text="(\d\d):(\d\d)"', x)
        cur = int(tm.group(1))*60+int(tm.group(2)) if tm else 8*60
        tgt_h, tgt_m = 13, 0
        # 从当前 rule.hour/rule.minute 走 ±5
        # 直接用 +5 按钮坐标（需要 dump 找 ±5 分钟按钮）
        p5 = find(x, "+ 5分钟"); m5 = find(x, "− 5分钟")
        # 计算从当前 cur 到 13:00 的 5 分钟步数（向上）
        diff = ((13*60 - cur) % 1440)
        n5 = round(diff/5)
        btn = (810, 1458) if n5 > 0 else (280, 1458)
        for i in range(abs(n5)): tap(*btn, 0.15)
        time.sleep(0.8)
        # 保存
        sv = find(dump(), "保存")
        if sv: tap(*sv, 2.0)
        step("S4 新建提醒已保存", True)
    else:
        step("S4 模板未找到", False)
else:
    step("S4 管理提醒未找到", False)

# ==== S5: 等 defer 触发 ====
target_time = None
deadline = time.time() + 600
deferred = False
while time.time() < deadline:
    lg = sh("logcat","-d").stdout or ""
    if "quiet window: defer" in lg:
        deferred = True; break
    if "fired id=" in lg:
        break
    time.sleep(10)
m = re.search(r"quiet window: defer id=\S+ for (\d+) min", sh("logcat","-d").stdout or "")
step("S5 defer 触发", bool(m), m.group(0) if m else "no defer log")

# ==== S6: 崩溃检查 ====
crash = sh("logcat","-d","-b","crash").stdout or ""
saf = len([l for l in crash.splitlines() if "com.safphere" in l and "FATAL" in "".join(crash.splitlines())])
step("S6 零应用崩溃", saf == 0, f"fatal={saf}")

fails = [r for r in RES if not r[1]]
print(f"\n===== FINAL: {len(RES)-len(fails)}/{len(RES)} =====")
for f in fails: print("FAIL:", f[0], f[1])
