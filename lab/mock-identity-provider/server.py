#!/usr/bin/env python3
"""
모의 본인확인기관 서버 (Mock Identity Provider) — 실습 전용

- 실제 본인확인·신원 검증을 하지 않습니다. 어떤 개인정보도 받거나 저장하지 않습니다.
- 공개된 인증서 API 가이드의 "흐름 구조"만 참고했습니다 (docs/08-public-guide-reference.md).
    접근토큰 발급 → 인증 요청(composite_key, 만료시각, 표준창 URL) → 결과 요청(polling) → 결과코드
  결과코드는 실제 기관 코드와 겹치지 않도록 MV(Mock Verification) 접두어를 씁니다.
- 외부 의존성 없이 Python 표준 라이브러리만 사용합니다.

실행:  python3 lab/mock-identity-provider/server.py
환경변수(모두 실습용 가정값):
  MOCK_IDP_PORT=18090            서버 포트
  MOCK_IDP_TTL_SECONDS=300       인증 요청 유효시간(초)
  MOCK_IDP_CORP_ID=IDV-LAB    이용기관 ID
  MOCK_IDP_CREDENTIAL=lab-mock-credential
  MOCK_IDP_TOKEN_TTL_SECONDS=3600 접근토큰 유효시간(초)
  MOCK_IDP_ALLOWED_CALLBACKS=http://localhost:8080/lab/identity-demo   등록된 callback URL(쉼표 구분)
  MOCK_IDP_PKI_DIR=lab/pki/out   결과 서명용 키·인증서 위치 (lab/pki/generate-lab-pki.sh로 생성)

결과 전자서명(실습):
  결과 응답마다 아래 서명 원문을 SHA256withRSA로 서명해 signed_data(Base64)와 signer_cert(Base64 DER)를 붙인다.
    MVSIG1|{corp_id}|{trx_key}|{client_request_id}|{response_code}|{status}|{completed_dttm}
  이용기관 서버는 Lab Root CA로 인증서 체인·유효기간·용도를 검증하고 서명을 확인한 뒤에만 결과를 반영한다.
  (공개 가이드의 signed_data(PKCS#7) 개념을 단순화한 학습용 구현)
"""
import html
import json
import os
import secrets
import socket
import socketserver
import base64
import subprocess
from collections import Counter
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse, urlsplit, urlunsplit

PORT = int(os.environ.get("MOCK_IDP_PORT", "18090"))
TTL_SECONDS = int(os.environ.get("MOCK_IDP_TTL_SECONDS", "300"))
CORP_ID = os.environ.get("MOCK_IDP_CORP_ID", "IDV-LAB")
CREDENTIAL = os.environ.get("MOCK_IDP_CREDENTIAL", "lab-mock-credential")
TOKEN_TTL_SECONDS = int(os.environ.get("MOCK_IDP_TOKEN_TTL_SECONDS", "3600"))
ALLOWED_CALLBACKS = [u.strip() for u in os.environ.get(
    "MOCK_IDP_ALLOWED_CALLBACKS", "http://localhost:8080/lab/identity-demo").split(",") if u.strip()]
PKI_DIR = os.environ.get("MOCK_IDP_PKI_DIR", os.path.join(os.path.dirname(__file__), "..", "pki", "out"))
SIGN_ALG = "SHA256withRSA"

DTTM = "%Y%m%d%H%M%S"

# ── 결과코드 (공개 가이드 오류코드 표의 '구조'를 참고한 모의 코드) ─────────────
CODES = {
    "MV0000": (200, "정상처리 완료"),
    "MV0100": (200, "인증 결과 대기중 (사용자 인증 미진행)"),
    "MV1000": (200, "사용자 직접 취소 (모의 인증창에서 취소)"),
    "MV1001": (200, "사용자 응답 없음 (유효시간 내 인증 미진행)"),
    "MV1002": (200, "인증 실패 (모의 시나리오: 가입자 정보 불일치)"),
    "MV1004": (200, "이용기관 요청으로 취소됨"),
    "MV9003": (503, "서버 통신 오류 (모의 장애)"),
    "MV9102": (401, "AccessToken 만료 또는 유효하지 않음"),
    "MV9104": (400, "서비스 요청 파라미터 오류"),
    "MV9106": (406, "존재하지 않는 트랜잭션"),
    "MV9107": (400, "등록되지 않은 callback URL"),
    "MV9200": (401, "허용되지 않은 이용기관 코드 또는 credential"),
}

# 트랜잭션 상태 → 결과코드
STATUS_CODE = {
    "PENDING": "MV0100",
    "SUCCEEDED": "MV0000",
    "FAILED": "MV1002",
    "USER_CANCELLED": "MV1000",
    "EXPIRED": "MV1001",
    "CLIENT_CANCELLED": "MV1004",
}

lock = threading.Lock()
tokens = {}            # access_token -> expire datetime
transactions = {}      # composite_key -> dict
by_client_request = {}  # (corp_id, client_request_id) -> composite_key
fault = {"mode": "NONE", "remaining": 0}   # NONE | DOWN | TIMEOUT | DROP_AFTER_PROCESS | TAMPER | UNTRUSTED_SIGNER
FAULT_MODES = ("NONE", "DOWN", "TIMEOUT", "DROP_AFTER_PROCESS", "TAMPER", "UNTRUSTED_SIGNER")
api_calls = Counter()   # 제휴사 포털 표시용: (API, 결과코드) 호출 수


# ── 결과 전자서명 (openssl CLI 사용, 표준 라이브러리만으로 RSA 서명이 불가해 외부 명령 호출) ──────────
def _der_b64(cert_path):
    der = subprocess.run(["openssl", "x509", "-in", cert_path, "-outform", "DER"], capture_output=True, check=True).stdout
    return base64.b64encode(der).decode()


def _signer(name):
    key, crt = os.path.join(PKI_DIR, name + ".key"), os.path.join(PKI_DIR, name + ".crt")
    if not (os.path.exists(key) and os.path.exists(crt)):
        return None
    return {"key": key, "cert_b64": _der_b64(crt), "crt": crt}


SIGNER = _signer("mock-idp-signing")
ROGUE = _signer("rogue-signing")


def sign_payload(text, signer):
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", signer["key"]],
                         input=text.encode("utf-8"), capture_output=True, check=True).stdout
    return base64.b64encode(sig).decode()


def signing_input(result, code):
    return "|".join(["MVSIG1", CORP_ID, result["trx_key"], result["client_request_id"], code,
                     result["status"], result.get("completed_dttm") or ""])


def attach_signature(result, code, mode):
    """결과에 서명을 붙인다. TAMPER: 서명 후 값을 바꿈 / UNTRUSTED_SIGNER: 신뢰 체계 밖의 키로 서명"""
    signer = ROGUE if mode == "UNTRUSTED_SIGNER" else SIGNER
    if signer is None:
        return result
    result["signed_data"] = sign_payload(signing_input(result, code), signer)
    result["signer_cert"] = signer["cert_b64"]
    result["sign_alg"] = SIGN_ALG
    if mode == "TAMPER":
        result["status"] = "SUCCEEDED" if result["status"] != "SUCCEEDED" else "FAILED"
    return result


def now():
    return datetime.now().replace(microsecond=0)


def fmt(dt):
    return dt.strftime(DTTM) if dt else None


def expire_if_needed(tx):
    """유효시간이 지난 PENDING 트랜잭션은 EXPIRED(MV1001)로 확정. 이미 종료된 건은 그대로."""
    if tx["status"] == "PENDING" and now() > tx["expire_at"]:
        tx["status"] = "EXPIRED"
        tx["completed_at"] = tx["expire_at"]


def finish(tx, status):
    """종료 상태 확정. 이미 종료된 트랜잭션은 바꾸지 않는다(상태 역전 금지)."""
    expire_if_needed(tx)
    if tx["status"] != "PENDING":
        return False
    tx["status"] = status
    tx["completed_at"] = now()
    return True


def tx_result(tx):
    expire_if_needed(tx)
    return {
        "trx_key": tx["trx_key"],
        "client_request_id": tx["client_request_id"],
        "status": tx["status"],
        "expire_dttm": fmt(tx["expire_at"]),
        "completed_dttm": fmt(tx.get("completed_at")),
        "loopback_param": tx.get("loopback_param") or {},
    }


def api_name(path):
    if path.endswith("/corp/auth"):
        return "접근토큰 발급"
    if path.endswith("/identifications"):
        return "본인확인 요청"
    if path.endswith("/result"):
        return "결과 요청"
    if path.endswith("/cancel"):
        return "요청 취소"
    return path


def with_query(url, **params):
    parts = urlsplit(url)
    query = parts.query + ("&" if parts.query else "") + urlencode(params)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


class Handler(BaseHTTPRequestHandler):
    server_version = "MockIdentityProvider/1.0"

    # ── 공통 응답 ────────────────────────────────────────────────
    def send_json(self, code, result=None, message=None):
        http_status, default_message = CODES[code]
        body = {"response_code": code, "message": message or default_message}
        if result is not None:
            body["result"] = result
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(http_status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_html(self, status, text):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_admin(self, status, body):
        data = json.dumps(body, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return None

    def read_form(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        return {k: v[0] for k, v in parse_qs(raw).items()}

    def log_message(self, fmt_, *args):
        print("[mock-idp] %s - %s" % (self.address_string(), fmt_ % args), flush=True)

    # ── 장애 주입: /mock-idp/v1/** (서버 간 API)에만 적용 ─────────────────
    def apply_fault_before(self):
        """True를 반환하면 이미 응답(또는 무응답) 처리가 끝난 것."""
        with lock:
            mode = fault["mode"]
            if mode == "NONE":
                return False, None
            if fault["remaining"] > 0:
                fault["remaining"] -= 1
                if fault["remaining"] == 0:
                    fault["mode"] = "NONE"
        if mode == "DOWN":
            self.send_json("MV9003")
            return True, None
        if mode == "TIMEOUT":
            time.sleep(6)   # 이용기관 서버의 응답 타임아웃(실습 가정 3초)보다 길게
            self.send_json("MV9003", message="모의 지연 후 응답")
            return True, None
        if mode == "DROP_AFTER_PROCESS":
            return False, "DROP"   # 요청은 처리하고, 응답만 보내지 않음 (응답 유실 재현)
        return False, mode     # TAMPER / UNTRUSTED_SIGNER: 결과 응답 단계에서 적용

    def drop_connection(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.close_connection = True

    def authorized(self):
        auth = self.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else None
        with lock:
            exp = tokens.get(token)
        return exp is not None and now() <= exp

    # ── 라우팅 ───────────────────────────────────────────────────
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.send_admin(200, {"status": "UP", "mock": True})
        if url.path == "/mock-idp/web/standard-auth":
            return self.web_standard_auth(parse_qs(url.query).get("trx_key", [None])[0])
        if url.path == "/mock-idp/portal":
            return self.portal()
        if url.path == "/mock-idp/admin/identifications":
            with lock:
                items = [dict(tx_result(t), composite_key=k, client_request_id=t["client_request_id"])
                         for k, t in transactions.items()]
            return self.send_admin(200, {"items": items, "fault": fault})
        self.send_admin(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path.startswith("/mock-idp/v1/"):
            handled, after = self.apply_fault_before()
            if handled:
                return
            return self.api(path, drop=(after == "DROP"), sign_fault=after)
        if path.startswith("/mock-idp/web/standard-auth/"):
            return self.web_action(path)
        if path.startswith("/mock-idp/admin/"):
            return self.admin(path)
        self.send_admin(404, {"error": "not found"})

    # ── 서버 간 API (이용기관 서버 → 모의 기관) ──────────────────────────
    def api(self, path, drop, sign_fault=None):
        body = self.read_json()
        if body is None:
            return self.send_json("MV9104", message="JSON 형식 오류")

        def respond(code, result=None, message=None):
            api_calls[(api_name(path), code)] += 1
            if drop:
                print("[mock-idp] DROP_AFTER_PROCESS: 처리 완료, 응답 유실 재현", flush=True)
                return self.drop_connection()
            return self.send_json(code, result, message)

        # 1) 접근토큰 발급
        if path == "/mock-idp/v1/corp/auth":
            if body.get("corp_id") != CORP_ID or body.get("credential") != CREDENTIAL:
                return respond("MV9200")
            token = secrets.token_hex(16)
            exp = now() + timedelta(seconds=TOKEN_TTL_SECONDS)
            with lock:
                tokens[token] = exp
            return respond("MV0000", {"access_token": token, "expire_dttm": fmt(exp)})

        if not self.authorized():
            return respond("MV9102")

        # 2) 본인확인 요청 (client_request_id 기준 멱등)
        if path == "/mock-idp/v1/identifications":
            client_request_id = body.get("client_request_id")
            callback = body.get("client_callback_url")
            if body.get("corp_id") != CORP_ID or not client_request_id or not callback:
                return respond("MV9104", message="corp_id, client_request_id, client_callback_url 필수")
            if callback not in ALLOWED_CALLBACKS:
                # 등록된 주소로만 돌려보낸다 (오픈 리다이렉트·결과 가로채기 방지)
                return respond("MV9107")
            with lock:
                key = by_client_request.get((CORP_ID, client_request_id))
                if key is None:
                    key = secrets.token_hex(12)
                    tx = {
                        "trx_key": secrets.token_hex(10),
                        "client_request_id": client_request_id,
                        "callback": callback,
                        "service_name": body.get("req_service_name") or "본인확인 실습",
                        "status": "PENDING",
                        "created_at": now(),
                        "expire_at": now() + timedelta(seconds=TTL_SECONDS),
                        "completed_at": None,
                        "loopback_param": body.get("loopback_param") or {},
                    }
                    transactions[key] = tx
                    by_client_request[(CORP_ID, client_request_id)] = key
                tx = transactions[key]
                result = {
                    "trx_key": tx["trx_key"],
                    "composite_key": key,
                    "expire_dttm": fmt(tx["expire_at"]),
                    "standard_web_url": "http://localhost:%d/mock-idp/web/standard-auth?trx_key=%s" % (PORT, tx["trx_key"]),
                }
            return respond("MV0000", result)

        parts = path.split("/")   # ['', 'mock-idp', 'v1', 'identifications', key, action]
        if len(parts) == 6 and parts[3] == "identifications":
            key, action = parts[4], parts[5]
            with lock:
                tx = transactions.get(key)
                if tx is None:
                    return respond("MV9106")
                # 3) 결과 요청 (polling)
                if action == "result":
                    result = tx_result(tx)
                    code = STATUS_CODE[result["status"]]
                    return respond(code, attach_signature(result, code, sign_fault))
                # 4) 이용기관 요청 취소: 진행 중일 때만 취소, 이미 종료된 건은 종료 상태 그대로 응답
                if action == "cancel":
                    finish(tx, "CLIENT_CANCELLED")
                    result = tx_result(tx)
                    code = STATUS_CODE[result["status"]]
                    return respond(code, attach_signature(result, code, sign_fault))
        return respond("MV9104", message="지원하지 않는 API")

    # ── 모의 인증 화면 (브라우저) ────────────────────────────────
    def find_by_trx(self, trx_key):
        for key, tx in transactions.items():
            if tx["trx_key"] == trx_key:
                return key, tx
        return None, None

    def web_standard_auth(self, trx_key):
        with lock:
            key, tx = self.find_by_trx(trx_key)
            if tx:
                expire_if_needed(tx)
                status, service_name = tx["status"], tx["service_name"]
                remain = max(0, int((tx["expire_at"] - now()).total_seconds()))
        if not tx:
            return self.send_html(404, page("요청을 찾을 수 없습니다", "<p>존재하지 않는 인증 요청입니다.</p>"))
        if status != "PENDING":
            return self.send_html(200, page("이미 종료된 요청", f"<p>이 인증 요청은 이미 <b>{status}</b> 상태입니다.</p>"
                                                         f"<p>이용 중인 서비스 화면으로 돌아가 결과를 확인하세요.</p>"))
        body = f"""
          <p class="svc">{html.escape(service_name)}</p>
          <h1>본인요청이 맞으면 아래 버튼으로 진행해 주세요</h1>
          <p class="remain">남은 시간 <b id="remain">{remain}</b>초 (실습용 가정)</p>
          <p class="hint">실제 인증서·개인정보 입력은 없습니다. 결과 시나리오를 고르세요.</p>
          <form method="post" action="/mock-idp/web/standard-auth/{html.escape(trx_key)}">
            <button name="action" value="success" class="primary">인증 성공으로 진행</button>
            <button name="action" value="fail">인증 실패 (정보 불일치 시나리오)</button>
            <button name="action" value="cancel">인증 취소</button>
            <button name="action" value="close" class="ghost">창 닫기만 하기 (결과 없이 돌아가기)</button>
          </form>
          <script>let r={remain};setInterval(()=>{{if(r>0){{r--;document.getElementById('remain').textContent=r;}}}},1000);</script>
        """
        self.send_html(200, page("모의 인증", body))

    def web_action(self, path):
        trx_key = path.rsplit("/", 1)[-1]
        action = self.read_form().get("action")
        with lock:
            key, tx = self.find_by_trx(trx_key)
            if not tx:
                return self.send_html(404, page("요청을 찾을 수 없습니다", "<p>존재하지 않는 인증 요청입니다.</p>"))
            if action == "success":
                finish(tx, "SUCCEEDED")
            elif action == "fail":
                finish(tx, "FAILED")
            elif action == "cancel":
                finish(tx, "USER_CANCELLED")
            # close: 아무 상태도 바꾸지 않음 (화면 닫기 ≠ 취소)
            callback = tx["callback"]
            client_request_id = tx["client_request_id"]
        # 결과값은 URL에 싣지 않는다. 이용기관 서버가 서버 간 API로 직접 확인해야 한다.
        self.send_response(303)
        self.send_header("Location", with_query(callback, verificationId=client_request_id))
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ── 제휴사(이용기관) 포털: 연동 정보·서명 인증서·거래·결과코드 현황 (실습용, 인증 없음) ─────────
    def portal(self):
        with lock:
            rows = sorted(((k, dict(tx_result(t), created=t["created_at"])) for k, t in transactions.items()),
                          key=lambda kv: kv[1]["created"], reverse=True)[:50]
            status_counts = Counter(r["status"] for _, r in rows)
            calls = sorted(api_calls.items(), key=lambda kv: (-kv[1], kv[0]))
        cert_info = "서명 인증서 없음 — lab/pki/generate-lab-pki.sh 실행 필요"
        if SIGNER:
            out = subprocess.run(["openssl", "x509", "-in", SIGNER["crt"], "-noout", "-subject", "-issuer",
                                  "-startdate", "-enddate", "-fingerprint", "-sha256"],
                                 capture_output=True, text=True).stdout
            cert_info = html.escape(out)
        esc = html.escape
        tx_rows = "".join(
            f"<tr><td>{esc(r['created'].strftime('%H:%M:%S'))}</td><td><code>{esc(k[:10])}…</code></td>"
            f"<td><code>{esc(r['client_request_id'][:8])}…</code></td><td>{esc(r['status'])}</td>"
            f"<td>{esc(r['expire_dttm'])}</td><td>{esc(r['completed_dttm'] or '-')}</td></tr>" for k, r in rows) \
            or "<tr><td colspan=6>거래 없음</td></tr>"
        call_rows = "".join(f"<tr><td>{esc(api)}</td><td>{esc(code)}</td><td>{esc(CODES[code][1])}</td><td>{n}</td></tr>"
                            for (api, code), n in calls) or "<tr><td colspan=4>호출 없음</td></tr>"
        body = f"""
          <h1>제휴사 포털 (실습)</h1>
          <p class="hint">이용기관이 연동 상태를 스스로 확인하는 화면을 가정한 최소 구현입니다.</p>
          <h2>연동 정보</h2>
          <table><tr><th>이용기관 ID</th><td>{esc(CORP_ID)}</td></tr>
            <tr><th>등록 callback URL</th><td>{'<br>'.join(esc(u) for u in ALLOWED_CALLBACKS)}</td></tr>
            <tr><th>인증 유효시간</th><td>{TTL_SECONDS}초 (실습 가정)</td></tr>
            <tr><th>결과 서명 알고리즘</th><td>{SIGN_ALG}</td></tr></table>
          <h2>결과 서명 인증서</h2><pre>{cert_info}</pre>
          <h2>최근 거래 (최대 50건)</h2>
          <p class="hint">상태별: {esc(', '.join(f'{k} {v}' for k, v in status_counts.items()) or '-')}</p>
          <table><tr><th>요청</th><th>composite_key</th><th>client_request_id</th><th>상태</th><th>만료</th><th>완료</th></tr>{tx_rows}</table>
          <h2>API 호출·결과코드 현황</h2>
          <table><tr><th>API</th><th>결과코드</th><th>의미</th><th>건수</th></tr>{call_rows}</table>
        """
        self.send_html(200, page("제휴사 포털", body, wide=True))

    # ── 실습·자동화 테스트용 관리 API (모의 서버에만 존재) ───────────────
    def admin(self, path):
        body = self.read_json() or {}
        if path == "/mock-idp/admin/faults":
            mode = body.get("mode", "NONE")
            if mode not in FAULT_MODES:
                return self.send_admin(400, {"error": "mode must be one of " + "|".join(FAULT_MODES)})
            with lock:
                fault["mode"] = mode
                fault["remaining"] = int(body.get("remaining", 0))   # 0이면 해제할 때까지 계속
            return self.send_admin(200, {"fault": fault})
        if path == "/mock-idp/admin/reset":
            with lock:
                transactions.clear()
                by_client_request.clear()
                api_calls.clear()
                fault.update(mode="NONE", remaining=0)
            return self.send_admin(200, {"reset": True})
        parts = path.split("/")   # ['', 'mock-idp', 'admin', 'identifications', key, 'force']
        if len(parts) == 6 and parts[3] == "identifications" and parts[5] == "force":
            with lock:
                tx = transactions.get(parts[4])
                if tx is None:
                    return self.send_admin(404, {"error": "no transaction"})
                status = body.get("status")
                if status not in STATUS_CODE:
                    return self.send_admin(400, {"error": "unknown status"})
                if body.get("override"):
                    # 상태 역전 방지 검증용: 모의 기관 쪽 상태를 강제로 바꿔 '충돌'을 만든다
                    tx["status"] = status
                    tx["completed_at"] = now()
                elif status == "EXPIRED":
                    tx["expire_at"] = now() - timedelta(seconds=1)
                    expire_if_needed(tx)
                else:
                    finish(tx, status)
                return self.send_admin(200, tx_result(tx))
        self.send_admin(404, {"error": "not found"})


def page(title, body, wide=False):
    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)} · 실습용 모의 인증</title>
<style>
  :root {{ --bg:#f4f5f7; --card:#fff; --ink:#1e1e1e; --sub:#666; --line:#e3e5e8; --accent:#3b5bdb; --warn:#c92a2a; }}
  body {{ margin:0; background:var(--bg); color:var(--ink); font-family:-apple-system,"Apple SD Gothic Neo",sans-serif; }}
  .banner {{ background:var(--warn); color:#fff; padding:12px 16px; font-weight:700; text-align:center; }}
  main {{ max-width:{'960px' if wide else '420px'}; margin:24px auto; padding:0 16px; }}
  table {{ width:100%; border-collapse:collapse; font-size:13px; margin-bottom:16px; }}
  th, td {{ border-bottom:1px solid var(--line); padding:6px 8px; text-align:left; vertical-align:top; }}
  pre {{ background:#f8f9fa; padding:10px; border-radius:8px; font-size:12px; white-space:pre-wrap; overflow-wrap:anywhere; }}
  h2 {{ font-size:15px; margin:20px 0 8px; }}
  .card {{ background:var(--card); border:1px solid var(--line); border-radius:16px; padding:24px; }}
  h1 {{ font-size:18px; line-height:1.4; margin:8px 0 12px; }}
  .svc {{ color:var(--accent); font-weight:700; margin:0; }}
  .remain, .hint {{ color:var(--sub); font-size:14px; }}
  form {{ display:flex; flex-direction:column; gap:10px; margin-top:16px; }}
  button {{ height:56px; border-radius:16px; border:1px solid var(--line); background:#fff; font-size:16px; font-weight:600; cursor:pointer; }}
  button.primary {{ background:var(--accent); color:#fff; border:none; }}
  button.ghost {{ color:var(--sub); }}
</style></head>
<body><div class="banner">실습용 모의 인증이며 실제 본인확인이 아닙니다</div>
<main><div class="card">{body}</div></main></body></html>"""


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self):
        # HTTPServer.server_bind의 역방향 DNS 조회(getfqdn)가 일부 환경에서 오래 멈춰 생략한다
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = "localhost", self.server_address[1]


if __name__ == "__main__":
    if SIGNER is None:
        print("[mock-idp] 경고: 서명 키 없음 → 결과에 서명이 붙지 않아 이용기관 서버가 결과를 거부합니다. "
              "lab/pki/generate-lab-pki.sh 를 먼저 실행하세요.", flush=True)
    server = LocalServer(("127.0.0.1", PORT), Handler)
    print(f"[mock-idp] 실습용 모의 본인확인기관 시작: http://localhost:{PORT} (TTL {TTL_SECONDS}s)", flush=True)
    server.serve_forever()
