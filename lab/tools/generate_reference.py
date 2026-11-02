#!/usr/bin/env python3
"""
코드 → 문서 자동 생성 (수동 문서 동기화 작업을 자동화)

생성물 (docs/identity-verification-lab/generated/, 직접 수정하지 말 것):
  - openapi-identity-verification.json : 실행 중인 서버의 /v3/api-docs에서 /api/v1 경로 추출
  - api-reference.md                   : 위 OpenAPI에서 엔드포인트·응답 코드 표 생성
  - code-catalog.md                    : Java enum·모의 기관 코드표에서 결과코드·종료사유 카탈로그 생성

사용: python3 lab/tools/generate_reference.py [--offline]
      --offline : 서버 없이 code-catalog.md만 생성
"""
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import codebase as cb  # noqa: E402

OUT = os.path.join(cb.DOCS, "generated")
APP = os.environ.get("APP_URL", "http://localhost:8080")
PREFIXES = ("/api/v1/",)
HEADER = "<!-- 자동 생성 파일: lab/tools/generate_reference.py 로 다시 만드세요. 직접 수정 금지 -->\n\n"


def write(name, text):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(text)
    print("생성:", os.path.relpath(os.path.join(OUT, name), cb.ROOT))


def code_catalog():
    lines = [HEADER, "# 코드 카탈로그 (자동 생성)\n"]
    for title, rel in [("앱 ↔ 서버 오류 코드", "src/main/java/idvlab/verification/IdentityVerificationErrorCode.java"),
                       ("앱 ↔ 서버 성공 코드", "src/main/java/idvlab/verification/IdentityVerificationSuccessCode.java")]:
        lines += [f"\n## {title}\n", "| code | HTTP | message |", "|---|---|---|"]
        lines += [f"| `{n}` | {s} | {m} |" for n, s, m in cb.result_codes(rel)]
    lines += ["\n## 인증 시도 종료 사유 (`endReason`)\n", "| 코드 | 설명(코드 주석) |", "|---|---|"]
    lines += [f"| `{n}` | {c} |" for n, c in
              cb.enum_with_comments("src/main/java/idvlab/verification/entity/VerificationEndReason.java")]
    lines += ["\n## 모의 인증기관 결과코드 (`response_code`)\n", "| 코드 | HTTP | 의미 |", "|---|---|---|"]
    lines += [f"| `{c}` | {h} | {m} |" for c, (h, m) in sorted(cb.mock_codes().items())]
    write("code-catalog.md", "\n".join(lines) + "\n")


def openapi():
    with urllib.request.urlopen(APP + "/v3/api-docs", timeout=10) as res:
        spec = json.load(res)
    paths = {p: v for p, v in spec["paths"].items() if p.startswith(PREFIXES)}
    used = json.dumps(paths)
    schemas = {k: v for k, v in spec.get("components", {}).get("schemas", {}).items() if f"/{k}\"" in used}
    sub = {"openapi": spec["openapi"], "info": {**spec["info"], "title": "Identity Verification Lab API"},
           "servers": [{"url": "http://localhost:8080", "description": "로컬 실습 서버"}],
           "paths": paths, "components": {"schemas": schemas, "securitySchemes": spec["components"].get("securitySchemes", {})}}
    write("openapi-identity-verification.json", json.dumps(sub, ensure_ascii=False, indent=2) + "\n")

    lines = [HEADER, "# API 레퍼런스 (자동 생성, OpenAPI 발췌)\n",
             "| 메서드 | 경로 | 요약 | 응답 코드 |", "|---|---|---|---|"]
    for p, ops in sorted(paths.items()):
        for verb, op in ops.items():
            codes = ", ".join(sorted(op.get("responses", {}).keys()))
            lines.append(f"| {verb.upper()} | `{p}` | {op.get('summary', '')} | {codes} |")
    write("api-reference.md", "\n".join(lines) + "\n")


if __name__ == "__main__":
    code_catalog()
    if "--offline" not in sys.argv:
        try:
            openapi()
        except OSError as e:
            print("OpenAPI 생략 (서버 미기동):", e)
            sys.exit(1)
