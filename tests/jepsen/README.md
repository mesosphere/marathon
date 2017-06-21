# Marathon Jepsen
Automate testing of various Marathon operations in a cluster in the presence of controlled network splits.

## What is Jepsen?
Jepsen is a clojure library designed by Kyle Kingsbury, designed to test the partition tolerance of distributed systems.
<brbr>Find more about Jepsen here https://jepsen.io

Jepsen source code can be found here https://github.com/jepsen-io/jepsen

## Usage

1. To spin-up VMs using `vagrant`:
```
vagrant up
```
This will spin-up 2 VMs for now for the purpose of testing.

1. To download the Jepsen and other dependencies:
```
make deps
```

1. To perform Jepsen setup and teardown on each of the VMs:
```
make
```
`make` if not passed any argument as shown above, will pick up the list of nodes from `nodes_list` file.

1. If you want run Jepsen setup and teardown on more number of nodes, add them in the `nodes_list` file or, a new nodes_list file can be passed as follows:
```
make run nodes=<path-to-nodes-file>
```

NOTE: Currently, Jepsen setup and teardown code for Mesos, Marathon and Zookeeper has been implemented. The further additions would be the Jepsen client, generator and checker code.
