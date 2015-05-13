#
# Configuration tempalte for run-tests.sh
#
# Copy to run-tests-config.sh and adjust to your needs.
#


# Where do you want to put your persistent build data by default?
# Per default all other build directories will get stored sub directories of this.
# If you adjust them all, you do not need this setting
#BUILD_VOLUME_DIR="$PROJECT_DIR/docker-volumes"

# Where do you want to store you SBT config/cache?
# You can configure your ~/.sbt here but be aware of permission issues
# since the build is running as root inside of the container.
SBT_DIR="$HOME/.sbt"
# Where do you want to store your IVY2 config/cache?
# You can configure your ~/.ivy2 here but be aware of permission issues
# since the build is running as root inside of the container.
IVY2_DIR="$HOME/.ivy2"
# Where do you want to store build artifacts?
#TARGET_DIRS="..."
# Do you want to clean the target directories before the build?
# Not deleting them results in faster incremental builds but incremental builds might not be reproducible.
CLEANUP_TARGET_DIRS="${CLEANUP_TARGET_DIRS-false}"
# Do you want to remove all generated images/containers on exit?
CLEANUP_CONTAINERS_ON_EXIT="${CLEANUP_CONTAINERS-false}"
# Do you want to NOT use the docker cache?
NO_DOCKER_CACHE="${NO_DOCKER_CACHE-false}"

# A parameter for the AppScalingTest
export MARATHON_MAX_TASKS_PER_OFFER="${MARATHON_MAX_TASKS_PER_OFFER-1}"
