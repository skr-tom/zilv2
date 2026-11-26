#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自律 · 应用内更新（OTA）本地测试服务器

用途：不用 GitHub、不用公网，手机和电脑连同一个 WiFi 就能跑完整条
      「检查 → 下载 → 覆盖安装」链路。

用法：
  1. 构建一个 versionCode=7 的 APK，放到本目录，命名为 zilu-test.apk
     （不改工程文件的话：./gradlew assembleRelease -PziluVersionCode=7
      -PziluVersionName=4.3.0-test，产物重命名为 zilu-test.apk）
  2. 运行：python ota-test/serve.py      （Windows 也可用 py ota-test/serve.py）
  3. 终端会打印手机该填的「更新地址」，例如 http://192.168.1.8:8000/update.json
  4. 手机安装 versionCode=6 的基线包，设置 → 版本更新 → 填入该地址 → 检查更新

说明：/update.json 里的 apkUrl 是按请求里的 Host 头动态生成的，
      所以不用手工改 IP；手机用哪个地址访问，下载地址就用哪个地址。
"""
import json
import os
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.path.join(HERE, "zilu-test.apk")
PORT = int(os.environ.get("OTA_PORT", "8000"))
VERSION_CODE = int(os.environ.get("OTA_VERSION_CODE", "7"))
VERSION_NAME = os.environ.get("OTA_VERSION_NAME", "4.3.0-test")


def lan_ips():
    ips = []
    # 用 UDP connect 拿到「能出网的那张网卡」的地址（不会真的发包）
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        if ip and not ip.startswith("127."):
            ips.append(ip)
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def host(self):
        return self.headers.get("Host", "127.0.0.1:%d" % PORT)

    def send_bytes(self, code, content_type, body):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/update.json"):
            if not os.path.isfile(APK):
                self.send_bytes(404, "text/plain; charset=utf-8",
                                "缺少 zilu-test.apk，请先把测试 APK 放进本目录".encode("utf-8"))
                return
            data = {
                "versionCode": VERSION_CODE,
                "versionName": VERSION_NAME,
                "apkUrl": "http://%s/apk" % self.host(),
                "changelog": "本地 OTA 链路测试包：验证检查 / 下载 / 覆盖安装 / 数据保留",
                "force": False,
                "sizeBytes": os.path.getsize(APK),
            }
            self.send_bytes(200, "application/json; charset=utf-8",
                            json.dumps(data, ensure_ascii=False).encode("utf-8"))
        elif path == "/apk":
            if not os.path.isfile(APK):
                self.send_bytes(404, "text/plain; charset=utf-8", b"APK not found")
                return
            size = os.path.getsize(APK)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(APK, "rb") as f:
                while True:
                    chunk = f.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        elif path == "/ping":
            self.send_bytes(200, "text/plain; charset=utf-8", b"zilu-ota-ok")
        else:
            self.send_bytes(404, "text/plain; charset=utf-8", b"not found")

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    print("=" * 64)
    print("自律 OTA 本地测试服务器   端口 %d   版本 %s (%d)"
          % (PORT, VERSION_NAME, VERSION_CODE))
    if os.path.isfile(APK):
        print("测试 APK 已就位：%.1f MB" % (os.path.getsize(APK) / 1024 / 1024))
    else:
        print("!! 还没找到 ota-test/zilu-test.apk，请先放入 versionCode=7 的 APK")
    print("手机和电脑必须连同一个 WiFi；如连不上，检查电脑防火墙是否放行 %d 端口" % PORT)
    print("设置页「更新地址」填下面任意一个（用手机浏览器先打开 /ping 可测连通）：")
    for ip in lan_ips():
        print("    http://%s:%d/update.json" % (ip, PORT))
    print("=" * 64, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
