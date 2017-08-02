#!/bin/sh
cd /marathon-docs

# BUNDLE_IGNORE_CONFIG=1 in case the user has a .bundle/config file from invoking bundler locally; we want to use the docker container's bundler files
BUNDLE_IGNORE_CONFIG=1 bundle exec jekyll serve --watch -H 0.0.0.0
