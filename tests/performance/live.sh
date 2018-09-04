#!/bin/bash
set -x -e -o pipefail

[ -z "$MARATHON_DIR" ] && MARATHON_DIR=$(pwd)
MARATHON_PERF_TESTING_DIR=$(pwd)/marathon-perf-testing

# Bring an up-to-date marathon-perf-testing environment
[ ! -d marathon-perf-testing ] && git clone "https://${GIT_USER}:${GIT_PASS}@github.com/mesosphere/marathon-perf-testing.git"
(cd marathon-perf-testing; git pull --rebase)

# Privileged clean-up of the previous run remnants
docker run -i --rm \
    --privileged \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$MARATHON_DIR:/marathon" \
    icharalampidis/marathon-perf-testing:latest \
    bash -c 'eval rm -rf /marathon/results /marathon/*.tar.gz'

# Configuration
DOCKER_NETWORK=testing
JOB_NAME_SANITIZED=$(echo "$JOB_NAME" | tr -c '[:alnum:]-' '-')

# Get the git hash
GIT_HASH=$(cd "$MARATHON_DIR" && git rev-parse HEAD)
echo "- Running on GIT hash $GIT_HASH..."

# Run the CI job
timeout 3600 docker run -i --rm \
    --network ${DOCKER_NETWORK} \
    --privileged \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$MARATHON_DIR:/marathon" \
    -e "PARTIAL_TESTS=test-continuous-n-apps" \
    -e "PERF_DRIVER_ENVIRONMENT=env-ci-live.yml" \
    -e "DATADOG_API_KEY=$DATADOG_API_KEY" \
    -e "RUN_NAME=$JOB_NAME_SANITIZED" \
    -e "DOCKER_NETWORK=$DOCKER_NETWORK" \
    -e "BUILD_NUMBER=$BUILD_TAG" \
    -e "MARATHON_PERF_TESTING_DIR=$MARATHON_PERF_TESTING_DIR" \
    icharalampidis/marathon-perf-testing:latest \
    ./tests/performance/ci_run.sh \
    -Djmx_host=marathon -Djmx_port=9010 -Dmarathon_url=http://marathon:8080 \
    -Mgit_hash="${GIT_HASH}" || docker rm -f "$(docker ps -aq)" || true

# Docker tends to leave lots of garbage ad the end, so
# we should clean the volumes and remove the marathon
# images that we just created.
docker volume prune -f
docker rmi -f "$(docker images -q --filter "reference=mesosphere/marathon:*")"
