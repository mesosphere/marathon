#!/bin/bash
#

# This setup assumes you started with the mesos source and installed the binaries into
# the ./build directory of the mesos source. Modify this as needed.

FRAMEWORK_HOME=`dirname $0`/..

# TODO(FL): ensure this script runs on *nix as well.
# TODO(FL): clean-up!
echo "This script is setup to run on MacOSX right now. Modify it to run on other systems."
MESOS_HOME=/Users/tobi/code/mesos/build
echo "MESOS_HOME is set to: $MESOS_HOME"
pushd $MESOS_HOME
libmesos_file=$(find . -name libmesos.dylib -or -name libmesos.so | head -n1)
build_env=$(find . -name "mesos-build-env.sh" | head -n1)
export MESOS_NATIVE_LIBRARY="${MESOS_HOME}/${libmesos_file}"
echo "MESOS_NATIVE_LIBRARY set to $MESOS_NATIVE_LIBRARY"
echo "Sourcing mesos-build-env.sh: $build_env"
source $build_env
popd

# Start Marathon
java -cp "$FRAMEWORK_HOME"/target/marathon-*.jar mesosphere.marathon.Main --http_port 8080 --master zk://localhost:2181/mesos
