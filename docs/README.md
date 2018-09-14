# Marathon Docs and Website

## Previewing the Docs

### Native OS

1. Ensure you have the following dependencies installed:

    * [Bundler](http://bundler.io/)
    * [Node.js](http://nodejs.org/) (for compiling assets)
    * Python
    * Ruby
    * [RubyGems](https://rubygems.org/)

2. Run Jekyll:

      ```
      bundle exec jekyll serve --watch -H 0.0.0.0
      ```

3. Visit the site at [http://localhost:4000/marathon/](http://localhost:4000/marathon/) (note the trailing slash)

### Docker

1. Build the docker image:

        docker build . -t jekyll

2. Run it (from this folder)

        docker run --rm -it -v $(pwd):/site-docs -p 4000:4000 jekyll watch

3. Visit the site at [http://localhost:4000/marathon/](http://localhost:4000/marathon/) (note the trailing slash)


## Publishing the Documentation

### Render the documentation

1. Install [Ammonite-REPL](http://ammonite.io/#Ammonite-REPL) if you don't have it.

2. Install Docker

3. Run the script:

        $ cd ci
        $ amm generate_docs.sc

4. Enjoy your docs at
   [http://localhost:8080/](http://localhost:8080/)

### Deploy

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
