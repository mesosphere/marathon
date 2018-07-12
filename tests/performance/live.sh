#!/bin/bash
set -e

MARATHON_DIR=$(pwd)
MARATHON_PERF_TESTING_DIR=$(pwd)/marathon-perf-testing

# Bring an up-to-date marathon-perf-testing environment
[ ! -d marathon-perf-testing ] && git clone "https://${GIT_USER}:${GIT_PASS}@github.com/mesosphere/marathon-perf-testing.git"
(cd marathon-perf-testing; git pull --rebase)

# Remove data from (possible) previous runs
rm -rf results
rm -f marathon-dcluster-*.log.gz

# Privileged clean-up of the results folder
docker run -i --rm \
    --privileged \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$MARATHON_DIR:/marathon" \
    icharalampidis/marathon-perf-testing:latest \
    rm -rf /marathon/results

# Configuration
DOCKER_NETWORK=testing

# Get the git hash
GIT_HASH=$(cd "$MARATHON_DIR" && git rev-parse HEAD)
echo "- Running on GIT hash $GIT_HASH..."

# Run the CI job
docker run -i --rm \
    --network ${DOCKER_NETWORK} \
    --privileged \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$MARATHON_DIR:/marathon" \
    -e "PARTIAL_TESTS=test-continuous-n-apps" \
    -e "PERF_DRIVER_ENVIRONMENT=env-ci-live.yml" \
    -e "DATADOG_API_KEY=$DATADOG_API_KEY" \
    -e DCLUSTER_ARGS="--docker_network='${DOCKER_NETWORK}' --marathon_jmx_host=marathon_1 --share_folder=${MARATHON_PERF_TESTING_DIR}/files" \
    icharalampidis/marathon-perf-testing:latest \
    ./tests/performance/ci_run_dcluster.sh \
    -Djmx_host=marathon_1 -Djmx_port=9010 -Dmarathon_url=http://marathon_1:8080 \
    -Mgit_hash="${GIT_HASH}"

# Docker tends to leave lots of garbage ad the end, so
# we should clean the volumes and remove the marathon
# images that we just created.
docker volume prune -f
docker rmi -f $(docker images -q --filter "reference=mesosphere/marathon:*")
