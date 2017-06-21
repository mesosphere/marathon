---
title: Secret Configuration
---

Marathon has a pluggable interface for secret store providers.
The secrets API provides a way for applications to consume sensitive data without exposing that data directly via API objects.
For example, the secrets API is useful for configuring a database password that's needed by an application or pod, without embedding the password itself into the Marathon app or pod JSON.

Secrets are an opt-in feature in Marathon and may be enabled by specifying `secrets` with the `--enable_features` command line flag.
Marathon does not ship with a default secrets plugin implementation out of the box. Enabling the `secrets` feature without providing and configuring a plugin will result in behavior
 that's confusing for end users: in this case Marathon will happily consume API objects using secrets but no secrets will actually be passed to apps/pods at launch time.

Marathon plugins are configured using the `--plugin_dir` and `--plugin_conf` command line flags.
For further information regarding plugin development and configuration please see [Extend Marathon with Plugins](plugin.md).

There are two ways to consume secrets in an app or pod definition, in either the form of an environment variable or a container volume.

**Important**: Marathon will only provide the API to configure and store these secrets. You need to write and register a plugin which interprets these secrets.


# Environment variable based secrets
The environment API facilitates the rendering of a secret as the value of an environment variable.

## Example configuration of environment variable based secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "cmd": "sleep 100",
  "env": {
    "DATABASE_PW": { 
      "secret": "secretpassword"
    }
  },
  "secrets": {
    "secretpassword": {
      "source": "databasepassword"
    }
  }
}
```

## Example configuration of environment variable based secrets for pod definitions

```
{
  "id": "/pod-with-secrets",
  "containers": [
    {
      "name": "container-1",
      "exec": {
        "command": {
          "shell": "sleep 1"
        }
      }
    }
  ],
  "environment": {
    "DATABASE_PW": { 
      "secret": "secretpassword"
    }
  },
  "secrets": {
    "secretpassword": {
      "source": "databasepassword"
    }
  }
}
```


# File based secrets
The file based secret API facilitates the rendering of a secret as a file at a particular path in a container's file system.

## Example configuration of file based secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "cmd": "sleep 100",
  "container": {
    "volumes": [
      {
        "containerPath": "path",
        "secret": "secretpassword"
      }
    ]
  },
  "secrets": {
    "secretpassword": {
      "source": "databasepassword"
    }
  }
}
```

## Example configuration of file based secrets for pod definitions

```
{
  "id": "/pod-with-secrets",
  "containers": [
    {
      "name": "container-1",
      "exec": {
        "command": {
          "shell": "sleep 1"
        }
      },
      "volumeMounts": [
        {
          "name": "secretvolume",
          "mountPath": "path/to/db/password"
        }
      ]
    }
  ],
  "volumes": [
    {
      "name": "secretvolume",
      "secret": "secretpassword"
    }
  ],
  "secrets": {
    "secretpassword": {
      "source": "databasepassword"
    }
  }
}
```
