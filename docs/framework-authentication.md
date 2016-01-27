---
title: Framework Authentication
---

# Framework Authentication

Mesos 0.15 added support for [framework authentication](http://mesos.apache.org/documentation/latest/authentication/). Using that, you can control

- as which unix user a framework can launch tasks
- for which roles a given principal can register a framework
- who can actually shutdown a framework

The details of defining rules is described in the Mesos documentation linked above. In order to use certain features of Mesos, a framework needs to authenticate its requests. Setting up framework authentication requires the following steps. We'll use a simple non-restrictive example to illustrate them here and place the config files under `/tmp/mesos/config/`.

1. Create a file containing your ACLs under `/tmp/mesos/config/acls` with the following content. This one basically states that a framework authenticating with ANY principal may run tasks as ANY user, and a framework registered with ANY principal can register for ANY role.
```
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
2. Create a file defining framework principals and their secrets under `/tmp/mesos/config/credentials` with the following content.
```
marathon marathonsecret
```
3. Create a file containing the secret for `marathon` under `/tmp/mesos/config/marathon.secret`
```
marathonsecret
```
4. Start the Mesos master process using
```
--acls=file:///tmp/mesos/config/acls
--credentials=file:///tmp/mesos/config/credentials
```
5. Start Marathon using the following command line arguments
```
--mesos_authentication_principal marathon
--mesos_authentication_secret_file /tmp/mesos/config/marathon.secret
--mesos_role foo
```
*Note:* the framework must be registered for a specific role only in case you want to use Mesos features that require specifying a role for a request.

The Marathon log should include lines similar to these, which means Marathon is successfully authenticated:
```
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
```