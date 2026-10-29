from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, HTMLResponse
from pydantic import BaseModel
from typing import Dict, List, Optional
import datetime
import json
import shutil
import subprocess

app = FastAPI(title="OKULUMBENİM - School MDM Server")

APK_URL_V1 = "https://github.com/mehmetdemir-tr/Okulum-Benim/releases/download/v1/app-debug.apk"
APK_CHECKSUM_V1 = "bJDG9b2N6Gg6iePYvd_gxGcEhgVkUB7HAuSc7P05d7Y"

devices_db: Dict[str, dict] = {}
pending_commands: Dict[str, List[str]] = {}

class DeviceRegisterRequest(BaseModel):
    device_id: str
    model: str
    android_version: str

class CommandRequest(BaseModel):
    device_id: str
    command: str  # Örn: "LOCK", "UNLOCK", "RESTRICT_CAMERA", "ALLOW_CAMERA"

class ProvisionQrRequest(BaseModel):
    wifi_ssid: str
    wifi_password: str = ""
    wifi_security: str = "WPA"
    apk_url: str = "https://github.com/mehmetdemir-tr/Okulum-Benim/releases/download/v1/app-debug.apk"
    apk_checksum: str = ""
    server_url: str = "http://192.168.1.106:8000/api/v1"
    skip_encryption: bool = True
    leave_system_apps: bool = True

@app.get("/")
def read_root():
    return {"status": "OK", "service": "OKULUMBENİM MDM Server"}

@app.post("/api/v1/register")
def register_device(req: DeviceRegisterRequest):
    devices_db[req.device_id] = {
        "model": req.model,
        "android_version": req.android_version,
        "last_seen": datetime.datetime.now().isoformat(),
        "status": "ACTIVE"
    }
    if req.device_id not in pending_commands:
        pending_commands[req.device_id] = []
    
    return {"status": "REGISTERED", "device_id": req.device_id}

@app.get("/api/v1/poll/{device_id}")
def poll_commands(device_id: str):
    if device_id in devices_db:
        devices_db[device_id]["last_seen"] = datetime.datetime.now().isoformat()
    
    cmds = pending_commands.get(device_id, [])
    pending_commands[device_id] = []
    return {"commands": cmds}

@app.post("/api/v1/send-command")
def send_command(req: CommandRequest):
    if req.device_id not in devices_db and req.device_id != "ALL":
        raise HTTPException(status_code=404, detail="Cihaz bulunamadı")
    
    if req.device_id == "ALL":
        for dev_id in pending_commands:
            pending_commands[dev_id].append(req.command)
    else:
        pending_commands[req.device_id].append(req.command)
        
    return {"status": "COMMAND_QUEUED", "command": req.command, "target": req.device_id}

@app.get("/api/v1/devices")
def list_devices():
    return {"devices": devices_db}

def build_qr_payload(wifi_ssid: str, wifi_password: str = "", wifi_security: str = "WPA",
                     apk_url: str = APK_URL_V1, apk_checksum: str = APK_CHECKSUM_V1,
                     server_url: str = "http://192.168.1.106:8000/api/v1",
                     skip_encryption: bool = True, leave_system_apps: bool = True):
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.example.okulumbenim/.AdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": skip_encryption,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": leave_system_apps,
        "android.app.extra.PROVISIONING_WIFI_SSID": wifi_ssid,
        "android.app.extra.PROVISIONING_WIFI_PASSWORD": wifi_password,
        "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": wifi_security,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
            "server_url": server_url,
        },
    }
    if apk_checksum:
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = apk_checksum
    return payload


@app.post("/api/v1/provision-qr")
def provision_qr(req: ProvisionQrRequest):
    payload = build_qr_payload(req.wifi_ssid, req.wifi_password, req.wifi_security,
                               req.apk_url, req.apk_checksum or APK_CHECKSUM_V1,
                               req.server_url, req.skip_encryption, req.leave_system_apps)
    if not req.apk_checksum:
        payload["_BILGI"] = "v1 checksum otomatik eklendi; yeni APK'da apk_checksum gonder."
    return {
        "qr_payload": payload,
        "nasil": "Bu JSON'u bir QR uretece gir (orn. qrencode / online generator), fabrika resetli cihazda karsilama ekranina 6 kez dokunup okut.",
    }


@app.get("/api/v1/provision-qr.png")
def provision_qr_png(wifi_ssid: str = "OKUL_WIFI_ADI", wifi_password: str = "OKUL_WIFI_SIFRE",
                     wifi_security: str = "WPA", server_url: str = "http://192.168.1.106:8000/api/v1"):
    if shutil.which("qrencode") is None:
        raise HTTPException(status_code=500, detail="qrencode kurulu degil")
    payload = build_qr_payload(wifi_ssid, wifi_password, wifi_security,
                               APK_URL_V1, APK_CHECKSUM_V1, server_url)
    raw = json.dumps(payload, sort_keys=True).encode()
    try:
        proc = subprocess.run(["qrencode", "-o", "-", "-s", "8", "-m", "2"],
                              input=raw, capture_output=True, timeout=15)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"QR uretilemedi: {e}")
    if proc.returncode != 0:
        raise HTTPException(status_code=500, detail="QR uretilemedi")
    return Response(content=proc.stdout, media_type="image/png")


@app.get("/api/v1/config-qr.png")
def config_qr_png(server_url: str = "http://192.168.1.106:8000/api/v1"):
    if shutil.which("qrencode") is None:
        raise HTTPException(status_code=500, detail="qrencode kurulu degil")
    raw = json.dumps({"server_url": server_url}, sort_keys=True).encode()
    try:
        proc = subprocess.run(["qrencode", "-o", "-", "-s", "8", "-m", "2"],
                              input=raw, capture_output=True, timeout=15)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"QR uretilemedi: {e}")
    if proc.returncode != 0:
        raise HTTPException(status_code=500, detail="QR uretilemedi")
    return Response(content=proc.stdout, media_type="image/png")


@app.get("/qr", response_class=HTMLResponse)
def qr_page(wifi_ssid: str = "OKUL_WIFI_ADI", wifi_password: str = "OKUL_WIFI_SIFRE",
            server_url: str = "http://192.168.1.106:8000/api/v1"):
    return f"""<!doctype html><html lang="tr"><head><meta charset="utf-8">
<title>OKULUMBENIM Kurulum QR</title></head><body style="font-family:sans-serif;text-align:center">
<h2>OKULUMBENIM Cihaz Kurulum</h2>
<p>1. Cihazi fabrika ayarlarina dondur &nbsp; 2. Karsilama ekranina 6 kez dokun &nbsp; 3. Bu QR'i okut</p>
<img src="/api/v1/provision-qr.png?wifi_ssid={wifi_ssid}&wifi_password={wifi_password}&server_url={server_url}" alt="kurulum QR">
<p>WiFi: {wifi_ssid} &nbsp; Server: {server_url}</p>
</body></html>"""