#!/usr/bin/env bash
# 로컬 실습 환경 한 번에 띄우기
#   1) 로컬 PostgreSQL 확인  2) 실습 DB 생성  3) 실습 PKI 생성  4) 모의 인증기관(18090) 기동
#   5) 서버(8080) 기동  6) (선택) E2E 시나리오 실행
#
# 사용:  ./lab/run-lab.sh          # 서버 2개 기동 후 대기 (Ctrl+C로 종료)
#        ./lab/run-lab.sh --e2e    # 기동 → E2E 실행 → 종료
#        ./lab/run-lab.sh --test   # JUnit 테스트만 실행 (서버 불필요, DB만 필요)
set -euo pipefail
cd "$(dirname "$0")/.."

PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
PGPORT="${PGPORT:-5434}"
# Java 21: JAVA_HOME_21 → Homebrew openjdk@21 → 현재 JAVA_HOME 순서로 사용
if [ -n "${JAVA_HOME_21:-}" ]; then
  export JAVA_HOME="$JAVA_HOME_21"
elif [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
PYTHON="${PYTHON:-$(command -v python3.12 || command -v python3)}"
LOG_DIR="${LOG_DIR:-build/lab-logs}"
mkdir -p "$LOG_DIR"

step() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }

step "로컬 PostgreSQL(:$PGPORT) 확인"
"$PG_BIN/pg_isready" -h localhost -p "$PGPORT" >/dev/null || { echo "PostgreSQL이 :$PGPORT 에 없습니다. docs/06-run-and-verify.md 의 설치 절차를 확인하세요."; exit 1; }

step "실습 DB 준비 (idvlab, idvlab_test) — 이미 있으면 건너뜀"
psql_admin() { "$PG_BIN/psql" -h localhost -p "$PGPORT" -d postgres -Atc "$1"; }
[ "$(psql_admin "select 1 from pg_roles where rolname='idvlab'")" = "1" ] || psql_admin "create role idvlab login password 'idvlab'"
for db in idvlab idvlab_test; do
  [ "$(psql_admin "select 1 from pg_database where datname='$db'")" = "1" ] || psql_admin "create database $db owner idvlab"
done

step "실습용 PKI 준비 (lab/pki/out, 이미 있으면 건너뜀)"
lab/pki/generate-lab-pki.sh >/dev/null && echo "  Lab Root CA / 결과 서명 인증서 준비됨"

if [ "${1:-}" = "--test" ]; then
  step "JUnit 테스트 실행"
  ./gradlew test
  exit $?
fi

cleanup() {
  step "종료"
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
}
trap cleanup EXIT

step "모의 인증기관 기동 → http://localhost:18090 (로그: $LOG_DIR/mock.log)"
"$PYTHON" -u lab/mock-identity-provider/server.py >"$LOG_DIR/mock.log" 2>&1 &
MOCK_PID=$!

step "서버 기동 → http://localhost:8080 (로그: $LOG_DIR/app.log)"
./gradlew bootRun -q >"$LOG_DIR/app.log" 2>&1 &
APP_PID=$!

for i in $(seq 1 90); do
  if curl -sf -m 2 localhost:8080/actuator/health >/dev/null && curl -sf -m 2 localhost:18090/health >/dev/null; then
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then echo "서버가 종료됐습니다. $LOG_DIR/app.log 확인"; exit 1; fi
  sleep 2
done
curl -sf -m 2 localhost:8080/actuator/health >/dev/null || { echo "기동 시간 초과. $LOG_DIR/app.log 확인"; exit 1; }

cat <<'EOF'

  준비 완료
  - Swagger   : http://localhost:8080/swagger-ui/index.html  (태그: 실습용 테스트 로그인, 본인확인)
  - 데모 화면  : http://localhost:8080/lab/identity-demo
  - 모의 기관  : http://localhost:18090/health
  - 제휴사 포털: http://localhost:18090/mock-idp/portal
EOF

if [ "${1:-}" = "--e2e" ]; then
  step "E2E 시나리오 실행"
  "$PYTHON" lab/e2e/run_e2e.py
  exit $?
fi

echo "  Ctrl+C로 두 서버를 함께 종료합니다."
wait "$APP_PID"
