# Artifact Store

Deployments inside a distributed system need a location, where application specific resources can be found.
In Marathon we call this place the artifact store.


## Usage

The use of the Marathon Artifact Store functionality is completely optional.
All functionality and goals can be accomplished manually as well.
Marathon tries to simplify several use cases.


## Artifact Store Backend

Marathon supports different storage system, that can be used as artifact store.
The type of the artifact store is configured via the command line.
Example: start with --artifact_store hdfs://localhost:54310/path/to/store to use Hadoop DFS 
as artifact storage backend.


## Artifact REST endpoint

There is a special endpoint to access and manipulate the artifacts in the artifact store.
The URL's of the created artifacts can be used as URI's of an application definition.

### Upload an artifact to the artifact store

Upload an artifact to the artifact store.
A multipart form upload request has to be performed.
The form parameter name has to be ```file```.
The filename used in the artifact store, is the same as given by the form parameter.
The response holds the URL of the artifact in the artifact store in the Location Header.

**Request:**
```
POST /v2/artifacts HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 158
Content-Type: multipart/form-data; boundary=f1902a5119af474791bc48395048a8f2
Host: localhost:8080
User-Agent: HTTPie/0.8.0

--f1902a5119af474791bc48395048a8f2
Content-Disposition: form-data; name="file"; filename="test.txt"

...Content of the file...

--f1902a5119af474791bc48395048a8f2--
```

**Response:**
```
HTTP/1.1 201 Created
Content-Length: 0
Location: hdfs://hd.cluster.bare.org:54310/artifact/test.txt
Server: Jetty(8.1.11.v20130520)
```

If you want to specify a specific path in the artifact store, specify the path in the url:

**Request:**
```
POST /v2/artifacts/special/file/name.txt HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 158
Content-Type: multipart/form-data; boundary=d6f4e71fe7db4197bc2a4f5666e65917
Host: localhost:8080
User-Agent: HTTPie/0.8.0

--d6f4e71fe7db4197bc2a4f5666e65917
Content-Disposition: form-data; name="file"; filename="test.txt"

...Content of the file...

--d6f4e71fe7db4197bc2a4f5666e65917--

```

**Response:**
```
HTTP/1.1 201 Created
Content-Length: 0
Location: hdfs://localhost:54310/artifact/special/file/name.txt
Server: Jetty(8.1.11.v20130520)

```

### Get an artifact from the artifact store

The path is the relative path in the artifact store.

**Request:**
```
GET /v2/artifacts/special/file/name.txt HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**
```
HTTP/1.1 200 OK
Content-Length: 14
Content-Type: text/plain
Last-Modified: Tue, 22 Jul 2014 11:52:23 GMT
Server: Jetty(8.1.11.v20130520)

...Content of the file...
```

### Delete an artifact from the artifact store

The path is the relative path in the artifact store.

**Request:**
```
DELETE /v2/artifacts/special/file/name.txt HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**
```
HTTP/1.1 200 OK
Content-Length: 0
Content-Type: application/json
Server: Jetty(8.1.11.v20130520)
```


## Automatic Artifact Storing

An AppDefinition holds a sequence of URIs, that get fetched on each instance, that gets started.
The AppDefinition has a field storeUrls, which holds an array of URL strings.
Every URL here is processed in this way:

* The URL gets downloaded
* The byte stream is stored in the asset store
* The asset store url is added to the AppDefinition uris list
* The url is removed from the storeUrls array

As a result, all storeUrls urls are accessible from the artifact store.
All instances that will run the application, will load the needed assets from the artifact store.


### Create an application definition with automatic artifact resolution

**Request:**
```
POST /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate
Content-Length: 197
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.8.0

{
    "cmd": "python toggle.py $PORT0", 
    "cpus": 0.2, 
    "id": "app", 
    "instances": 2, 
    "mem": 32, 
    "ports": [
        0
    ], 
    "storeUrls": [
        "http://downloads.mesosphere.io/misc/toggle.tgz"
    ]
}
```

**Response**
```
HTTP/1.1 201 Created
Content-Type: application/json
Location: http://localhost:8080/v2/apps/mongo2
Server: Jetty(8.1.11.v20130520)
Transfer-Encoding: chunked

{
    "deploymentId": "910ae97f-3f3d-4fdb-a9a1-9d72f0ae8e49", 
    "version": "2014-07-22T13:25:52.319Z"
}
```

When the app gets deployed, all storeUrls will be stored in the artifact store and
the definition will be adapted and will look like this:

**Request:**
```
GET /v2/apps/app HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response**
```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.1.11.v20130520)
Transfer-Encoding: chunked

{
    "app": {
        "cmd": "python toggle.py $PORT0 mongo2 v1", 
        "cpus": 0.2, 
        "id": "/app", 
        "instances": 2, 
        "mem": 32.0, 
        "ports": [
            10001
        ], 
        "storeUrls": [], 
        "uris": [
            "hdfs://localhost:54310/artifact/10f271c27fd780a37b4319c2c12f0ba5/toggle.tgz"
        ] 
    }
}
```



### Automatic Path creation
 
The path in the asset store is computed that way:

* MD5 sum of the complete URL builds the folder
* filename of the url remains the same

The complete path is {artifact store base}/{md5 of url}/{filename of asset}

### Prerequisites

To use this feature, all assets need to be resolvable by marathon itself.
