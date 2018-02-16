---
title: Constraints
---

# Constraints

Constraints control where apps run to allow optimizing for either fault tolerance (by spreading a task out on multiple nodes) or locality (by running all of an applications tasks on the same node). Constraints have three parts: a field name, an operator, and an optional parameter. The field can be the hostname of the agent node or any attribute of the agent node.

## Fields

### Hostname field

The `hostname` field matches the agent node hostnames. See `UNIQUE operator`, below, for a usage example.

`hostname` field supports all operators of Marathon.

### Attribute field

If the field name is not the agent node hostname, it will be treated as a Mesos agent node attribute. A Mesos agent node attribute allows you to tag an agent node. See `mesos-slave --help` to learn how to set the attributes.

If the specified attribute is not defined on the agent node, most operators will refuse to run tasks on it. In fact, only the `UNLIKE` operator will (and always will) accept this offer for now, while other operators will always refuse it.

Attribute field supports all operators of Marathon.

Marathon supports text, scalar, range, and set attribute values. For scalars, ranges, and sets Marathon will perform a string comparison on the formatted values. The format matches that of the Mesos attribute formatting. For ranges and sets, the format is `[begin-end,...]` and `{item,...}` respectively. For example, you might have a range formatted as `[100-200]` and a set formatted as `{a,b,c}`.

Regex is allowed for LIKE and UNLIKE operators; to match ANY value, use the string `.*`.

## Operators

### CLUSTER operator
**Value** (optional): A string value.
When a value is specified then tasks are launched on agent nodes with an attribute whose value matches exactly.

`CLUSTER` allows you to run all of your app's tasks on agent nodes that share a certain attribute.
This is useful for example if you have apps with special hardware needs, or if you want to run them on the same rack for low latency.

A field of `"hostname"` tells Marathon that launched tasks of the app/pod have affinity for each other and should be launched together on the same agent:
* When value is specified then tasks are launched on the agent whose hostname matches the value.
* When value is empty or unspecified then the first instance is launched on **any** agent node and the remaining tasks are launched alongside it on the same agent.

Attribute fields are handled differently:
* When value is specified then tasks are launched on any agent with an attribute named according the field **and** with a value matching that of the constraint.
* When value is empty or unspecified then the first instance is launched on any agent with an attribute named according to the field; the value of the attribute on that agent is used for future constraint matches.

You could specify the exact rack on which to run app tasks:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "CLUSTER", "rack-1"]]
  }'
```

Alternatively, you could specify that all of the app tasks should run on the same rack, but without specifying which rack:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "CLUSTER"]]
  }'
```

You can also tie an app to a specific node by using the `hostname` field:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "CLUSTER", "a.specific.node.com"]]
  }'
```

Or in a similar way, you can run all app tasks together on the same node but without specifying which agent to use:

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-cluster",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "CLUSTER"]]
  }'
```

### LIKE operator
**Value** (required): A regular expression for the value of the attribute.

`LIKE` accepts a regular expression as parameter and allows you to run your tasks only on the agent nodes whose field values match the regular expression.

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "LIKE", "rack-[1-3]"]]
  }'
```

### UNLIKE operator
**Value** (required): A regular expression for the value of the attribute.

Just like `LIKE` operator, but only run tasks on agent nodes whose field values don't match the regular expression.

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "UNLIKE", "rack-[7-9]"]]
  }'
```

### MAX_PER operator
**Value** (required): An integer, for example `"2"`.

`MAX_PER` accepts a number as parameter which specifies the maximum size of each group. It can be used to limit tasks across racks or datacenters.

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "MAX_PER", "2"]]
  }'
```

### UNIQUE operator
`UNIQUE` does not accept a value.

`UNIQUE` tells Marathon to enforce uniqueness of the attribute across all of an app's tasks. For example the following constraint ensures that there is only one app task running on each host.

``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-unique",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["hostname", "UNIQUE"]]
  }'
```

### GROUP_BY operator
**value** (optional): An integer, indicating how many distinct values can be expected for the given `fieldName`, for example `"3"`.

`GROUP_BY` can be used to distribute tasks evenly across racks or datacenters for high availability. Be aware that not specifying a value for this operator will lead to non-deterministic results based on the order of offers that Marathon receives. This is because Marathon will only discover distinct values of the provided `fieldName` when it receives offers.

Imagine the following scenario: Marathon is trying to launch 9 tasks and is constrained by `["rack_id", "GROUP_BY"]`, i.e. without a specified value. Now, for some time, Marathon is only receiving offers from agents specifying `"rack_1"` and `"rack_2"` and will evenly distribute 8 tasks over these 2 racks. When Marathon eventually receives an offer from `"rack_3"`, it will place a task there, but the result is obviously not an even distribution. Several things to note here:

1. In the above scenario, Marathon will not kill any task in order to group by the newly discovered third rack.
2. Specifying the value `"3"` in this case will only place 2 tasks, one on `"rack_1"` and one on `"rack_2"`, and then wait until an offer from a third rack is received.
3. Specifying a value `"3"` when there's actually 5 racks will not prevent Marathon from placing tasks on additional racks. It only means that Marathon will distribute evenly across at least 3 racks.

Example:
``` bash
$ curl -X POST -H "Content-type: application/json" localhost:8080/v2/apps -d '{
    "id": "sleep-group-by",
    "cmd": "sleep 60",
    "instances": 3,
    "constraints": [["rack_id", "GROUP_BY", "3"]]
  }'
```
