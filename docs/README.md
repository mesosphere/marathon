# Marathon Docs and Website

Ensure you have installed everything listed in the dependencies section before
following the instructions.

## Dependencies

* Docker
* Ammonite

## Instructions

### Using the script to generate documentation

1. Install [Ammonite-REPL](http://ammonite.io/#Ammonite-REPL) if you don't have it.

2. Install Docker

3. Run the script:

        $ cd ci
        $ ./generate_docs.sc

4. Enjoy your docs at
   [http://localhost:8080/](http://localhost:8080/)

### Deploying the documentation to the github pages

1. Run the script with a publish flag:

        $ ./generate_docs.sc --publish true --preview false
        
### Additional options

1. `--remote <git remote>` specifies a git remote where docs will be published (useful for testing)
2. `--preview false` disables preview
3. `--release_commits_override 1.6=hash1,1.5=hash2` allows to use a specific commits instead of latest tags for each respective branch