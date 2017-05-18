# Marathon CI pipeline

In order to be CI tool agnostic and provide the benefit of running all CI tasks
on a "local" machine, the marathon team has moved to using [Amonite](http://www.lihaoyi.com/Ammonite/) for CI pipelining tasks.   Ammonite
is a Scala based scripting tool and is easiest to install on a Mac with `brew install ammonite-repl`.  Other platforms please read the Ammonite site.   The `ci` folder
in the root project contains the ammonite scripts.   The script requires the project build
requirements such as a JDK, Scala and sbt in the path.


## Using Ammonite

The ammonite script is a runnable script.  To get a list of functions to invoke execute: `./ci/pipeline`

To execute a particular function just invoke the script with function appended as in:
`./ci/pipeline compileAndTest` which will compile and test the marathon project.
