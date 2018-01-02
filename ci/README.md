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
2. `pr`
3. `release`

The `jenkins` target is executed on every release branch, on master, and on each Github pull request. The `jenkins`
target will check if a build is for a pull-request, and if so, run forward to the `pr` target.

The `pr` target runs the test pipeline followed by Github PR review reporting. It is triggered with each diff
update.

The `release` target runs the build and packages Marathon in a Docker image,
native packages for various Linux distributions and a big jar.

The test pipeline involves the following steps:

* Compile
* Run unit tests
* Run integration tests
* Run scapegoat
* Build and upload snapshot artifacts

Additionally, for master builds, we:

* Update the DCOS packages for Marathon by pushing an update to the snapshot Marathon DCOS branch (for OSS and
  Enterprise).

## Sub Targets

The `provision.*` targets prepare the Jenkins AWS nodes by killing leak
processes from older test runs and updating Mesos. You can run them locally but
be careful. The `killStaleTestProcesses` might kill process you don't want to be
gone.

The `compileAndTest` target basically runs `sbt clean test integration:test
scapegoat`. This is the main compilation step.

The `build` target assembles Marathon binary packages and generates the
sha1 checksums for the zip and tarball packages. See `createPackageSha1s` in the
code base for details.

There are several targets in `githubClient`:

  * `reject`
  * `comment`
  * `reportSuccess`
  * `reportFailure`

Method `reject` and `reportFailure` rejects a GitHub pull request review while `reportSuccess` approves it.
The `comment` method posts a comment to the pull request.

One can comment from the CLI with
```
amm ./ci/githubClient.sc comment 777 "test from pipeline"
```
