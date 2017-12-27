#!/bin/bash

curl ${1:-localhost:9200}/_all | jq '. | keys[]' -r | grep logstash | while read index; do echo http DELETE :9200/$index; done | bash
