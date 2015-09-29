#!/bin/bash

/etc/init.d/zookeeper start
cd /marathon
screen -m -d -S testing -L sbt 'run --zk zk://localhost:2181/marathon --master local'

i=0
while ! grep "Scheduler actor ready" screenlog.0
do
        echo "Waiting for Marathon to boot up..."
        if [[ $i -gt 600 ]]; then
                exit 1
        else
                sleep 5
                let "i++"
        fi
done

cd /dcos-cli/cli
py.test tests/integrations/test_marathon.py && py.test tests/integrations/test_marathon_groups.py
