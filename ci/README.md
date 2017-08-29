# Marathon CI pipeline

In order to be CI tool agnostic and provide the benefit of running all CI tasks
on a "local" machine, the marathon team has moved to using [Ammonite](http://www.lihaoyi.com/Ammonite/)
for CI pipelining tasks.   Ammonite is a Scala based scripting tool and is
easiest to install on a Mac with `brew install ammonite-repl`.  Other platforms
please read the Ammonite site.   The `ci` folder in the root project contains
the ammonite scripts.   The script requires the project build requirements such
as a JDK, Scala and sbt in the path.


## Using Ammonite

The ammonite script is a runnable script.  To get a list of functions to invoke execute: `./ci/pipeline`

To execute a particular function just invoke the script with function appended as in:
`./ci/pipeline compileAndTest` which will compile and test the marathon project.

## Pipeline Targets

The `ci/pipeline` script defines two primary targets

1. `jenkins`
2. `phabricator`

The `jenkins` target is excuted on every branch build by jenkins. It runs

  * `provision.killStaleTestProcesses()`
  * `provision.installMesos()`
  * `compileAndTest()`
  * `createPackages()`

The `phabricator` target runs the `jenkins` pipeline followed by Phabricator
review reporting. It is triggered with each diff update.

## Sub Targets

The `provision.*` targets prepare the Jenkins AWS nodes by killing leak
processes from older test runs and updating Mesos. You can run them locally but
be careful. The `killStaleTestProcesses` might kill process you don't want to be
gone.

The `compileAndTest` target basically runs `sbt clean test integration:test
scapegoat`. This is the main compilation step.

The `createPackages` target assembles Marathon binary packages and generates the
sha1 checksums for the zip and tarball packages. See `createPackageSha1s` in the
code base for details.

There are several targets in `phabricatorClient`:

  * `accept`
  * `reject`
  * `comment`
  * `reportTestResults`
  * `reportSuccess`
  * `reportFailure`

All Phabricator targets require the `PHABRICATOR_API_TOKEN` environment variable
to be set to a valid Phabricator API token. One can generate a token with
[Conduit](https://phabricator.mesosphere.com/conduit/login/).

The targets can be run locally and expect diff ids and PHIDs. The later can
either be the ID of a Phabricator Differential review, e.g. `777` for
`https://phabricator.mesosphere.com/D777` or an ID generator by Harbormaster,
e.g. `PHID-HMBT-76qfbscvw2vs777hu000`. The ids have to be valid, i.e. they have
to correspong to an actuall Differential review.

Methods `accept` and `reject` accept or reject a Phabriactor Differential review.
The `comment` method posts a comment to Phabriactor Differential reviews.
`reportTestResults` uploads unit and integration test results to a build
triggered by Phabriactor Harbormaster. It checks `target/phabricator-test-reports/`
for JSON files and joins them. The methods `reportSuccess` and `reportFailure`
call the previous methods to report back.

One can comment from the CLI with
```
PHABRICATOR_API_TOKEN=<secret> amm ./ci/phabricatorClient.sc comment 777 "test from pipeline"
```
