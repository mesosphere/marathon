#!/bin/sh
cd /site-docs

if [ -z $MARATHON_DOCS_VERSION ]
  then
    echo "Generating top level docs"
    # BUNDLE_IGNORE_CONFIG=1 in case the user has a .bundle/config file from invoking bundler locally; we want to use the docker container's bundler files
    cmd="BUNDLE_IGNORE_CONFIG=1 bundle exec jekyll build --config _config.yml -d _site"
    eval $cmd
else
    echo "Generating docs for version $MARATHON_DOCS_VERSION"
    cmd="BUNDLE_IGNORE_CONFIG=1 bundle exec jekyll build --config _config.yml,_config.$MARATHON_DOCS_VERSION.yml -d _site/$MARATHON_DOCS_VERSION/"
    eval $cmd
fi
