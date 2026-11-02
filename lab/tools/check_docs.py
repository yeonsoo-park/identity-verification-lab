#!/usr/bin/env python3
"""
문서-코드 불일치 점검 (사람이 매번 눈으로 대조하던 일을 자동화)

코드에 있는 '사실'이 문서에 빠짐없이 적혀 있는지 확인한다. 하나라도 빠지면 종료코드 1.
  1. 오류·성공 코드 enum → API 명세(04) 또는 제휴사 연동 가이드(20)
  2. 종료 사유 enum       → 정책 문서(03)
  3. 모의 기관 결과코드   → 제휴사 연동 가이드(20)  + 서버의 기관 클라이언트가 해석하는지(코드 존재)
  4. 엔드포인트  → API 명세(04)
  5. lab.* 설정 키        → 운영 매뉴얼(30)

사용: python3 lab/tools/check_docs.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import codebase as cb  # noqa: E402


def doc(name):
    return cb.read(os.path.join("docs", name))


def main():
    problems = []
    api, policy, guide, ops = doc("04-api-spec.md"), doc("03-policy-and-exceptions.md"), \
        doc("20-partner-integration-guide.md"), doc("30-api-operations-manual.md")
    client = cb.read("src/main/java/idvlab/verification/provider/mock/MockIdentityProviderClient.java")

    for rel in ["src/main/java/idvlab/verification/IdentityVerificationErrorCode.java",
                "src/main/java/idvlab/verification/IdentityVerificationSuccessCode.java"]:
        for name, _, _ in cb.result_codes(rel):
            if name not in api and name not in guide:
                problems.append(f"[코드] {name} 이(가) 04-api-spec.md / 20-partner-integration-guide.md 에 없음")

    for name, _ in cb.enum_with_comments("src/main/java/idvlab/verification/entity/VerificationEndReason.java"):
        if name not in policy:
            problems.append(f"[종료사유] {name} 이(가) 03-policy-and-exceptions.md 에 없음")

    for code in cb.mock_codes():
        if code not in guide:
            problems.append(f"[모의 결과코드] {code} 이(가) 20-partner-integration-guide.md 에 없음")
        if code.startswith(("MV0", "MV1")) and f'"{code}"' not in client:
            problems.append(f"[모의 결과코드] {code} 을(를) 서버의 기관 클라이언트가 해석하지 않음 (MockIdentityProviderClient)")

    for verb, path in cb.lab_endpoints():
        shown = path.replace("{verificationId}", "{verificationId}")
        if shown not in api:
            problems.append(f"[엔드포인트] {verb} {path} 이(가) 04-api-spec.md 에 없음")

    for key in cb.lab_config_keys():
        if key not in ops:
            problems.append(f"[설정] {key} 이(가) 30-api-operations-manual.md 에 없음")

    checked = "코드 enum·종료사유·모의 결과코드·lab 엔드포인트·lab 설정 키"
    if problems:
        print(f"문서-코드 불일치 {len(problems)}건 ({checked})")
        for p in problems:
            print("  -", p)
        return 1
    print(f"문서-코드 일치 확인 완료 ({checked})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
