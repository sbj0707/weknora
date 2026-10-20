set -euo pipefail

# 1. 从最新源码构建两个镜像
cd /home/azureuser/projects/weknora
release="$(git rev-parse --short=12 HEAD)"

docker build \
  --build-arg GOPROXY_ARG=https://proxy.golang.org,direct \
  --build-arg COMMIT_ID_ARG="$release" \
  -f docker/Dockerfile.app \
  -t "weknora-local/app:$release" .

docker build \
  --build-arg VITE_FRONTEND_COMMIT="$release" \
  -t "weknora-local/frontend:$release" \
  frontend/

# 2. 沿用现有生产配置
cd /home/azureuser/runner/WeKnora

# 保留旧镜像
docker tag "$(docker inspect -f '{{.Image}}' WeKnora-app)" \
  weknora-local/app:previous
docker tag "$(docker inspect -f '{{.Image}}' WeKnora-frontend)" \
  weknora-local/frontend:previous

# 仅覆盖两个服务的镜像
cat > docker-compose.code.yml <<YAML
services:
  app:
    image: weknora-local/app:$release
  frontend:
    image: weknora-local/frontend:$release
YAML

# 3. 仅替换 app、frontend
docker compose -p weknora \
  -f docker-compose.yml \
  -f docker-compose.code.yml \
  up -d --no-deps --no-build --pull never \
  --wait --wait-timeout 180 \
  app frontend

# 4. 检查
curl -fsS http://127.0.0.1:18080/health
curl -fsS -o /dev/null -w 'frontend HTTP %{http_code}\n' \
  http://127.0.0.1:180/