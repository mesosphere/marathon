# Analyzing Marathon with ELK Stack

Marathon comes with a basic set of grok filter for Logstash to load logs into
Elasticsearch. This should enable users to analyze logs with Kibana.

## Generating Logstash config

### DCOS Bundle

Logstash config can be generated for a DCOS bundle using the following command:

```
bin/target bundle <path-to-diagnostic-bundle>
```

Command will attempt to detect log line format (include date, etc.), and generate the appropriate Logstash input
directives such that the hostname field will be populated in Elasticsearch.

## Installing Elasticsearch, Kibana, and Logstash

These tools can be easily installed with Homebrew:

```
brew install elasticsearch
brew install kibana
brew install logstash
```

## Running Elasticsearch, Kibana, and Logstash

In a separate terminal EACH, run the following commands, in this order:

```
elasticsearch

kibana

logstash -f target/loading
```

Logstash will begin populating Elasticsearch with your bundles log data.

Open a browser to http://localhost:5601 to use Kibana


## Clearing data

You can purge the data loaded via Logstash from Elasticsearch using this command (Elasticsearch must be running):

`bin/clear-indices.sh`

If you re-run Logstash without reconfiguring, then no data will be loaded as the last read position is saved to the target folder. Reconfigure or run this to clear these files:

`find target -name *.db -exec rm {} \;`
