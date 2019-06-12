# Marathon Docs and Website

Ensure you have installed everything listed in the dependencies section before
following the instructions.

**Important:** `gh-pages` branch is auto generated. Please do not edit it manually, otherwise your changes will be lost.

## Dependencies

* Docker
* Ammonite

## Instructions

### Previewing a single branch (with incremental rebuild)

1. Install Docker (if not already installed)

2. Build the docker image:

        docker build . -t jekyll

3. Run it (from this folder)

        docker run --rm -it -v $(pwd):/site-docs -p 4000:4000 jekyll watch

4. Visit the site at [http://localhost:4000/marathon/](http://localhost:4000/marathon/) (note the trailing slash)

###  Rendering the complete documentation (no incremental rebuild, the end result of what will be published)

1. Install [Ammonite-REPL](http://ammonite.io/#Ammonite-REPL) if you don't have it.

2. Install Docker

3. Run the script:

        $ cd ci
        $ ./generate_docs.sc

4. Enjoy your docs at
   [http://localhost:8080/](http://localhost:8080/)

### Pushing the documentation to the github pages

1. Run the script with a publish flag:

        $ ./generate_docs.sc --publish true --preview false
        
### Additional options

1. `--remote <git remote>` specifies a git remote where docs will be published (useful for testing)
2. `--preview false` disables preview
3. `--release_commits_override 1.6=hash1,1.5=hash2` allows to use a specific commits instead of latest tags for each respective branch
4. `--ignored_versions 1.7` ignores provided minor version when generating/publishing docs.
