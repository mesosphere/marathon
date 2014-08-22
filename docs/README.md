# Marathon Docs and Website

## Run it locally

Ensure you have installed everything listed in the dependencies setction before
following the instructions.

### Dependencies

* Ruby
* [RubyGems](https://rubygems.org/)
* [Bundler](http://bundler.io/)

### Instructions

1. Clone the Marathon repository

2. Change into the "docs" directory where docs live

      $ cd docs

3. Install the bundle's dependencies

      $ bundle install

4. Start the web server

      $ bundle exec jekyll serve --watch

## Deploying the site

1. Clone a separate copy of the Marathon repo as a sibling of your normal
   Marathon project directory and name it "marathon-gh-pages".

      $ git clone git@github.com:mesosphere/marathon.git marathon-gh-pages

2. Check out the "gh-pages" branch.

      $ cd /path/to/marathon-gh-pages
      $ git checkout gh-pages

3. Copy the contents of the "docs" directory in master to the root of your
   marathon-gh-pages directory.

      $ cd /path/to/marathon
      $ cp -r docs/** ../marathon-gh-pages

4. Change to the marathon-gh-pages directory, commit, and push the changes

      $ cd /path/to/marathon-gh-pages
      $ git commit . -m "Syncing docs with master branch"
      $ git push
