---
---

# Constraints

Constraints control where apps run to allow optimizing for fault tolerance or locality.
Constraints can be set via the REST API or the [Marathon gem](https://rubygems.org/gems/marathon_client) when starting an app. Make sure to use the gem version 0.2.0 or later for constraint support. Constraints are made up of three parts: a field name, an operator, and an optional value. The field can be the slave hostname or any Mesos slave attribute.

### UNIQUE operator

`UNIQUE` tells Marathon to enforce uniqueness of the attribute across all of an app's tasks. For example the following constraint ensures that there is only one app task running on each host:

via the Marathon gem:

``` bash
$ marathon start -i sleep -C 'sleep 60' -n 3 --constraint hostname:UNIQUE
```

via curl:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v1/apps/start -d '{
    "id": "sleep-unique",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "UNIQUE"]]
  }'
```

### CLUSTER operator

`CLUSTER` allows you to run all of your app's tasks on slaves that share a certain attribute. This is useful for example if you have apps with special hardware needs, or if you want to run them on the same rack for low latency.

via the Marathon gem:

``` bash
$ marathon start -i sleep -C 'sleep 60' -n 3 --constraint rack_id:CLUSTER:rack-1
```

via curl:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v1/apps/start -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "CLUSTER", "rack-1"]]
  }'
```

You can also use this attribute to tie an application to a specific node by using the hostname property:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v1/apps/start -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "CLUSTER", "a.specific.node.com"]]
  }'
```

### GROUP_BY operator

`GROUP_BY` can be used to distribute tasks evenly across racks or datacenters for high availability.

via the Marathon gem:

``` bash
$ marathon start -i sleep -C 'sleep 60' -n 3 --constraint rack_id:GROUP_BY
```

via curl:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v1/apps/start -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "GROUP_BY"]]
  }'
```

Optionally, you can specify a minimum number of groups to try and achieve.
