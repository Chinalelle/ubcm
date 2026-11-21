#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 30 个独立业务系统用移动端 UA 抓首页 HTML，判定移动端形态。
只读 GET，不登录。"""
import json, subprocess, re, os, hashlib
from urllib.parse import urlparse

ROOT = "/Users/kang/Desktop/My Projects/ubcm"
OUTDIR = "/tmp/ubcm-html"
os.makedirs(OUTDIR, exist_ok=True)
MOBILE_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
             "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
APPS = json.load(open(f"{ROOT}/research/raw/apps-clean.json"))
NW = [a for a in APPS if "workflow.bucm.edu.cn" not in (a["pc_url"] or "")]

FP = {
    "vue": r"vue(\.min)?\.js|Vue\.config|v-app|data-v-",
    "react": r"react(-dom)?(\.production)?(\.min)?\.js|__REACT|_reactRootContainer|reactroot",
    "elementui": r"element-ui|el-button|el-input",
    "jquery": r"jquery",
    "angular": r"angular(\.min)?\.js|ng-app",
    "nuxt": r"__NUXT__|_nuxt/",
    "nextjs": r"__NEXT_DATA__|_next/static",
    "wechat_jssdk": r"res\.wx\.qq\.com/open/js/jweixin|jweixin-\d\.\d|wx\.config\(|WeixinJSBridge",
    "wx_oauth": r"open\.weixin\.qq\.com/connect/oauth2",
    "viewport": r'<meta[^>]+name=["\']viewport',
    "loginform": r'type=["\']password|name=["\'](username|userName|loginName|account)',
    "cas": r"cas\.bucm\.edu\.cn",
    "sso": r"/sso|/cas|/login",
    "h5app_shell": r"chunk-vendors|app\.[0-9a-f]{8}\.js|/static/js/(app|index)\.",
}

def fetch(url, tag, maxtime=15):
    url = (url or "").strip()
    path = os.path.join(OUTDIR, tag + ".html")
    cmd = ["curl", "-m", str(maxtime), "-sS", "-L", "--max-redirs", "6", "-A", MOBILE_UA,
           "--connect-timeout", "8", "-D", path + ".hdr", "-o", path,
           "-w", "%{http_code}|%{url_effective}|%{size_download}|%{content_type}", url]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=maxtime + 25)
    except subprocess.TimeoutExpired:
        return {"url": url, "err": "进程超时"}
    out = p.stdout.strip().split("|")
    rec = {"url": url, "code": None, "final": "", "size": 0, "ctype": "", "err": ""}
    if len(out) == 4:
        rec.update(code=out[0], final=out[1], size=int(out[2] or 0), ctype=out[3])
    if p.returncode != 0:
        cm = {6: "DNS 解析失败", 7: "连接失败/拒绝", 28: "超时", 35: "TLS 握手失败",
              60: "TLS 证书校验失败", 56: "接收数据失败", 52: "空响应"}
        rec["err"] = cm.get(p.returncode, f"curl exit {p.returncode}") + (
            " :: " + p.stderr.strip().replace("\n", " ")[:120] if p.stderr.strip() else "")
    return rec

def analyze(rec, tag):
    path = os.path.join(OUTDIR, tag + ".html")
    html = ""
    if os.path.exists(path):
        html = open(path, "rb").read().decode("utf-8", "ignore")
    a = {"chars": len(html)}
    low = html.lower()
    for k, pat in FP.items():
        a[k] = bool(re.search(pat, html, re.I))
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
    a["title"] = (m.group(1).strip()[:60] if m else "")
    # 可见文本（粗略）
    txt = re.sub(r"<script.*?</script>|<style.*?</style>", " ", html, flags=re.S | re.I)
    txt = re.sub(r"<[^>]+>", " ", txt)
    txt = re.sub(r"\s+", " ", txt).strip()
    a["text"] = txt[:300]
    a["gen"] = re.findall(r'name=["\']generator["\'][^>]*content=["\']([^"\']+)', html, re.I)[:1]
    a["md5"] = hashlib.md5(html.encode("utf-8", "ignore")).hexdigest()[:10]
    return a

res = {}
for a in NW:
    tag = hashlib.md5(a["appid"].encode()).hexdigest()[:8]
    target = a["mobile_url"] or a["pc_url"]
    r = fetch(target, tag)
    r["tag"] = tag
    r["target"] = target
    r["is_mobile_url"] = bool(a["mobile_url"])
    r["html"] = analyze(r, tag)
    res[a["appid"]] = r
    print(f"{a['name'][:20]:22s} {r['code']} -> {(r['final'] or '')[:80]} ({r['size']}B, {r['html']['chars']}ch)")
json.dump(res, open(f"{ROOT}/research/systems-mobile.json", "w"), ensure_ascii=False, indent=1)
print("OK")
