---
title: Framework Authentication
---

# Framework Authentication

Mesos 0.15 added support for framework authentication. Framework authentication allows you to specify:

- which Unix user to launch the task as
- which roles a framework can be registered as
- who can shut down a framework

Use the [Mesos documentation](http://mesos.apache.org/documentation/latest/authentication/) to define the framework authentication rules.

## Configure Framework Authentication for Marathon

Here we use a simple non-restrictive example to illustrate the steps and place the config files under `/tmp/mesos/config/`.

1. Create an ACL. Create a file called `acls` in `/tmp/mesos/config/` with the following content:

```json
        {
          "run_tasks": [
            {
              "principals": {
                "type": "ANY"
              },
              "users": {
                "type": "ANY"
              }
            }
          ],
          "register_frameworks": [
            {
              "principals": {
                "type": "ANY"
              },
              "roles": {
                "type": "ANY"
              }
            }
          ]
        }
```

1. Define framework principals and their secrets. Create a file called `credentials` in `/tmp/mesos/config/` with the following content:

        {
            "credentials": [
                {
                "principal": "marathon",
                "secret": "marathonsecret"
                }
            ]
        }

1. Create a file called `marathon.secret` with the secret for `marathon` in `/tmp/mesos/config/`:

        marathonsecret

1. Start the Mesos master process with the following arguments:

        --acls=file:///tmp/mesos/config/acls
        --credentials=file:///tmp/mesos/config/credentials

1. Start Marathon using the following command line arguments

        --mesos_authentication
        --mesos_authentication_principal marathon
        --mesos_authentication_secret_file /tmp/mesos/config/marathon.secret
        --mesos_role foo

**Note:** If you want to use Mesos features that require specifying a role for a request, register your framework with that role only.

When Marathon is successfully authenticated, you should see lines similar to the following in the Marathon log:

        I0126 17:49:22.245414 571383808 sched.cpp:318] Authenticating with master master@127.0.0.1:5050
        I0126 17:49:22.245434 571383808 sched.cpp:325] Using default CRAM-MD5 authenticatee
        I0126 17:49:22.245774 506134528 authenticatee.cpp:91] Initializing client SASL
        I0126 17:49:22.254290 506134528 authenticatee.cpp:115] Creating new client SASL connection
        I0126 17:49:22.255765 571383808 authenticatee.cpp:206] Received SASL authentication mechanisms: CRAM-MD5
        I0126 17:49:22.255839 571383808 authenticatee.cpp:232] Attempting to authenticate with mechanism 'CRAM-MD5'
        I0126 17:49:22.256511 543682560 authenticatee.cpp:252] Received SASL authentication step
        I0126 17:49:22.257164 512196608 authenticatee.cpp:292] Authentication success
        I0126 17:49:22.257333 530903040 sched.cpp:407] Successfully authenticated with master master@127.0.0.1:5050
        I0126 17:49:22.258972 506134528 sched.cpp:640] Framework registered with 20160126-165754-16777343-5050-35782-0000
