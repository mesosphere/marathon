# Analyzing Marathon with ELK Stack

Marathon comes with a basic set of grok filter for Logstash to load logs into
Elasticsearch. This should enable users to analyze logs with Kibana.

## Usage

Given you have a running Elasticsearch instance listening on `localhost:9200`
simply run

```
bin/target.sc <path-to-diagnostic-bundle>

bin/clear-indices.sh
logstash -f target/loading
```

The target script will automatically detect masters in the bundle, unzip the files, and

Then, you can run Kibana and analyze, etc.
