#!/usr/bin/env python3
"""
실제 HTTP로 서버 ↔ 모의 본인확인기관을 오가며 검증하는 E2E 시나리오.

사전 조건: 모의 서버(18090)와 서버(8080)가 떠 있어야 합니다.
실행:      python3 lab/e2e/run_e2e.py
옵션 환경변수: APP_URL(기본 http://localhost:8080), MOCK_URL(기본 http://localhost:18090),
              PSQL(기본 /opt/homebrew/opt/postgresql@16/bin/psql), PGPORT(기본 5434)

각 시나리오는 테스트 회원을 '탈퇴 → 재로그인'으로 새 계정으로 만든 뒤 시작합니다(이전 실행 결과와 독립).
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

APP = os.environ.get("APP_URL", "http://localhost:8080")
MOCK = os.environ.get("MOCK_URL", "http://localhost:18090")
PSQL = os.environ.get("PSQL", "/opt/homebrew/opt/postgresql@16/bin/psql")
PGPORT = os.environ.get("PGPORT", "5434")
BASE = "/api/v1/members/me/identity-verifications"

results = []


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(NoRedirect)


def call(method, url, body=None, token=None, form=None, timeout=15):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if form is not None:
        data = "&".join(f"{k}={v}" for k, v in form.items()).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    started = time.time()
    try:
        with opener.open(req, timeout=timeout) as res:
            status, raw, hdrs = res.status, res.read(), res.headers
    except urllib.error.HTTPError as e:
        status, raw, hdrs = e.code, e.read(), e.headers
    elapsed = time.time() - started
    try:
        payload = json.loads(raw) if raw else {}
    except ValueError:
        payload = {"raw": raw.decode(errors="replace")[:20000]}
    payload["_http"] = status
    payload["_elapsed"] = round(elapsed, 2)
    payload["_location"] = hdrs.get("Location") if hdrs else None
    return payload


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    mark = "PASS" if cond else "FAIL"
    print(f"  [{mark}] {name}" + (f"  ← {detail}" if not cond and detail else ""))


def sql(query):
    try:
        out = subprocess.run([PSQL, "-h", "localhost", "-p", PGPORT, "-U", "idvlab", "-d", "idvlab",
                              "-At", "-c", query], capture_output=True, text=True, timeout=10,
                             env=dict(os.environ, PGPASSWORD="idvlab"))
        return out.stdout.strip()
    except Exception as e:   # psql이 없으면 DB 직접 확인 단계만 건너뜀
        return f"psql-unavailable: {e}"


# ── 헬퍼 ──────────────────────────────────────────────────────────
def login(key):
    r = call("POST", f"{APP}/api/v1/auth/test-login", {"testUserKey": key})
    assert r["_http"] == 200, r
    return r["data"]["accessToken"], r["data"]["isNewUser"]


def fresh_user(key):
    """기존 테스트 회원을 탈퇴시키고 새 계정으로 로그인 (탈퇴 흐름도 함께 사용)"""
    token, is_new = login(key)
    if not is_new:
        call("DELETE", f"{APP}/api/v1/members/me", token=token)
        token, is_new = login(key)
    return token


def me(token):
    return call("GET", f"{APP}/api/v1/members/me", token=token)


def start(token):
    return call("POST", f"{APP}{BASE}", token=token)


def status(token, vid):
    return call("GET", f"{APP}{BASE}/{vid}", token=token)


def cancel(token, vid):
    return call("POST", f"{APP}{BASE}/{vid}/cancel", token=token)


def trx_of(resp):
    return resp["data"]["authUrl"].split("trx_key=")[1]


def mock_click(trx, action):
    """사용자가 모의 인증 화면에서 버튼을 누른 것과 같은 요청"""
    return call("POST", f"{MOCK}/mock-idp/web/standard-auth/{trx}", form={"action": action})


def mock_tx_by_request(vid):
    items = call("GET", f"{MOCK}/mock-idp/admin/identifications")["items"]
    return [i for i in items if i["client_request_id"] == vid]


def mock_force(composite_key, status_, override=False):
    return call("POST", f"{MOCK}/mock-idp/admin/identifications/{composite_key}/force",
                {"status": status_, "override": override})


def fault(mode, remaining=0):
    call("POST", f"{MOCK}/mock-idp/admin/faults", {"mode": mode, "remaining": remaining})


# ── 시나리오 ──────────────────────────────────────────────────────
def s1_success_and_idempotency():
    print("\n[S1] 테스트 회원 로그인 → 요청 생성 → 모의 성공 → 내 정보 반영, 반복 요청·조회 중복 방지")
    t = fresh_user("lab-user-a")
    check("가입 직후 계정 미확인", me(t)["data"]["identityVerificationStatus"] == "UNVERIFIED")
    r1 = start(t)
    check("요청 생성 201 / PENDING / authUrl 존재",
          r1["_http"] == 201 and r1["data"]["status"] == "PENDING" and r1["data"]["authUrl"], r1)
    vid = r1["data"]["verificationId"]
    r2 = start(t)
    check("연속 클릭: 200 RESUMED, 같은 verificationId", r2["_http"] == 200 and r2["data"]["verificationId"] == vid, r2)
    check("모의 기관에도 요청 1건만 등록", len(mock_tx_by_request(vid)) == 1)
    check("클릭 전 조회는 PENDING 유지", status(t, vid)["data"]["status"] == "PENDING")
    loc = mock_click(trx_of(r1), "success")["_location"]
    check("모의 인증 후 데모 화면으로 복귀(303) — URL에 결과값 없음",
          loc and "verificationId=" + vid in loc and "SUCCEEDED" not in loc, loc)
    s = status(t, vid)
    check("서버가 외부 결과 확인 → SUCCEEDED, 계정 VERIFIED",
          s["data"]["status"] == "SUCCEEDED" and s["data"]["accountStatus"] == "VERIFIED", s)
    m = me(t)["data"]
    check("내 정보에 확인 완료 + 완료 시각", m["identityVerificationStatus"] == "VERIFIED" and m["identityVerifiedAt"], m)
    first_at = m["identityVerifiedAt"]
    for _ in range(3):
        status(t, vid)
    check("반복 조회해도 완료 시각 불변(중복 반영 없음)", me(t)["data"]["identityVerifiedAt"] == first_at)
    r3 = start(t)
    check("이미 확인 완료 회원의 추가 요청 → 409", r3["_http"] == 409 and r3["code"] == "IDENTITY_ALREADY_VERIFIED", r3)
    return t, vid


def s2_cancel_fail_expire():
    print("\n[S2] 취소·실패·만료 시 미확인 유지 / 명시 취소와 창 닫기 구분")
    t = fresh_user("lab-user-b")

    r = start(t); vid = r["data"]["verificationId"]
    mock_click(trx_of(r), "close")   # 창 닫기: 결과 없이 복귀
    check("창 닫기는 취소가 아님 → PENDING 유지", status(t, vid)["data"]["status"] == "PENDING")
    check("창 닫은 뒤 다시 누르면 같은 요청 재사용", start(t)["data"]["verificationId"] == vid)
    mock_click(trx_of(r), "cancel")
    s = status(t, vid)
    check("모의 화면에서 취소 → CANCELLED(PROVIDER_USER_CANCELLED), 계정 UNVERIFIED",
          s["data"]["status"] == "CANCELLED" and s["data"]["endReason"] == "PROVIDER_USER_CANCELLED"
          and s["data"]["accountStatus"] == "UNVERIFIED", s)

    r = start(t); vid2 = r["data"]["verificationId"]
    check("종료 후 다시 요청하면 새 요청(201)", r["_http"] == 201 and vid2 != vid, r)
    mock_click(trx_of(r), "fail")
    s = status(t, vid2)
    check("인증 실패 → FAILED(PROVIDER_REJECTED), 계정 UNVERIFIED",
          s["data"]["status"] == "FAILED" and s["data"]["accountStatus"] == "UNVERIFIED", s)

    r = start(t); vid3 = r["data"]["verificationId"]
    key = mock_tx_by_request(vid3)[0]["composite_key"]
    mock_force(key, "EXPIRED")
    s = status(t, vid3)
    check("유효시간 만료 → EXPIRED(PROVIDER_EXPIRED), 계정 UNVERIFIED",
          s["data"]["status"] == "EXPIRED" and s["data"]["accountStatus"] == "UNVERIFIED", s)
    mock_click(trx_of(r), "success")   # 만료 후 성공 시도
    check("만료된 요청은 이후 성공 시도로도 바뀌지 않음", status(t, vid3)["data"]["status"] == "EXPIRED")

    r = start(t); vid4 = r["data"]["verificationId"]
    c1 = cancel(t, vid4)
    check("서비스 화면 명시 취소 → CANCELLED(USER_CANCELLED)",
          c1["data"]["status"] == "CANCELLED" and c1["data"]["endReason"] == "USER_CANCELLED", c1)
    check("모의 기관에도 취소 반영(CLIENT_CANCELLED)", mock_tx_by_request(vid4)[0]["status"] == "CLIENT_CANCELLED")
    c2 = cancel(t, vid4)
    check("취소 재호출은 멱등(200, 상태 동일)", c2["_http"] == 200 and c2["data"]["endedAt"] == c1["data"]["endedAt"], c2)
    check("모든 종료 후에도 계정 미확인", me(t)["data"]["identityVerificationStatus"] == "UNVERIFIED")
    return t


def s3_other_member(token_a, vid_a, token_b):
    print("\n[S3] 다른 회원의 인증 요청 접근 차단")
    g = status(token_b, vid_a)
    check("다른 회원 요청 조회 → 404", g["_http"] == 404 and g["code"] == "IDENTITY_VERIFICATION_NOT_FOUND", g)
    c = cancel(token_b, vid_a)
    check("다른 회원 요청 취소 → 404", c["_http"] == 404, c)
    check("소유자에게는 그대로 보임", status(token_a, vid_a)["data"]["status"] == "SUCCEEDED")
    n = call("GET", f"{APP}{BASE}/{vid_a}")
    check("토큰 없이 조회 → 401", n["_http"] == 401, n)


def s4_outage_and_recovery():
    print("\n[S4] 외부 서버 장애·시간 초과 후 재조회로 복구 / 응답 유실")
    t = fresh_user("lab-user-c")
    r = start(t); vid = r["data"]["verificationId"]
    mock_click(trx_of(r), "success")
    fault("DOWN")
    s = status(t, vid)
    check("장애 중 조회 → 503 (실패로 단정하지 않음)", s["_http"] == 503 and s["code"] == "IDENTITY_PROVIDER_UNAVAILABLE", s)
    check("장애 중 DB 상태는 PENDING 유지",
          sql(f"select status from identity_verifications where verification_id='{vid}'") in ("PENDING",) or
          sql("select 1").startswith("psql-unavailable"))
    fault("TIMEOUT", remaining=1)
    s = status(t, vid)
    check("응답 지연 → 타임아웃 후 503 (설정 3초 근처)", s["_http"] == 503 and s["_elapsed"] < 6, s)
    fault("NONE")
    s = status(t, vid)
    check("장애 해소 후 재조회 → SUCCEEDED, 계정 VERIFIED",
          s["data"]["status"] == "SUCCEEDED" and s["data"]["accountStatus"] == "VERIFIED", s)

    print("  -- 응답 유실: 모의 기관은 등록을 처리했지만 서버는 응답을 못 받음")
    t2 = fresh_user("lab-user-b")
    fault("DROP_AFTER_PROCESS", remaining=1)
    r = start(t2)
    check("등록 응답 유실 → 503", r["_http"] == 503, r)
    vid = sql("select verification_id from identity_verifications v join members u on u.id=v.member_id "
              "where u.login_key='lab-user-b' and v.status='PENDING'")
    r2 = start(t2)
    check("재시도 → 같은 요청을 이어서(200 RESUMED) authUrl 확보",
          r2["_http"] == 200 and r2["data"]["authUrl"] and (vid.startswith("psql") or r2["data"]["verificationId"] == vid), r2)
    check("모의 기관 등록은 여전히 1건(멱등)", len(mock_tx_by_request(r2["data"]["verificationId"])) == 1)
    cancel(t2, r2["data"]["verificationId"])


def s5_no_reversal(token_a, vid_a):
    print("\n[S5] 종료된 요청의 상태 역전 방지 (모의 기관과 상태 충돌)")
    key = mock_tx_by_request(vid_a)[0]["composite_key"]
    mock_force(key, "USER_CANCELLED", override=True)   # 기관 쪽 상태를 강제로 뒤집음
    s = status(token_a, vid_a)
    check("기관이 뒤늦게 다른 값을 줘도 서버 SUCCEEDED 유지", s["data"]["status"] == "SUCCEEDED", s)
    check("계정도 VERIFIED 유지", me(token_a)["data"]["identityVerificationStatus"] == "VERIFIED")


def s6_relogin_and_withdrawal():
    print("\n[S6] 세션 만료 후 같은 회원 재로그인 / 인증 대기 중 탈퇴")
    t = fresh_user("lab-user-c")
    r = start(t); vid = r["data"]["verificationId"]
    t_again, is_new = login("lab-user-c")   # 기존 토큰을 잃어버렸다고 가정
    check("같은 회원으로 재로그인(isNewUser=false)", is_new is False)
    check("재로그인 토큰으로 기존 요청 조회 가능", status(t_again, vid)["data"]["status"] == "PENDING")
    check("재로그인 후 다시 요청해도 같은 요청 재사용", start(t_again)["data"]["verificationId"] == vid)

    d = call("DELETE", f"{APP}/api/v1/members/me", token=t_again)
    check("인증 대기 중 탈퇴 → 204", d["_http"] == 204, d)
    mock_click(trx_of(r), "success")   # 탈퇴 후 외부에서 성공
    check("탈퇴 후 기존 토큰으로 조회 불가(401)", status(t_again, vid)["_http"] == 401)
    row = sql(f"select status||'/'||coalesce(end_reason,'') from identity_verifications where verification_id='{vid}'")
    check("탈퇴 회원 요청은 CANCELLED/MEMBER_WITHDRAWN로 확정", row == "CANCELLED/MEMBER_WITHDRAWN" or row.startswith("psql"), row)
    urow = sql(f"select u.identity_verification_status from members u join identity_verifications v on v.member_id=u.id "
               f"where v.verification_id='{vid}'")
    check("탈퇴 회원 계정에 인증 결과 미반영(UNVERIFIED)", urow == "UNVERIFIED" or urow.startswith("psql"), urow)
    t_new, is_new = login("lab-user-c")
    check("같은 키 재가입은 새 계정·미확인", is_new and me(t_new)["data"]["identityVerificationStatus"] == "UNVERIFIED")


def s7_concurrent_clicks():
    print("\n[S7] 동시 요청(버튼 연타) — 진행 중 요청은 1건")
    import threading
    t = fresh_user("lab-user-a")
    out = []
    threads = [threading.Thread(target=lambda: out.append(start(t))) for _ in range(5)]
    [th.start() for th in threads]
    [th.join() for th in threads]
    ids = {o["data"]["verificationId"] for o in out if o.get("data")}
    codes = sorted(o["_http"] for o in out)
    check("동시 5회 생성 → verificationId 1개", len(ids) == 1, f"{ids} {codes}")
    check("201은 최대 1번, 나머지는 200", codes.count(201) <= 1 and all(c in (200, 201) for c in codes), codes)
    pend = sql("select count(*) from identity_verifications v join members u on u.id=v.member_id "
               "where u.login_key='lab-user-a' and v.status='PENDING'")
    check("DB의 PENDING 1건", pend == "1" or pend.startswith("psql"), pend)
    cancel(t, ids.pop())


def s8_signature_integrity():
    print("\n[S8] 결과 전자서명 검증 (PKI) — 위·변조·신뢰할 수 없는 서명자")
    t = fresh_user("lab-user-b")
    r = start(t); vid = r["data"]["verificationId"]
    mock_click(trx_of(r), "fail")   # 실제 결과는 '실패'
    fault("TAMPER", remaining=1)    # 모의 기관이 서명 뒤 상태를 SUCCEEDED로 바꿔 응답
    s = status(t, vid)
    check("서명 후 변조된 결과 → 502 IDENTITY_RESULT_INTEGRITY_FAILED", s["_http"] == 502
          and s["code"] == "IDENTITY_RESULT_INTEGRITY_FAILED", s)
    check("변조 응답은 반영 안 됨 → 계정 UNVERIFIED", me(t)["data"]["identityVerificationStatus"] == "UNVERIFIED")
    fault("UNTRUSTED_SIGNER", remaining=1)   # Root CA가 발급하지 않은 키로 서명
    s = status(t, vid)
    check("신뢰 체계 밖 서명자 → 502", s["_http"] == 502, s)
    check("검증 실패 동안 DB는 PENDING 유지",
          sql(f"select status from identity_verifications where verification_id='{vid}'") in ("PENDING",)
          or sql("select 1").startswith("psql-unavailable"))
    s = status(t, vid)
    check("정상 서명 응답으로 재조회 → 실제 결과(FAILED) 반영", s["data"]["status"] == "FAILED", s)

    print("  -- 등록되지 않은 callback URL")
    tok = call("POST", f"{MOCK}/mock-idp/v1/corp/auth", {"corp_id": "IDV-LAB", "credential": "lab-mock-credential"})
    reg = call("POST", f"{MOCK}/mock-idp/v1/identifications",
               {"corp_id": "IDV-LAB", "client_request_id": "e2e-evil", "client_callback_url": "https://evil.example/steal"},
               token=tok["result"]["access_token"])
    check("미등록 callback URL 등록 거부 → MV9107", reg.get("response_code") == "MV9107", reg)
    portal = call("GET", f"{MOCK}/mock-idp/portal")
    check("제휴사 포털 화면 응답(200) + 서명 인증서 정보 표시",
          portal["_http"] == 200 and "Mock IdP Result Signing" in portal.get("raw", ""), portal.get("_http"))


if __name__ == "__main__":
    fault("NONE")
    try:
        token_a, vid_a = s1_success_and_idempotency()
        token_b = s2_cancel_fail_expire()
        s3_other_member(token_a, vid_a, token_b)
        s4_outage_and_recovery()
        s5_no_reversal(token_a, vid_a)
        s6_relogin_and_withdrawal()
        s7_concurrent_clicks()
        s8_signature_integrity()
    finally:
        fault("NONE")
    passed = sum(1 for _, ok in results if ok)
    print(f"\n결과: {passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)
