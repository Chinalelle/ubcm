#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量只读 GET 探测：163 个应用的 PC / 移动端可达性。
不登录、不提交表单、不扫目录；仅 GET。"""
import json, subprocess, os, sys
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

ROOT = "/Users/kang/Desktop/My Projects/ubcm"
APPS = json.load(open(f"{ROOT}/research/raw/apps-clean.json"))
MOBILE_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
             "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
DESKTOP_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
OUT = f"{ROOT}/research/apps-probe.json"
FMT = "%{http_code}|%{redirect_url}|%{time_total}|%{size_download}|%{content_type}|%{url_effective}"

def curl(url, ua, follow=False, save=None, maxtime=12):
    url = (url or "").strip().replace("\n", "").replace("\r", "")
    if not url:
        return {"err": "URL 为空", "code": None}
    cmd = ["curl", "-m", str(maxtime), "-sS", "-A", ua,
           "-w", FMT, "--connect-timeout", "8"]
    if follow:
        cmd += ["-L", "--max-redirs", "5"]
    if save:
        cmd += ["-o", save]
    else:
        cmd += ["-o", "/dev/null"]
    cmd.append(url)
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=maxtime + 20)
    except subprocess.TimeoutExpired:
        return {"err": "curl 超时（进程级）", "code": None}
    out = p.stdout.strip()
    err = p.stderr.strip().replace("\n", " ")
    parts = out.split("|")
    rec = {"code": None, "redirect_url": "", "time": None, "size": 0,
           "ctype": "", "effective": "", "err": ""}
    if len(parts) == 6:
        c, r, t, s, ct, eff = parts
        rec.update(code=(int(c) if c.isdigit() and c != "000" else c),
                   redirect_url=r, time=float(t) if t else None,
                   size=int(s) if s.isdigit() else 0, ctype=ct, effective=eff)
    if p.returncode != 0:
        code_map = {6: "DNS 解析失败", 7: "连接失败/拒绝", 28: "超时(-m 12)",
                    35: "TLS 握手失败", 60: "TLS 证书校验失败", 52: "空响应",
                    56: "接收数据失败", 5: "代理解析失败"}
        rec["err"] = code_map.get(p.returncode, f"curl exit {p.returncode}") + (
            (" :: " + err) if err else "")
    return rec

def task(side, url, ua, follow=False):
    return side, curl(url, ua, follow=follow)

def main():
    jobs = []          # (key, side, url, ua, follow)
    for a in APPS:
        jobs.append((a["appid"], "pc", a["pc_url"], DESKTOP_UA, False))
        jobs.append((a["appid"], "pc_follow", a["pc_url"], DESKTOP_UA, True))
        if a["mobile_url"]:
            jobs.append((a["appid"], "m", a["mobile_url"], DESKTOP_UA, False))
            jobs.append((a["appid"], "m_ua", a["mobile_url"], MOBILE_UA, False))
            jobs.append((a["appid"], "m_ua_follow", a["mobile_url"], MOBILE_UA, True))
    print(f"请求数 = {len(jobs)}", flush=True)
    results = {}
    done = 0
    from concurrent.futures import as_completed
    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = {ex.submit(curl, j[2], j[3], j[4]): j for j in jobs}
        for f in as_completed(futs):
            j = futs[f]
            r = f.result()
            results.setdefault(j[0], {})[j[1]] = r
            done += 1
            if done % 50 == 0:
                print(f"  {done}/{len(jobs)}", flush=True)
    json.dump({"mobile_ua": MOBILE_UA, "desktop_ua": DESKTOP_UA, "results": results},
              open(OUT, "w"), ensure_ascii=False, indent=1)
    print("写入", OUT, flush=True)

if __name__ == "__main__":
    main()
