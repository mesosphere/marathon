#!/bin/bash

if [[ "$1" == *fail* ]]
then
  echo "net services down"
  # drop standard mesos traffic
  sudo iptables -A OUTPUT -m comment --comment "remove_me" -p tcp --destination-port 5050 -j DROP
  sudo iptables -A OUTPUT -m comment --comment "remove_me" -p tcp --destination-port 5051 -j DROP
  sudo iptables -A OUTPUT -m comment --comment "remove_me" -p tcp --destination-port 2181 -j DROP
else
  echo "and we are back"
  sudo iptables-save | grep -v "remove_me" | sudo iptables-restore
fi
