# Marathon Docs and Website

## Run it locally

Ensure you have installed everything listed in the dependencies section before
following the instructions.

### Dependencies

* [Bundler](http://bundler.io/)
* [Node.js](http://nodejs.org/) (for compiling assets)
* Python
* Ruby
* [RubyGems](https://rubygems.org/)

### Instructions

#### Using the script to generate documentation

1. Install [Ammonite-REPL](http://ammonite.io/#Ammonite-REPL) if you don't have it.

2. Install Docker

3. Run the script:

        $ cd ci
        $ amm generate_docs.sc

4. Enjoy your docs at
   [http://localhost:8080/](http://localhost:8080/)

## Deploying the site

1. Clone a separate copy of the Marathon repo as a sibling of your normal
   Marathon project directory and name it "marathon-gh-pages".

        $ git clone git@github.com:mesosphere/marathon.git marathon-gh-pages

2. Check out the "gh-pages" branch.

        $ cd /path/to/marathon-gh-pages
        $ git checkout gh-pages

3. Run the docs generation script. After you do this, it will print the path with generated docs:

        Success! Docs were generated at /tmp/marathon-docs-build-2018-02-21T14-46-49.260Z/docs/_site
        
4. Use the provided path to copy docs into gh-pages branch folder

        $ # to make sure we also remove deleted documentation, we need to delete all files first.
        $ # please note, rm -r ../marathon-gh-pages/* will not delete dot-files
        $ rm -r ../marathon-gh-pages/*
        $ cp -r /tmp/marathon-docs-build-2018-02-21T14-46-49.260Z/docs/_site/** ../marathon-gh-pages

4. Change to the marathon-gh-pages directory, commit, and push the changes

        $ cd /path/to/marathon-gh-pages
        $ git commit . -m "Syncing docs with release branch"
        $ git push
