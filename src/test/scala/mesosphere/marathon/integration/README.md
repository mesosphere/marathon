# Integration Tests

## General Setup

![Setup Overview](http://yuml.me/diagram/scruffy;dir:LR/class/%5bIntegration+Test%5d-%3e%5bTestDriver%5d%2c+%5bTestDriver%5d-%3e%5bMarathon%5d%2c+%5bMarathon%5d-%3e%5bMesos%5d%2c+%5bMarathon%5d-%3e%5bZooKeeper%5d%2c+%5bMesos%5d-%3e%5bAppMock%5d%2c+%5bMesos%5d-%3e%5bZooKeeper%5d%2c+%5bAppMock%5d-%3e%5bTestDriver%5d)

The integration test will launch marathon as external process (system under test = SUT).
The integration test infrastructure allows to communicate and control the state of the SUT.


## Single Marathon Test <a name="single"></a> 

There is a special setup, which makes testing a single instance of marathon simple.
By extending the trait SingleMarathonIntegrationTest, following behaviuor is provided:

Before the suite runs:

- start mesos local
- start a marathon instance
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
- a running zookeeper instance has to be provided
  Without any configuration, a local zookeeper instance is assumed.

Side note: we should think about starting a local zookeeper for the integration tests.
 
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

The system property integration is used to mark a test run as integration test.
To launch the integration tests from maven, use

```
$> mvn -Dintegration=true test
```

## Configuration of the integration tests

There are following parameters, that can be used to configure the test setup

- cwd: the working directory, used to launch processes. 
  Default value is .
- zk: the url of the zookeeper instance(s). 
  Default is zk://localhost:2181/test.
- master: the url of the mesos master to connect from marathon. 
  Default is local.
- mesosLib: the path to the native mesos library. This parameter will only take effect, 
  if the environment variable MESOS_NATIVE_LIBRARY is not set.
  Default value is: /usr/local/lib/libmesos.dylib
- httpPort: the port used for the http service to launch.
  Default value is: a random port between 11211 and 11311
- singleMarathonPort: the port used for the marathon process to start
  Default value is: a random port between 8080 and 8180
  
The test config can be given via the command line as:

```
$> mvn -DtestConfig="cwd=/,master=local,httpPort=12345" -Dintegration=true test
```
 


