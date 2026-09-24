#!/bin/sh
# ============================================================
#  OO공단 방화벽 (학습용)
#
#  진짜 방화벽처럼 동작한다. iptables 로 패킷을 실제로 버린다.
#
#  policy.conf = 방화벽 담당자가 '신청서에 적힌 그대로' 넣은 허용 정책.
#  ★ 방화벽 담당자는 신청서를 해석하지 않는다. 적힌 대로 넣을 뿐이다.
# ============================================================
set -e

iptables -F INPUT
iptables -A INPUT -i lo -j ACCEPT
iptables -A INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

echo "[방화벽] 허용 정책 적용 (policy.conf)"
grep -v '^[[:space:]]*#' /policy/policy.conf | while read -r src dst port proto rest; do
  [ -z "$src" ] && continue
  iptables -A INPUT -p tcp -s "$src" --dport "$port" -j ACCEPT
  echo "  ACCEPT  $src -> $dst:$port/$proto"
done

# 기관망에서 들어오는 나머지 새 연결은 '아무 말 없이' 버린다.
# REJECT 가 아니라 DROP 이다. 공공기관 방화벽은 대부분 이렇다.
iptables -A INPUT -s 10.20.30.0/24 -p tcp -j DROP
echo "  DROP    그 외 기관망(10.20.30.0/24) 에서 오는 모든 새 연결"

# AI 서비스 VIP : 443 으로 들어온 요청을 내부 AI 서버로 넘긴다.
socat TCP-LISTEN:443,fork,reuseaddr TCP:aisvc:8000 &
echo "[방화벽] VIP 10.20.30.100:443 -> aisvc:8000 전달 시작"
wait
