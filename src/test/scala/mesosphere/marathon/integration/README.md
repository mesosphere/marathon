# Integration Tests

## General Setup

![Setup Overview](http://yuml.me/diagram/scruffy;dir:LR/class/%5bIntegration+Test%5d-%3e%5bTestDriver%5d%2c+%5bTestDriver%5d-%3e%5bMarathon%5d%2c+%5bMarathon%5d-%3e%5bMesos%5d%2c+%5bMarathon%5d-%3e%5bZooKeeper%5d%2c+%5bMesos%5d-%3e%5bAppMock%5d%2c+%5bMesos%5d-%3e%5bZooKeeper%5d%2c+%5bAppMock%5d-%3e%5bTestDriver%5d)

The integration test will launch marathon as external process (system under test = SUT).
The integration test infrastructure allows to communicate and control the state of the SUT.


## Single Marathon Test <a name="single"></a> 

There is a special setup, which makes testing a single instance of marathon simple.
By extending the trait SingleMarathonIntegrationTest, following behavior is provided:

Before the suite runs (except `useExternalSetup` is true):

- start zookeeper
- start mesos local
- start a marathon instance

Before the suite runs even if useExternalSetup is true:

- start a local http service 
- register the test driver as event callback listener
- provide a facade, to interact with the marathon instance


After the suite is finished:

- clean up the state (delete apps, groups, listener etc.)
- stop the http service
- stop all started processes

## Prerequisites

To successfully complete the integration tests, following things must be ensured:

- the mesos command must be in path (it will be started with mesos local)

## Traits

- IntegrationFunSuite: marks all test cases as integration test. 
  The tests will only run, during integration, but not as normal test case.
- SingleMarathonIntegrationTest: provide all functionality described in [Single Marathon Test](#single) 

## Helper classes

### AppMock

The AppMock is an executable, that can be launched from mesos as separate application.
The mock starts an http service. When a request is processed, the mock queries the test driver for
the http status response. Inside the test cases, this status can be manipulated.
With the app mocks in place, it is easy to simulate hanging or failing processes.

### IntegrationHealthCheck

Every started AppMock has health checks defined, which can be controlled by instances of this class.
Since all launched applications need a separate port which is assigned by mesos, the application has
to be started first, to be able to control the health of a specific instance.

## Starting the integration tests

To launch the integration tests from sbt, use

```
$sbt> integration:test 
```

## Configuration of the integration tests

There are following parameters, that can be used to configure the test setup

- useExternalSetup: Use an already running marathon instance instead of starting one in the test setup.
- cwd: the working directory, used to launch processes. 
  Default value is .
- zkPort: the port where a local zookeeper is started
  Default 2183
- master: the url of the mesos master to connect from marathon. 
  Default is local.
- mesosLib: the path to the native mesos library. This parameter will only take effect, 
  if the environment variable MESOS_NATIVE_JAVA_LIBRARY is not set.
  If not set, the java.library.path is searched for matches.
- httpPort: the port used for the http service to launch.
  Default value is: a random port between 11211 and 11311
- marathonPort: the port used for the marathon process to start
  Default value is: a random port between 8080 and 8180
- marathonGroup: The marathon group inside which all app definitions created by the integration tests reside.
  Default value is: /marathon_integration_test
  
The test config can be given via the command line as:

```
$> integration:testOnly mesosphere.marathon.integration.*IntegrationTest -- -Dmaster=local -DhttpPort=12345 -Dcwd=/
```
 


