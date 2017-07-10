---
title: Secret Configuration
---

Marathon has a pluggable interface for secret store providers. The secrets API provides a way for applications to consume sensitive data without exposing that data directly via API objects. For example, you can use a secret to securely provide a database password that is needed by a service or pod without embedding the password itself into the Marathon app or pod JSON.

Secrets are an opt-in feature in Marathon. Enable them by specifying `secrets` with the `--enable_features` command line flag.

Marathon does not ship with a default secrets plugin implementation out-of-the-box. If you enable the `secrets` feature without providing and configuring a plugin, Marathon will consume API objects that use secrets, but no secrets will actually be passed to apps/pods at launch time.

Marathon plugins are configured using the `--plugin_dir` and `--plugin_conf` command line flags. For further information regarding plugin development and configuration see [Extend Marathon with Plugins](plugin.md).

There are two ways to consume secrets in an app or pod definition: as either an environment variable or a container volume (a file-based secret).

**Important**: Marathon will only provide the API to configure and store these secrets. You need to write and register a plugin that interprets these secrets.

# Environment variable-based secrets
The environment API allows you to reference a secret as the value of an environment variable.

## Example configuration of environment variable-based secrets for app definitions
In the example below, the secret is under the environment variable `"DATABASE_PW"`. Observe how the `"env"` and `"secrets"` objects are used to define environment variable-based secrets.

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

## Example configuration of environment variable-based secrets for pod definitions
In the example below, the secret is under the environment variable `"DATABASE_PW"`. Observe how the `"environment"` and `"secrets"` objects are used to define environment variable-based secrets.

```json
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

# File-based secrets
The file-based secret API allows you to reference a secret as a file at a particular path in a container's file system. File-based secrets are available in the sandbox of the task (`$MESOS_SANDBOX/<configured-path>`).

## Example configuration of file based secrets for app definitions

```json
{
  "id": "app-with-secrets",
  "cmd": "sleep 100",
  "container": {
    "volumes": [
      {
        "containerPath": "path/to/db/password",
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

In the example above, the secret will have the filename `path/to/db/password` and will be available in the task's sandbox (`$MESOS_SANDBOX/path/to/db/password`).

## Example configuration of file based secrets for pod definitions

```json
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

In the example above, the secret will have the filename `path/to/db/password` and will be available in the task's sandbox (`$MESOS_SANDBOX/path/to/db/password`).
