#!/bin/bash

# See http://mesos.apache.org/documentation/latest/port-mapping-isolator/
# and mesosphere.util.PortAllocator docs.
echo "Ephemeral port range before: $(cat /proc/sys/net/ipv4/ip_local_port_range)"
sysctl -w net.ipv4.ip_local_port_range="60001 61000"
echo "Ephemeral port range after: $(cat /proc/sys/net/ipv4/ip_local_port_range)"
