# Analyzing Marathon with ELK Stack

Marathon comes with a basic set of grok filter for Logstash to load logs into
Elasticsearch. This should enable users to analyze logs with Kibana.

## Generating LogStash config

### DCOS Bundle

LogStash config can be generated for a DCOS bundle using the following command:

```
bin/target.sc <path-to-diagnostic-bundle>
```

Command will attempt to detect log line format (include date, etc.), and generate the appropriate LogStash input
directives such that the hostname field will be populated in ElasticSearch.

## Installing ElasticSearch, Kibana, and LogStash

These tools can be easily installed with Homebrew:

```
brew install elasticsearch
brew install kibana
brew install logstash
```

## Running ElasticSearch, Kibana, and LogStash

In a separate terminal EACH, run the following commands, in this order:

```
elasticsearch

kibana

logstash -f target/loading
```

Logstash will begin populating ElasticSearch with your bundles log data.

Open a browser to http://localhost:5601 to use Kibana


## Clearing data

You can purge the logs from ElasticSearch using this command:

`bin/clear-indices.sh`

If you re-run logstash without reconfiguring, then no data will be loaded as the last read position is saved to the target folder. Reconfigure or run this to clear these files:

`find target -name *.db -exec rm {} \;`
