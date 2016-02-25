---
title: Constraints
---

# Constraints

Constraints control where apps run to enable you to optimize for fault tolerance or location. Constraints have three parts: a field name, an operator, and an optional parameter. The field can be the slave hostname or any Mesos slave attribute.

## Fields

### Hostname field

The `hostname` field matches the slave hostnames. See `UNIQUE operator`, below, for a usage example.

The `hostname` field supports all Marathon operators.

### Attribute field

If the field name is not the slave hostname, it will be treated as a Mesos slave attribute. A Mesos slave attribute tags a slave node, see `mesos-slave --help` to learn how to set the attributes.

If the specified attribute is not defined on the slave, then most operators will refuse to run tasks on this slave. In fact, only the `UNLIKE` operator will (and always will) accept this offer for now, while other operators will always refuse it.

The attribute field supports all operators of Marathon.

**Note:** Marathon currently only supports text values for attributes; Marathon cannot process numerical values. For example, the attribute pair `foo:bar` will be recognised, but marathon will not be able to match `cpu:4`.

## Operators

### UNIQUE operator

`UNIQUE` tells Marathon to enforce uniqueness of an attribute across all of an app's tasks. For example, the following constraint ensures that there is only one app task running on each host:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-unique",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "UNIQUE"]]
  }'
```

### CLUSTER operator

`CLUSTER` allows you to run all of your app's tasks on slaves that share a certain attribute. This is useful if, for example, you have apps with special hardware needs, or if you want to run them on the same rack for low latency:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "CLUSTER", "rack-1"]]
  }'
```

You can also use the `CLUSTER` operator in conjunction with the hostname property to tie an application to a specific node:

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

`LIKE` accepts a regular expression as parameter. Your tasks will only run on the slaves whose field values match the regular expression:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "LIKE", "rack-[1-3]"]]
  }'
```

**Note:** The parameter is required.

### UNLIKE operator

`UNLIKE` accepts a regular expression as a parameter. Your tasks will only run on slaves whose field values *do not* match the regular expression:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "UNLIKE", "rack-[7-9]"]]
  }'
```