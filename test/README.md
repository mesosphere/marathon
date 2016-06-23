# Marathon Testing

The Marathon project has several styles of tests that are maintained with the project that include:

* Unit Testing + Mocking
* System Testing

##  Unit Testing

The unit and mocking tests are part of the project.  The test code is managed in the
code base under `src/test` and are involved with `sbt test`.  These tests are whitebox tests and
are focused on testing the internals of Marathon.

These tests are run for each pull request as part of CI builds and are maintained with
[jenkins](https://velocity.mesosphere.com/service/velocity/view/Marathon/).

For version code of tests, the CI build is initiated with [jenkins/unit-integration-tests.sh](../jenkins/unit-integration-tests.sh)

## System Testing

The system integration tests are integration tests with [DCOS](http://dcos.io) and verifies
system integration behavior from a user perspective using DCOS as the context.   The tests are
writen in python using [shakedown](https://github.com/dcos/shakedown) as the testing tool.

The tests are written under the project [test/system](system/README.md).
