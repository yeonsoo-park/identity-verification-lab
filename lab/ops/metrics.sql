-- 본인확인 운영 지표 쿼리 (API 운영 매뉴얼 §3.2 참고)
-- 실행: psql -h localhost -p 5434 -U idvlab -d idvlab -f lab/ops/metrics.sql
-- 기간 조건은 :since 변수로 조정 (기본 최근 7일)
\set since '''7 days'''

\echo '[M1] 시도 퍼널: 생성 → 외부 등록 → 종료 상태별 (기획 KPI: 시도 대비 성공률)'
SELECT count(*)                                                        AS attempts,
       count(*) FILTER (WHERE provider_transaction_id IS NOT NULL)     AS registered,
       count(*) FILTER (WHERE status = 'SUCCEEDED')                    AS succeeded,
       count(*) FILTER (WHERE status = 'FAILED')                       AS failed,
       count(*) FILTER (WHERE status = 'CANCELLED')                    AS cancelled,
       count(*) FILTER (WHERE status = 'EXPIRED')                      AS expired,
       count(*) FILTER (WHERE status = 'PENDING')                      AS pending,
       round(100.0 * count(*) FILTER (WHERE status = 'SUCCEEDED') / nullif(count(*), 0), 1) AS success_rate_pct
FROM identity_verifications
WHERE created_at >= now() - interval :since;

\echo '[M2] 종료 사유 분포 (이탈 원인 분석: 사용자 취소 vs 실패 vs 만료 vs 장애)'
SELECT status, coalesce(end_reason, '-') AS end_reason, count(*) AS cnt,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
FROM identity_verifications
WHERE created_at >= now() - interval :since AND status <> 'PENDING'
GROUP BY status, end_reason
ORDER BY cnt DESC;

\echo '[M3] 성공까지 걸린 시간 (초): 중앙값·p90 — 유효시간(실습 가정 5분) 적정성 판단'
SELECT count(*) AS succeeded,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM ended_at - created_at))::numeric, 1) AS p50_sec,
       round(percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM ended_at - created_at))::numeric, 1) AS p90_sec
FROM identity_verifications
WHERE status = 'SUCCEEDED' AND created_at >= now() - interval :since;

\echo '[M4] 계정 본인확인 현황 (활성 회원 기준)'
SELECT identity_verification_status, count(*) AS members,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
FROM members
WHERE status = 'ACTIVE'
GROUP BY identity_verification_status;

\echo '[M5] 점검 대상: 유효시간이 지났는데 아직 PENDING (외부 장애·미조회 의심, 0건이 정상)'
SELECT verification_id, member_id, provider_transaction_id IS NOT NULL AS registered, expires_at,
       round(extract(epoch FROM now() - expires_at) / 60) AS minutes_overdue
FROM identity_verifications
WHERE status = 'PENDING' AND expires_at < now()
ORDER BY expires_at;

\echo '[M6] 일별 추이'
SELECT date_trunc('day', created_at)::date AS day, count(*) AS attempts,
       count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded
FROM identity_verifications
WHERE created_at >= now() - interval :since
GROUP BY 1 ORDER BY 1;
