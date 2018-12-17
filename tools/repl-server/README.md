# Ammonite REPL Server

## Overview

This subproject depends on Marathon core. It is useful to launch an instance of Marathon that also has an embedded Ammonite REPL that you can connect to and use to introspect and manipulate the internal state of Marathon for debugging purposes.

## Running

This README assumes the following:

1. You have a Zookeeper instance running at localhost:2181
2. You have a running Mesos master registered with that Zookeeper instance
3. You have an SSH key public key in $HOME/.ssh/id_rsa.pub; your ssh client will use the corresponding private key when connecting.

```
$ sbt

marathon(ammonite) > ammonite/reStart --http_address 127.0.0.1 --http_port 8080 --zk zk://localhost:2181/marathon --master zk://localhost:2181/mesos --mesos_role marathon
```

Marathon will launch by default in the foreground.

In another terminal:

```
$ ssh localhost -p 22222
```

You should be an at Ammonite REPL at this point.

## Prelude

Once connected, you'll have some things automatically provided in to your environment:

Accessors:


- `app` - Instance of the `MarathonApp`.
- `coreModule` - Top-level module in Marathon; has just about everything.
- `storageModule` - Convenience accessor for `coreModule.storageModule`.
- `actorSystem` (implicit) - the `actorSystem`
- `materializer` (implicit) - An ActorMaterializer for the `actorSystem`
- `executionContext` (implicit) - The global execution context

Methods

- `inject[T]` - Helper to access anything that can be Guice injected.
- `await[T](f: Future[T])` - Synchronously wait for the Future result; return it.

## Configuration

Set the environment variables to configure the Ammonite repl:

- `AMMONITE_HOST` - Host on which Ammonite should listen (default "localhost")
- `AMMONITE_PORT` - SSHD Port for listening. (default 22222)
- `AMMONITE_PUBKEY_PATH` - Path to the public key; (default `$HOME/.ssh/id_rsa.pub`)
- `AMMONITE_USER` - User for password auth. (default "debug")
- `AMMONITE_PASS` - Password for password auth. (default - disabled)

## Output

`println` invocations running in the Ammonite thread will be output to your SSH session. Any println / output invocations in other threads will go to the terminal running your Marathon process. Invocations of `show` will always output to the SSH session.
