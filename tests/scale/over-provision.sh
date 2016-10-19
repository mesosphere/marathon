
# This script replaces the 'mesos-resources' file on a mesos agent node
# with a configuration of 100 cpus.
echo "MESOS_RESOURCES='[{\"ranges\": {\"range\": [{\"end\": 2180, \"begin\": 1025}, {\"end\": 3887, \"begin\": 2182}, {\"end\": 5049, \"begin\": 3889}, {\"end\": 8079, \"begin\": 5052}, {\"end\": 8180, \"begin\": 8082}, {\"end\": 32000, \"begin\": 8182}]}, \"name\": \"ports\", \"type\": \"RANGES\"}, {\"name\": \"disk\", \"type\": \"SCALAR\", \"role\": \"*\", \"scalar\": {\"value\": 35577}},{\"name\":\"cpus\",\"type\":\"SCALAR\",\"scalar\":{\"value\":100}}]'" | sudo tee /var/lib/dcos/mesos-resources
