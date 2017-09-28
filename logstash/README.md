# Analyzing Marathon with ELK Stack

Marathon comes with a basic set of grok filter for Logstash to load logs into
Elasticsearch. This should enable users to analyze logs with Kibana.

## Usage

Given you have Docker installed simply run

```
TAG=5.5.2 DCOS_LOG_BUNDLE="<path to dcos-marathon>" docker-compose up
```

Docker ELK stack was forked from [elastic/stack-docker](https://github.com/elastic/stack-docker).
