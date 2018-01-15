#!/bin/bash

HOST=${1:-localhost:9200}
INDICES=$(curl ${HOST}/_all | jq '. | keys[]' -r | grep logstash)

echo "Detected the following indices:"
echo "$INDICES"

echo "$INDICES" | while read index; do
  if [ -z "$index" ]; then
    break
  fi
  echo "Deleting ${index}:"
  echo
  curl -X  DELETE ${HOST}/$index
  echo

done
