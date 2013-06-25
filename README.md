# Marathon

Marathon is a Mesos framework for long running services. It provides a REST API for starting, stopping, and scaling services.

## API

Using [HTTPie](http://httpie.org):

  http localhost:8080/v1/service/start id=sleep cmd='sleep 600' instances=1 mem=128 cpus=1
  http localhost:8080/v1/service/scale id=sleep instances=2
  http localhost:8080/v1/service/stop id=sleep

More in the examples dir.
