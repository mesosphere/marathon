# Preferential GPU Scheduling


## Overview of GPU Scheduling in Marathon

Marathon supports launching GPU tasks when the command-line option `--features gpu_resources` is specified. Depending on the GPU availability, it may be desirable to configure Marathon to avoid placing non-GPU tasks on GPU nodes.


## Parameters in Marathon / Mesos

In order to enable GPU support in your cluster, you should be cognizant configure the following command-line launch parameters for Mesos and Marathon.

Mesos Master:

- `--filter_gpu_resources` - Only send offers for nodes containing GPUs to frameworks that opt-in to GPU resources (e.g. `marathon --features gpu_resources`).
- `--no-filter_gpu_resources` - Send offers for nodes containing GPUs to all frameworks, regardless of GPU opt-in status.

Marathon:

- `--features gpu_resources` - Tell Mesos that Marathon should be offered GPU resources. (See the [command-line docs](./command-line-flags.html)).
- `--gpu_scheduling_behavior` - Defines how offered GPU resources should be treated. Possible settings:
    - `unrestricted` (Default) - non-GPU tasks are launched irrespective of offers containing GPUs.
    - `undefined` - The same as `unrestricted`, but logs a warning when a GPU resource containing offer is used for a non-GPU task.
    - `restricted` - non-GPU tasks will decline offers containing GPUs. A decline reason of `DeclinedScareResources` is given.

## Enumeration of Different Possible Cluster Scenarios

You may fall under one of the following configuration scenarios:

1. **No GPUs**
2. **Scarce GPUs** - only a few nodes have GPUs.
3. **Abundant GPUs** - most or every node has a GPU.

Further, if your cluster has GPUs:

a. Marathon will directly launch GPU tasks (and, perhaps, also launch frameworks that launch GPU tasks).
b. Marathon will **not** launch GPU tasks - While Marathon may launch a GPU framework (such as Tensorflow) which launches GPU tasks, Marathon will not directly launch GPU tasks itself.

If GPUs are abundant, Marathon should be able to place non-GPU tasks on nodes containing GPUs. Otherwise, you might risk not being able to deploy non-GPU tasks.

If GPUs are scarce, Marathon should avoid placing non-GPU tasks on nodes containing GPUs. Otherwise, GPU tasks may not launch if the memory/CPU resources on GPU containing nodes are consumed by non-GPU tasks.

With that in mind, the following list of scenarios and suggested GPU param configuration should help guide your cluster configuration:

- (1 ) No GPUs
    - `mesos-master ...`
    - `marathon ...`
- (2a) Scarce GPUs, Marathon will directly launch GPU tasks
    - `mesos-master --filter_gpu_resources ...`
    - `marathon --features gpu_resources --gpu_scheduling_behavior restricted ...`
- (2b) Scarce GPUs, Marathon will **not** launch GPU tasks
    - `mesos-master --filter_gpu_resources ...`
    - `marathon ...`
- (3a) Abundant GPUs, Marathon will directly launch GPU tasks
    - `mesos-master --no-filter_gpu_resources ...`
    - `marathon --features gpu_resources --gpu_scheduling_behavior unrestricted ...`
- (3b) Abundant GPUs, Marathon will **not** launch GPU tasks
    - `mesos-master --no-filter_gpu_resources ...`
    - `marathon ...`

## Caveats / Edge Cases

Please be aware of the important following edge cases applicable to Marathon `--gpu_scheduling_behavior restricted`

### Nodes That Have All GPUs Consumed Treated as Non-GPU Nodes

When a GPU node has all GPU resources consumed (either reserved for other roles, or used by running tasks), Marathon will treat it as a non-GPU node and will proceed to place non-GPU tasks on it. This is because makes GPU placement decisions based off of resources offered, and if no GPUs are available for the role as which Marathon is registered, or the role `*`, then Marathon will see no GPUs.

If you reserve your GPUs for a non-Marathon role (e.g. Tensorflow), be sure to reserve CPU, disc, and memory resources, also for that role.

### Non-GPU Resident Tasks Will Ignore GPU Scheduling Behavior When Relaunching

If a resident task (has persistent volumes) that does not use GPUs is launched on a GPU resource containing node, then it will be relaunched, regardless of the setting of `--gpu_scheduling_behavior`. A resident task could launch on a GPU resource node, even though `--gpu_scheduling_behavior restricted` specified under the following scenarios:

1. The resident task is launched while the `--gpu_scheduling_behavior restricted` is unset, and then the flag is changed and Marathon is restarted.
2. The resident task is launched while all GPU resources on the GPU node are consumed (as outlined in the prior caveat).
