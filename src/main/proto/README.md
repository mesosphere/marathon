# Google Protocol Buffers
 
marathon is using google protocol buffers to marshal and unmarshal data from the persistent store.
See: https://developers.google.com/protocol-buffers for more information.

## Dependencies

- google protocol buffers should be installed on the system.
- the mesos.proto is needed from the related mesos version. It is part of the mesos sources.
  The file must be available in the same directory as the marathon.proto.

## Rebuilt the protos

To rebuild the protoc, open a terminal where the current working directory is the project directory. 

$> cd src/main/proto
$> protoc --java_out=.. marathon.proto 

 
