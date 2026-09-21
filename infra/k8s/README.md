# k8s (Phase 5)

로컬 k3s 기준. ADR-09: `api` 와 `worker` Deployment 분리, HPA 는 `api` 만.

예정 매니페스트:
- `namespace.yaml`
- `configmap.yaml`, `secret.yaml` (datasource, redis, kafka, toss 키)
- `api-deployment.yaml`, `api-service.yaml`, `api-hpa.yaml` (CPU 60% + 커스텀 메트릭 `groupbuy_participation_tps`)
- `worker-deployment.yaml` (replicas = Kafka 파티션 수 이하로 고정)
- `ingress.yaml`
- `monitoring/` (kube-prometheus-stack values)

부하 기반 자동 확장 실험: `kubectl get hpa -w` 로 2→6 확장, 종료 후 축소 확인 (Phase 5 완료 기준).
