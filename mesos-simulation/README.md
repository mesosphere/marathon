This module provides a simplistic Mesos simulation which can be used for Marathon scale tests.

# Run Marathon in Simulation Mode

The marathon with the simulated Mesos can be started from the command line like this:

```bash
sbt -Djava.library.path=<...include the directories of your native mesos libraries...> \
    "project mesosSimulation" "run --master zk://localhost:2181/mesos --zk zk://localhost:2181/marathon"
```

Of course, you can adjust your configuration. You need to provide the master configuration but no Mesos master or slave
has to be running.

# Run Automated Scaling Test

Run scaling tests:

```bash
sbt "project mesosSimulation" "integration:testOnly **ScalingTest"
```

Show results:

```bash
sbt "project mesosSimulation" "test:run"
```

The simulation is currently not configurable outside the code. If you want to adjust the number
of offers sent every offer cycle, you can change `mesosphere.mesos.simulation.DriverActor.numberOfOffersPerCycle`.