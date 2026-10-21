#!/usr/bin/env bash
# Mac에서 private EKS API에 붙는 SSM 터널.
#
#   ./bin/lt-tunnel.sh find               # 배스천 instance-id 찾기
#   ./bin/lt-tunnel.sh channel            # 터미널 하나를 차지한다(Ctrl+C로 끊음). 끊기면 다시 실행
#   ./bin/lt-tunnel.sh corebank           # 다른 터미널에서
#
# 하는 일: (1) aws eks update-kubeconfig로 context(lt-channel/lt-corebank)를 만들고
#          (2) 그 context의 서버 주소를 https://127.0.0.1:<포트>로 바꾸고 TLS 이름은 원래 엔드포인트로 둔 뒤
#          (3) 배스천을 거쳐 EKS 엔드포인트 443으로 가는 SSM 포트 포워딩을 연다.
# 필요: awscli v2, session-manager-plugin, AWS 자격(EKS access entry가 있는 사용자).
# develop(corebank)는 밤에 SSM 엔드포인트가 꺼진다(ADR-0035): 평일 낮에만 된다.
source "$(dirname "${BASH_SOURCE[0]}")/lt-env.sh"

case "${1:-}" in
  find)
    aws ec2 describe-instances --region "$AWS_REGION" \
      --filters "Name=tag:Name,Values=*astion*" "Name=instance-state-name,Values=running" \
      --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==`Name`]|[0].Value,VpcId,PrivateIpAddress]' \
      --output table
    echo "VPC 10.10.x = channel, 10.20.x = corebank(dev-bastion). 찾은 id를 LT_CHANNEL_BASTION / LT_COREBANK_BASTION에 넣는다."
    exit 0
    ;;
  channel)
    cluster="$LT_CHANNEL_CLUSTER" ctx="$LT_CHANNEL_CTX" port="$LT_CHANNEL_API_PORT"
    bastion="$LT_CHANNEL_BASTION" var=LT_CHANNEL_BASTION prom_port=9090
    ;;
  corebank)
    cluster="$LT_COREBANK_CLUSTER" ctx="$LT_COREBANK_CTX" port="$LT_COREBANK_API_PORT"
    bastion="$LT_COREBANK_BASTION" var=LT_COREBANK_BASTION prom_port=9091
    ;;
  *)
    echo "사용법: $0 find | channel | corebank" >&2
    exit 2
    ;;
esac

if [[ -z "$bastion" ]]; then
  echo "배스천 instance-id가 없다. '$0 find'로 찾아 export $var=i-... 후 다시 실행한다." >&2
  exit 2
fi

endpoint="$(aws eks describe-cluster --region "$AWS_REGION" --name "$cluster" --query cluster.endpoint --output text)"
arn="$(aws eks describe-cluster --region "$AWS_REGION" --name "$cluster" --query cluster.arn --output text)"
host="${endpoint#https://}"

aws eks update-kubeconfig --region "$AWS_REGION" --name "$cluster" --alias "$ctx" >/dev/null
kubectl config set-cluster "$arn" --server="https://127.0.0.1:$port" --tls-server-name="$host" >/dev/null

cat <<EOF
터널: localhost:$port -> $bastion -> $host:443
이 터미널은 열어 둔다. 다른 터미널에서 확인:
  kubectl --context $ctx get nodes
Grafana/Prometheus는 이 터널 위에서 kubectl port-forward로 연다(런북 §3.4):
  kubectl --context $ctx -n monitoring port-forward svc/ls-observe-grafana 3000:80
  kubectl --context $ctx -n monitoring port-forward svc/ls-observe-prometheus $prom_port:9090
EOF

exec aws ssm start-session --region "$AWS_REGION" --target "$bastion" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$host\"],\"portNumber\":[\"443\"],\"localPortNumber\":[\"$port\"]}"
