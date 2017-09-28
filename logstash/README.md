# Analyzing Marathon with ELK Stack

Marathon comes with a basic set of grok filter for Logstash to load logs into
Elasticsearch. This should enable users to analyze logs with Kibana.

## Usage

Given you have a running Elasticsearch instance listening on `localhost:9200`
simply run

```
cat <path to Marathon logfile> | logstash -f <path to Marathon repo>/logstash/conf/
```
