#!/bin/bash

function newOutput {
  date +"%Y-%m-%d_%H-%M-%S.log"
}

MYDIR=`dirname "$0"`
cd "$MYDIR/.."

set -x

CONTAINER_SUFFIX=`date +"%Y-%m-%d_%H-%M-%S"`-$RANDOM

while true ; do
  mkdir -p target/itests/{running,passed,failed}
  OUTPUT=`newOutput`
  ./bin/run-tests.sh $CONTAINER_SUFFIX 2>&1 | tee "target/itests/running/$OUTPUT"
  if [ $PIPESTATUS -eq 0 ]; then
    mv ./target/itests/{running,passed}/"$OUTPUT"
  else
    mv ./target/itests/{running,failed}/"$OUTPUT"
  fi
done
