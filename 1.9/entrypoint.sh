#!/bin/sh
cd /site-docs

MODE="${1:-build}"

# in case the user has a .bundle/config file from invoking bundler locally; we want to use the docker container's bundler files
export BUNDLE_IGNORE_CONFIG=1

case "$MODE" in
  build)
    if [ -z $MARATHON_DOCS_VERSION ]; then
      echo "Generating top level docs"
      bundle exec jekyll build --config _config.yml -d _site
    else
      echo "Generating docs for version $MARATHON_DOCS_VERSION"
      bundle exec jekyll build --config "_config.yml,_config.$MARATHON_DOCS_VERSION.yml" -d "_site/$MARATHON_DOCS_VERSION/"
    fi
    ;;
  watch)
    bundle exec jekyll serve --watch -H 0.0.0.0
    ;;
esac
