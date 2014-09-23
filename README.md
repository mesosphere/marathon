# Marathon Docs and Website

## Run it locally

Ensure you have installed everything listed in the dependencies setction before
following the instructions.

### Dependencies

* [Bundler](http://bundler.io/)
* [Node.js](http://nodejs.org/) (for compiling assets)
* Python
* Ruby
* [RubyGems](https://rubygems.org/)

### Instructions

1. Install packages needed to generate the site

    * On Linux:

            $ apt-get install ruby-dev make autoconf nodejs nodejs-legacy python-dev
    * On Mac OS X:
    
            $ brew install node

2. Clone the Marathon repository

3. Change into the "docs" directory where docs live

        $ cd docs

4. Install Bundler

        $ gem install bundler

5. Install the bundle's dependencies

        $ bundle install

6. Start the web server

        $ bundle exec jekyll serve --watch

7. Visit the site at
   [http://localhost:4000/marathon/](http://localhost:4000/marathon/)

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
