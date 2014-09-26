# Google Protocol Buffers

Marathon is using Google [Protocol Buffers](https://developers.google.com/protocol-buffers) to marshal and unmarshal data from the persistent store.

## Dependencies

- Google [Protocol Buffers](https://developers.google.com/protocol-buffers) should be installed on the system.
- The mesos.proto is needed from the related [Mesos](http://mesos.apache.org) version. It is part of the [Mesos](http://mesos.apache.org) sources.
  The file must be available in the same directory as the marathon.proto.

## Rebuilt the protos

To rebuild the protos, open a terminal where the current working directory is the project directory.

```
$> cd src/main/proto
$> protoc --java_out=../java/ marathon.proto
```

