#!/usr/bin/env bash
# 실습용 PKI 생성 — 실제 인증기관·공동인증서·금융인증서와 무관한 로컬 테스트 키입니다.
#
#   Lab Root CA (신뢰 기준점, trust anchor)
#     └─ Mock IdP Result Signing (모의 기관이 결과에 전자서명할 때 쓰는 인증서, keyUsage=digitalSignature)
#   Rogue Signer (Root CA가 발급하지 않은 자체 서명 인증서 — '신뢰할 수 없는 서명자' 시나리오용)
#
# 사용: lab/pki/generate-lab-pki.sh [출력 디렉터리]   (기본: lab/pki/out, gitignore 대상)
set -euo pipefail
OUT="${1:-$(dirname "$0")/out}"
mkdir -p "$OUT"
cd "$OUT"
if [ -f mock-idp-signing.crt ] && [ -z "${FORCE:-}" ]; then
  echo "이미 존재: $OUT (다시 만들려면 FORCE=1)"; exit 0
fi

cat > ext.cnf <<'CNF'
[root]
basicConstraints = critical, CA:true
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
[signer]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = codeSigning
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
CNF

# 1) Root CA (실습용 10년)
openssl genrsa -out lab-root-ca.key 2048 2>/dev/null
openssl req -x509 -new -key lab-root-ca.key -sha256 -days 3650 \
  -subj "/C=KR/O=IDV Lab (NOT A REAL CA)/CN=IDV Lab Root CA" \
  -extensions root -config <(cat /etc/ssl/openssl.cnf 2>/dev/null; cat ext.cnf) \
  -out lab-root-ca.crt

# 2) 결과 서명용 인증서 (실습용 1년)
openssl genrsa -out mock-idp-signing.key 2048 2>/dev/null
openssl req -new -key mock-idp-signing.key \
  -subj "/C=KR/O=IDV Lab (NOT A REAL CA)/CN=Mock IdP Result Signing" -out mock-idp-signing.csr
openssl x509 -req -in mock-idp-signing.csr -CA lab-root-ca.crt -CAkey lab-root-ca.key -CAcreateserial \
  -sha256 -days 365 -extfile ext.cnf -extensions signer -out mock-idp-signing.crt 2>/dev/null

# 3) 신뢰 체계 밖의 서명자 (자체 서명)
openssl genrsa -out rogue-signing.key 2048 2>/dev/null
openssl req -x509 -new -key rogue-signing.key -sha256 -days 365 \
  -subj "/C=KR/O=Unknown/CN=Mock IdP Result Signing" -out rogue-signing.crt

rm -f mock-idp-signing.csr ext.cnf lab-root-ca.srl
openssl verify -CAfile lab-root-ca.crt mock-idp-signing.crt
echo "생성 완료: $OUT"
