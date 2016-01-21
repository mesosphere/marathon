---
title: Constraints
---

# Constraints

Constraints control where apps run to allow optimizing for fault tolerance or locality. They are made up of three parts: a field name, an operator, and an optional parameter. The field can be the slave hostname or any Mesos slave attribute.

## Fields

### Hostname field

`hostname` field matches the slave hostnames, see `UNIQUE operator` for usage example.

`hostname` field supports all operators of Marathon.

### Attribute field

If the field name is none of the above, it will be treated as a Mesos slave attribute. Mesos slave attribute is a way to tag a slave node, see `mesos-slave --help` to learn how to set the attributes.

If the specified attribute is not defined on the slave, then most operators will refuse to run tasks on this slave. In fact, only the `UNLIKE` operator will (and always will) accept this offer for now, while other operators will always refuse it.

Attribute field supports all operators of Marathon.

Note that currently Marathon only supports text values for attributes and will not be able to process any numberic values. For example the attribute pair `foo:bar` will be recognised, but marathon will not be able to match `cpu:4`.

## Operators

### UNIQUE operator

`UNIQUE` tells Marathon to enforce uniqueness of the attribute across all of an app's tasks. For example the following constraint ensures that there is only one app task running on each host:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-unique",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "UNIQUE"]]
  }'
```

### CLUSTER operator

`CLUSTER` allows you to run all of your app's tasks on slaves that share a certain attribute. This is useful for example if you have apps with special hardware needs, or if you want to run them on the same rack for low latency:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "CLUSTER", "rack-1"]]
  }'
```

You can also use this attribute to tie an application to a specific node by using the hostname property:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "CLUSTER", "a.specific.node.com"]]
  }'
```

### GROUP_BY operator

`GROUP_BY` can be used to distribute tasks evenly across racks or datacenters for high availability:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "GROUP_BY"]]
  }'
```

Marathon only knows about different values of the attribute (e.g. "`rack_id`") by analyzing incoming offers from Mesos. If tasks are not already spread across all possible values, specify the number of values in constraints. If you don't specify the number of values, you might find that the tasks are only distributed in one value, even though you are using the GROUP_BY constraint. For example, if you are spreading across 3 racks, use:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "GROUP_BY", "3"]]
  }'
```


### LIKE operator

`LIKE` accepts a regular expression as parameter, and allows you to run your tasks only on the slaves whose field values match the regular expression:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "LIKE", "rack-[1-3]"]]
  }'
```

Note, the parameter is required, or you'll get a warning.

### UNLIKE operator

Just like `LIKE` operator, but only run tasks on slaves whose field values don't match the regular expression:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "UNLIKE", "rack-[7-9]"]]
  }'
```
