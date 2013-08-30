# Marathon

Marathon is a Mesos framework for long running services. It provides a REST API for starting, stopping, and scaling services.

<p align="center">
  <img src="http://www.jeremyscottadidas-wings.co.uk/images/Adidas-Jeremy-Scott-Wing-Shoes-2-0-Gold-Sneakers.jpg" width="30%" height="30%">
</p>

## API

Using [HTTPie](http://httpie.org):

    http localhost:8080/v1/apps/start id=sleep cmd='sleep 600' instances=1 mem=128 cpus=1
    http localhost:8080/v1/apps/scale id=sleep instances=2
    http localhost:8080/v1/apps/stop id=sleep

More in the examples dir.
