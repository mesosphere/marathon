# Examples

This directory contains example configurations for a number of popular tools.
Here is how you start them with Curl:

For applications:

    curl -i -H 'Content-Type: application/json' -d @<filename.json> localhost:8080/v2/apps

For groups:

    curl -i -H 'Content-Type: application/json' -d @<filename.json> localhost:8080/v2/groups

If you want to contribute your own config, please send us a pull request!
