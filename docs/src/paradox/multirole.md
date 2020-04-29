# Introduction

Marathon 1.9 brings support for multi-role, allowing a single Marathon instance to launch services for more than one role. The `role` field is added to pods and services, with the possible values restricted to either the default Mesos role (`--mesos_role`) or the associated group role. A group-role convention is introduced to enforce a relationship between Marathon groups and the corresponding roles for services within them. In order to assist users that wish to migrate their Marathon instance to use multi-role, an incremental pathway is defined and explained in this document.

Multi-role functionality itself is enabled by default; no command-line parameter is required to enable it. However, there are run-time configuration options (group `enforceRole` setting and service `role` field) to control it, along with the command-line parameter `--new_group_enforce_role` to affect default behavior.

While it is possible to partially use multi-role in Marathon, it is recommended that Marathon be fully "upgraded" to multi-role. This is to say:

* All top-level services (those not in groups) are moved to a group.
* All services are updated to use the group-role
* `enforceRole` is enabled for all top-level groups
* The `--new_group_enforce_role` command-line parameter is set to `top`.

# Service Role Field

Each Marathon service now contains a `role` field, which defaults to the role specified by the command line parameter `--mesos_role`, which defaults to `*`. For example, if the command line parameter `--mesos_role` is `slave_public` (as is the default in DC/OS), and the following service is posted to Marathon `/v2/apps`:

```json
{
  "id": "/dev/bigbusiness",
  "cmd": "sleep 3600",
  "instances": 10,
  "cpus": 0.05,
  "mem": 128
}
```

... then, the resulting Marathon service will receive the default role `slave_public`, as follows:

```json
{
  "id": "/dev/bigbusiness",
  "cmd": "set; sleep 3600",
  "instances": 10,
  "cpus": 0.05,
  "mem": 128,
  "role": "slave_public"
}
```

Marathon will then revive offers for the role `slave_public` and match offers allocated to the role `slave_public` in order to launch this service.

# Group Role

The group role is the name of the top-level group, as illustrated by the following table:

| Full service id | Marathon group | Group-role |
| --------------- | -------------- | --------- |
| /dev/bigbusiness | /dev | "dev" |
| /bigbusiness | / | N/A |
| /dev/team1/bigbusiness | /dev/team1 | "dev" |
| /frontend/ui | /frontend | "frontend" |

A service may only be assigned one of two possible roles: the default role (that which is specified by `--mesos_role`), or, the group role. Note that services placed directly in the root group do not have a group-role.

# Migrating Existing Services

Migrating a service to multi-role means modifying an existing service role from using the default Mesos role to using the group role.

Migrating ephemeral services is straightforward: update the role, and Marathon relaunches the service, accepting and using resources from offers for the new role. However, there is a caveat when migrating services with reservations.

## Ephemeral service multi-role migration

In order to migrate the `bigbusiness` app described earlier in this document from using the default Mesos role to the group role, you can simply post the following:

```
echo '{"role": "dev"} | curl -X PATCH http://local:8080/v2/apps/dev/bigbusiness -H "Content-Type: application/json" --data "@-"
```

This will trigger a deployment where all of the old `bigbusiness` instances running as the role `slave_public` are killed, and replaced by new instances running as the role `dev`. Further, Marathon will update its `FrameworkInfo` information with Mesos to also include the role `dev`, so that it can begin receiving offers allocated to the role. This may require the Mesos `create role` permission if the role does not already exist. See the section below on Mesos permission caveats.

## Resident service multi-role migration

Migrating resident services is a bit different, since changing the role for reserved resources would result in the reservations being destroyed (and subsequently the volume data), and this is often not desired. Instead of wiping old reserved instances, Marathon allows a resident service role to be changed for newly created instances, continuing to leave the existing instances running with the old role.

For example, say that you have deployed the following resident service:

```
$ cat <<EOF | curl -v -X POST http://localhost:8080/v2/pods -H "Content-Type: application/json" --data "@-"
{
  "id": "/dev/bigbusiness",
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "pwd; ls -la; date >> data/launches.txt; sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "volumeMounts": [{
        "name": "data",
        "mountPath": "data"
      }]
    }
  ],
  "volumes": [
    {
      "name": "data",
      "persistent": {"size": 16, "type": "root"}
    }
  ]
}
EOF
```

When you check the roles for the running instances, you will see that the instances were launched with the role `slave_public`:

```
$ curl http://localhost:8080/v2/pods/dev/bigbusiness::status | jq '{specRole: .spec.role, instanceRoles: [.instances[].role]}'

{
  "specRole": "slave_public",
  "instanceRoles": [
    "slave_public"
  ]
}
```

When you attempt to PUT the update with the role `dev` to the pods endpoint, as follows:

```
$ cat <<EOF | curl -v -X PUT http://localhost:8080/v2/pods/dev/bigbusiness -H "Content-Type: application/json" --data "@-"
{
  "id": "/dev/bigbusiness",
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "pwd; ls -la; date >> le-data/launches.txt; sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "volumeMounts": [{
        "name": "data",
        "mountPath": "le-data"
      }]
    }
  ],
  "volumes": [
    {
      "name": "data",
      "persistent": {"size": 16, "type": "root"}
    }
  ],
  "role": "dev"
}
EOF
```

... the API request will fail, indicating that resident services cannot change the role for existing instances. In case you would like to proceed, so that new instances are allocated to the new role, then you can do so by appending the parameter "?force=true":

```
cat <<EOF | curl -v -X PUT http://localhost:8080/v2/pods/dev/bigbusiness?force=true -H "Content-Type: application/json" --data "@-"
{
  "id": "/dev/bigbusiness",
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "pwd; ls -la; date >> le-data/launches.txt; sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "volumeMounts": [{
        "name": "data",
        "mountPath": "le-data"
      }]
    }
  ],
  "volumes": [
    {
      "name": "data",
      "persistent": {"size": 16, "type": "root"}
    }
  ],
  "role": "dev"
}
EOF
```

The deployment may take a little bit of time to succeed. Check your pod spec role versus the instance role:

```
$ curl http://localhost:8080/v2/pods/dev/bigbusiness::status | jq '{specRole: .spec.role, instanceRoles: [.instances[].role]}'

{
  "specRole": "dev",
  "instanceRoles": [
    "slave_public"
  ]
}
```

You can see your spec's role was updated, but the instance you launched prior to the update continues to use the role `slave_public`. This means your reservation was preserved.

Let's scale up the pod!

```
cat <<EOF | curl -v -X PUT http://localhost:8080/v2/pods/dev/bigbusiness?force=true -H "Content-Type: application/json" --data "@-"
{
  "id": "/dev/bigbusiness",
  "scaling": {"instances": 2, "kind": "fixed"},
  "containers": [
    {
      "name": "sleep1",
      "exec": { "command": { "shell": "pwd; ls -la; date >> le-data/launches.txt; sleep 1000" } },
      "resources": { "cpus": 0.1, "mem": 32 },
      "volumeMounts": [{
        "name": "data",
        "mountPath": "le-data"
      }]
    }
  ],
  "volumes": [
    {
      "name": "data",
      "persistent": {"size": 16, "type": "root"}
    }
  ],
  "role": "dev"
}
EOF
```

Then, check the role of your instances now that you have a freshly added instance after the role change:

```
curl http://localhost:8080/v2/pods/dev/bigbusiness::status | jq '{role: .spec.role, instanceRoles: [.instances[].role]}'

{
  "role": "dev",
  "instanceRoles": [
    "slave_public",
    "dev"
  ]
}
```

You can see that the new pod instance has the new role, `dev`, while the old pod instance continues to have the old role, `slave_public`. Moving forward, all new instances of the bigbusiness pod will be launched with the new role, `dev`.

# Group Role Enforcement

Marathon allows the mixed role usage in order to support a reasonable, incremental upgrade pathway for users wishing to migrate their existing Marathon instances to use multi-role. However, as stated in the introduction, this isn't the ideal. Ideally, Marathon is configured to enforce services to use the group role. This is why the `enforceRole` property exists on groups.

## Using `enforceRole`

The group `enforceRole` property is settable only for top-level groups; when enabled, it forces all **new** services in that group (or in subgroups) to use the group role, and receive the group role as a default. Existing services still must be migrated to multi-role, by modifying the respective `role` field for each of the existing services.

As an example, say you have deployed the following bigbusiness app, using the default role:

```
{
  "id": "/dev/bigbusiness",
  "cmd": "set; sleep 3600",
  "instances": 10,
  "cpus": 0.05,
  "mem": 128,
  "role": "slave_public"
}
```

Now, use the PATCH method to enable role enforcement for a group:

```
$ echo '{"enforceRole": true}' | curl -f -X PATCH http://localhost:8080/v2/groups/dev -H "Content-Type: application/json" --data "@-"
```

Note: the API will simply respond with an empty entity, with a 200 status if the operation is successful. You can check the value for the `enforceRole` field:

```
$ curl http://localhost:8080/v2/groups/dev | jq .enforceRole

true
```

Now that `enforceRole` is enabled for the top-level group `/dev`, you can see that deploying a new app will receive the group role, `dev`, by default:

```
$ cat <<EOF | curl -f -X POST http://localhost:8080/v2/apps -H "Content-Type: application/json" --data "@-"
{
  "id": "/dev/bigbusiness-role-enforced",
  "cmd": "set; sleep 3600",
  "instances": 10,
  "cpus": 0.05,
  "mem": 128
}
EOF

{
  "id":"/dev/bigbusiness-role-enforced",
  ...
  "role":"dev"
}
```

From this point on, if you try to update any service in the top-level group `/dev` from the role `dev` back to `slave_public` then you will get a validation rejection.

```
$ echo '{"role": "slave_public"}' | curl -v  -X PATCH http://localhost:8080/v2/apps/dev/bigbusiness-role-enforced -H "Content-Type: application/json" --data "@-"

...
{"message":"Object is not valid","details":[{"path":"/role","errors":["got slave_public, expected one of: [dev]"]}]}
```

You are allowed to modify services in `/dev` use the old role `slave_public` but, after you migrated, it is rejected:

```
$ echo '{"instances": 2, "role": "slave_public"}' | curl -v  -X PATCH http://localhost:8080/v2/apps/dev/bigbusiness -H "Content-Type: application/json" --data "@-"

...
{"version":"2019-08-18T21:11:33.328Z","deploymentId":"d1f5654c-95f9-45ca-9fdd-0a08eceba7fb"}

$ echo '{"role": "dev"}' | curl -v  -X PATCH http://localhost:8080/v2/apps/dev/bigbusiness -H "Content-Type: application/json" --data "@-"

...
{"version":"2019-08-18T21:12:34.259Z","deploymentId":"ac0930bf-8141-4452-967e-9f4b66229cef"}

$ echo '{"role": "slave_public"}' | curl -v  -X PATCH http://localhost:8080/v2/apps/dev/bigbusiness -H "Content-Type: application/json" --data "@-"

...
{"message":"Object is not valid","details":[{"path":"/role","errors":["got slave_public, expected one of: [dev]"]}]}
$
```

## Enabling `enforceRole` for all new groups, by default

When you begin migrating Marathon to use multi-role, it is recommended to turn on role enforcement for all new groups, by default. This is controlled by specifying the command line parameter open `--new_group_enforce_role top`.

Note, providing this command-line parameter does not affect existing services or existing groups. You must enable `enforceRole` for existing groups manually using the PATCH /v2/groups method, as seen above.

# Allocation vs Reservation (acceptedResourceRoles)

With the introduction of the `role` field, the field `acceptedResourceRoles` is easily misunderstood. It is very important to recognize there are [two separate concepts](http://mesos.apache.org/documentation/latest/reservation/#offer-operation-reserve-without-reservation_refinement) with regards to roles in Mesos:

* **Allocation role** - the role for which some resource is currently allocated. For example, unreserved resources and resources reserved for `dev` can be **allocated** for the role `dev`.
* **Reservation role** - the role for which some resource is currently **reserved**.

With this in mind, given a service with the role `dev`, only the following `acceptedResourceRoles` field values are valid, and have the following interpretation:

* `["*"]` - only unreserved resources will be considered; resources already reserved for the role `dev` will be declined.
* `["*", "dev"]` - both unreserved resources and resources already reserved for the role `dev` will be considered for offer matching.
* `["dev"]` - only resources already reserved for `dev` will be considered for offer matching. Unreserved resources will be declined.

It is invalid to specify a role other than the service role or `"*"` in `acceptedResourceRoles`. In Marathon 1.9, invalid roles in `acceptedResourceRoles` will be automatically removed, and `acceptedResourceRoles` will default to `["*"]` if all specified roles are removed. In Marathon 1.10 and later, invalid resource roles will be rejected with a validation error.

# Mesos Permissions Caveats

It is vitally important that Marathon has access to use the roles that are specified in services.

Marathon blindly assumes it has the permissions to **both** create and use the new roles specified. If Marathon attempts to subscribe to a role to which it does not have permission, then this results in a non-functional Marathon, as Mesos reacts to unauthorized role subscriptions by dropping the connection.

See the [http://mesos.apache.org/documentation/latest/authorization/](Authorization) section in Mesos for documentation on the respective permissions. In DC/OS, the appropriate permissions are granted for the root Marathon out-of-the-box.
