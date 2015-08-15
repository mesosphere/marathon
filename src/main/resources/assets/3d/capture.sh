#!/bin/bash

display_usage() {
  echo -e "\nPlease specify your Marathon address."
  echo -e "\nExample:\n$0 http://marathon.local:8080"
  echo -e "\nPress CTRL-C to stop capturing.\n"
}

if [  $# -le 0 ]
  then
    display_usage
    exit 1
fi

# Ex: http://marathon.local:8080/v2/apps
marathon_api="$1/v2/apps"
# Ex: 20150814-10:28:38.log
filename="$(date '+%Y%m%d-%H:%M:%S').log"

# Start
watch -n 1 "curl -w '\n' '$marathon_api' -H 'Accept: application/json' -H 'Connection: keep-alive' >> $filename"
