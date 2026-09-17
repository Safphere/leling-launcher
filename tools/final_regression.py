# -*- coding: utf-8 -*-
"""最终全量回归（状态感知版）"""
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

def dump(f):
    for _ in range(2):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        r = subprocess.run([ADB, "-s", SER, "pull", "/sdcard/ui.xml", DIR + f], capture_output=True)
        if r.returncode == 0:
            try:
                return ET.parse(DIR + f).getroot()
            except ET.ParseError:
                pass
        time.sleep(1.2)
    raise RuntimeError("dump failed: " + f)

def tap(x, y):
    sh("input", "tap", str(x), str(y))

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2

def texts(root, n=40):
    return [(x.get('text') or '') for x in root.iter('node') if x.get('text')]

def has(root, text):
    return any(x.get('text') == text for x in root.iter('node'))

def find(root, text):
    for n in root.iter('node'):
        if n.get('text') == text:
            return center(n.get('bounds'))
    return None

def goto_page(target):
    """通过页面特征判断当前页并滑动到目标页（0=呼叫 1=生活 2=应用）"""
    for attempt in range(4):
        root = dump("_nav.xml")
        tx = texts(root)
        cur = 0 if any('Son' in t or '添加联系人' in t for t in tx) else \
              1 if any('语音问答' in t or '点我听天气' in t for t in tx) else \
              2 if any('Calendar' in t or 'Camera' in t for t in tx) else -1
        if cur == target:
            return True
        # 朝目标方向滑动（每步滑一页）
        if target > cur:
            sh("input", "swipe", "880", "1200", "200", "1200", "400")
        else:
            sh("input", "swipe", "200", "1200", "880", "1200", "400")
        time.sleep(2.2)
    return False

def record(name, ok, detail=""):
    (PASS if ok else FAIL).append(f"{name} {detail}")
    print(("✅" if ok else "❌"), name, detail)

print("===== Phase A: 三页结构 + 导航 =====")
goto_page(0)
root = dump("_fa.xml")
record("P0 呼叫页（SOS条+联系人）",
       has(root, "🆘 紧急求助 · 按住3秒") and has(root, "Son") and has(root, "添加联系人"))
goto_page(1)
root = dump("_fb.xml")
record("P1 生活页（时钟/天气/日历/语音入口）",
       has(root, "🎤 语音问答：有问题点我问") and has(root, "2026年9月"))
goto_page(2)
root = dump("_fc.xml")
record("P2 应用页（白名单图标）", has(root, "Calendar") and has(root, "Camera"))

print("===== Phase B: 底部快捷栏 =====")
goto_page(0)
root = dump("_fb1.xml")
phone_btn = None
for n in root.iter('node'):
    if n.get('text') == 'Phone':
        phone_btn = center(n.get('bounds'))
if phone_btn:
    tap(*phone_btn); time.sleep(2.5)
    act = sh("dumpsys", "activity", "activities").stdout
    record("快捷栏 Phone → 拨号盘", "dial" in act.lower())
    sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2.5)
else:
    record("快捷栏 Phone", False, "按钮未找到")
goto_page(2)

print("===== Phase C: 联系人直呼 + 挂断 =====")
goto_page(0)
root = dump("_fc1.xml")
pos = find(root, "Son")
assert pos, "Son不在桌面"
tap(*pos); time.sleep(3.5)
act = sh("dumpsys", "activity", "activities").stdout
record("照片直呼（InCall）", "InCallActivity" in act)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(3)
root = dump("_fc2.xml")
chip = find(root, "📞 通话中 · 点这里挂断电话")
if chip:
    tap(*chip); time.sleep(2.5)
    act = sh("dumpsys", "activity", "activities").stdout
    record("挂断条结束通话", "InCallActivity" not in act)
else:
    record("挂断条出现", False, "未找到通话中条（可能通话已由对方结束）")

print("===== Phase D: 长按菜单 + 发短信落库 =====")
goto_page(0)
root = dump("_fd0.xml")
pos = find(root, "Son")
sh("input", "swipe", str(pos[0]), str(pos[1]), str(pos[0]), str(pos[1]), "1000")
time.sleep(2.5)
root = dump("_fd1.xml")
sms_item = find(root, "✉️ 发短信")
assert sms_item, "长按菜单未出现: " + str(texts(root)[:6])
tap(*sms_item); time.sleep(2.2)
root = dump("_fd2.xml")
phrase = find(root, "记得吃药")
assert phrase, "短信页未打开: " + str(texts(root)[:6])
tap(*phrase); time.sleep(0.8)
send = find(root, "📨 发送短信")
tap(*send); time.sleep(2.5)
sms = sh("content", "query", "--uri", "content://sms/sent", "--projection", "address:body").stdout
record("发短信真实落库", "记得吃药" in sms)

print("===== Phase E: 快捷栏 Messages → 收件箱 =====")
goto_page(2)
root = dump("_fe0.xml")
msg_btn = None
for n in root.iter('node'):
    if n.get('text') == 'Messages' and n.get('clickable') == 'true':
        msg_btn = center(n.get('bounds'))
if msg_btn is None:
    root = dump("_fe0b.xml")
    msg_btn = find(root, "Messages")
tap(*msg_btn); time.sleep(3)
act = sh("dumpsys", "activity", "activities").stdout
record("快捷栏 Messages → 短信应用", "messaging" in act.lower() or "Messages" in act)
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
sh("input", "keyevent", "KEYCODE_HOME"); time.sleep(2)

print("===== Phase F: SIM/流量警报（真实短信链路） =====")
sh("emu", "sms", "send", "10086", "您本月剩余流量为39MB，请注意使用")
time.sleep(4)
data1 = sh("settings", "get", "global", "mobile_data").stdout.strip()
record("低流量自动关数据", data1 == "0", f"mobile_data={data1}")
sh("emu", "sms", "send", "10086", "您本月剩余流量为2.8GB，请放心使用")
time.sleep(4)
data2 = sh("settings", "get", "global", "mobile_data").stdout.strip()
record("充足自动恢复", data2 == "1", f"mobile_data={data2}")

print("===== Phase G: AI 离线技能 =====")
goto_page(1)
root = dump("_fg0.xml")
ai = find(root, "🎤 语音问答：有问题点我问")
tap(*ai); time.sleep(2.5)
root = dump("_fg1.xml")
edit = None
for n in root.iter('node'):
    if n.get('class', '').endswith('EditText'):
        edit = center(n.get('bounds'))
assert edit, "AI输入框未找到"
tap(*edit); time.sleep(1.2)
sh("input", "text", "what%sis%sthe%stime")
time.sleep(0.5)
sh("input", "keyevent", "111"); time.sleep(1)
root = dump("_fg2.xml")
send = find(root, "发送")
tap(*send); time.sleep(2)
root = dump("_fg3.xml")
tx = texts(root)
record("AI离线答时间", any("现在是" in t for t in tx), str([t for t in tx if "现在" in t][:1]))
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)

print("===== Phase H: 日历点日期详情 =====")
goto_page(1)
root = dump("_fh0.xml")
day15 = None
for n in root.iter('node'):
    if n.get('text') == '15':
        day15 = center(n.get('bounds'))
assert day15, "日历15号未找到"
tap(*day15); time.sleep(2)
root = dump("_fh1.xml")
record("日历日期详情（农历）", any("2026年9月15日" in t for t in texts(root, 60)))
sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

print("===== Phase I: 隐藏入口 → 子女模式 =====")
goto_page(0)
root = dump("_fi0.xml")
dots = None
for n in root.iter('node'):
    rid = n.get('resource-id', '')
    if rid.endswith('/dotsRow'):
        dots = center(n.get('bounds'))
assert dots, "指示点行未找到"
for _ in range(5):
    tap(*dots); time.sleep(0.45)
time.sleep(2.5)
root = dump("_fi1.xml")
pin1 = find(root, "1")
record("5连击隐藏入口 → PIN门", pin1 is not None)
for k, c in [("1", pin1)]:
    pass
pin2 = None
root = dump("_fi2.xml")
keys = {}
for n in root.iter('node'):
    t = n.get('text', '')
    if t in ('1', '2', '3', '4') and n.get('clickable') == 'true':
        keys[t] = center(n.get('bounds'))
if keys:
    for k in ['1', '2', '3', '4']:
        tap(*keys[k]); time.sleep(0.45)
    ok = find(root, '✓')
    if ok is None:
        root = dump("_fi3.xml")
        ok = find(root, '✓')
    if ok:
        tap(*ok); time.sleep(2.2)
root = dump("_fi4.xml")
record("PIN解锁进入设置", any("紧急联系人" in t for t in texts(root, 60)) or
       any("联系人大小" in t for t in texts(root, 60)))

print("===== Phase J: 崩溃检查 =====")
crash = sh("logcat", "-d").stdout
n = crash.count("FATAL EXCEPTION")
record("零崩溃", n == 0, f"count={n}")

print()
print("========== 回归结果 ==========")
for p in PASS:
    print("✅", p)
for f in FAIL:
    print("❌", f)
print(f"通过 {len(PASS)} / 总 {len(PASS)+len(FAIL)}")
