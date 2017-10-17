#!/bin/bash

# Script which returns your primary IP; works on Mac OS X and Linux
#

case $(uname) in
  Darwin)
    ifconfig $(route -n get default | grep interface | awk '{print $2}') | egrep "inet\b" | awk '{print $2}'
    ;;
  Linux)
    ip -o addr | egrep -v 'inet6|/32' | grep global | head -1 | awk '{print $4}' | cut -f1 -d/
    ;;
  *)
    # Unknown operating system; default to 127.0.0.1
    echo "127.0.0.1"
esac
