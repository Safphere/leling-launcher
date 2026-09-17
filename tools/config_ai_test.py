# -*- coding: utf-8 -*-
"""配置App的AI为本地Claude中转并测试连接"""
import os
import subprocess, os, re, time
import xml.etree.ElementTree as ET

os.environ['MSYS_NO_PATHCONV'] = '1'
ADB = os.environ.get("ADB", "adb")
DIR = "shots/"
SER = "emulator-5554"

KEY = os.environ.get("AI_KEY", "")
BASE = os.environ.get("AI_BASE", "https://your-ai-relay.example.com/api/anthropic")
MODEL = "glm-5.2"

def sh(*a):
    return subprocess.run([ADB, "-s", SER, "shell"] + list(a), capture_output=True, text=True)

def dump(f):
    sh("rm", "-f", "/sdcard/ui.xml")
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    r = subprocess.run([ADB, "-s", SER, "pull", "/sdcard/ui.xml", DIR + f], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("dump fail")
    return ET.parse(DIR + f).getroot()

def tap(x, y):
    sh("input", "tap", str(x), str(y))

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2

def find_down(text, maxs=4):
    root = dump("_fd.xml")
    for i in range(maxs + 1):
        for n in root.iter('node'):
            if n.get('text', '').startswith(text) and n.get('clickable') == 'true':
                return center(n.get('bounds'))
        if i < maxs:
            sh("input", "swipe", "540", "1800", "540", "900", "400")
            time.sleep(1.2)
            root = dump("_fd.xml")
    return None

def fill_save(value):
    t = dump("_dlg.xml")
    for n in t.iter('node'):
        if n.get('class', '').endswith('EditText'):
            cx, cy = center(n.get('bounds'))
            tap(cx, cy)
            time.sleep(0.9)
            sh("input", "keycombination", "113", "29")
            time.sleep(0.4)
            sh("input", "text", value)
            time.sleep(0.4)
            sh("input", "keyevent", "111")
            time.sleep(0.9)
            t2 = dump("_dlg2.xml")
            for m in t2.iter('node'):
                if m.get('text') == '保存':
                    tap(*center(m.get('bounds')))
                    time.sleep(1)
                    return True
    return False

# 1. 进子女设置
root = dump("_a0.xml")
dots = None
for n in root.iter('node'):
    if (n.get('resource-id') or '').endswith('/dotsRow'):
        dots = center(n.get('bounds'))
        break
assert dots, "dotsRow not found"
for _ in range(5):
    tap(*dots)
    time.sleep(0.4)
time.sleep(2.2)

root = dump("_a1.xml")
for k in ['1', '2', '3', '4']:
    for n in root.iter('node'):
        if n.get('text') == k and n.get('clickable') == 'true':
            tap(*center(n.get('bounds')))
            time.sleep(0.4)
            break
for n in root.iter('node'):
    if n.get('text') == '✓':
        tap(*center(n.get('bounds')))
        break
time.sleep(2.2)

# 2. 三项配置
pos = find_down("接口地址")
assert pos, "接口地址行未找到"
tap(*pos); time.sleep(1.2)
print("URL saved:", fill_save(BASE))

pos = find_down("模型：")
assert pos, "模型行未找到"
tap(*pos); time.sleep(1.2)
print("MODEL saved:", fill_save(MODEL))

pos = find_down("API Key")
assert pos, "Key行未找到"
tap(*pos); time.sleep(1.2)
print("KEY saved:", fill_save(KEY))

# 3. 测试连接（真实网络请求，留足时间）
pos = find_down("测试 AI 连接")
assert pos, "测试行未找到"
tap(*pos)
time.sleep(15)
root = dump("_a6.xml")
found = False
for n in root.iter('node'):
    t = n.get('text', '')
    if t.startswith('连接') or '回复' in t:
        print("结果:", t[:150])
        found = True
if not found:
    print("未见结果弹窗，页面文本:",
          [(n.get('text') or '')[:20] for n in root.iter('node') if n.get('text')][:8])
