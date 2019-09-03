# Google Protocol Buffers

Marathon is using Google [Protocol Buffers](https://developers.google.com/protocol-buffers) to marshal and unmarshal data from the persistent store.

`./marathon.proto` contains the basic definitions marathon uses for persistence
`./mesos/mesos.proto` are included in the `./marathon.proto` for certain fields that are mostly copied from the original mesos proto-definitions. These should be merged into the marathon and don't have any real connections to the original anymore. They are *not* to be used for communication with mesos, only for marathon internal storage use

The Protos are automatically updated in the sbt build process.