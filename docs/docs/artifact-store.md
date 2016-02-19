---
title: Artifact Store
---

# Artifact Store

Deployments inside a distributed system need a location where application-specific resources can be found. In Marathon, we call this place the artifact store.


## Usage

Use of the Marathon artifact store is completely optional: you can create all of the artifact atore functionality manually as well. The Marathon artifact store simplifies several use cases.


## Artifact Store Backend

Marathon supports several different storage system backends for the artifact store.

You can specify the storage system backend on the command line. To use Hadoop DFS, for example, use the following command line flag:

 --artifact_store hdfs://localhost:54310/path/to/store 


## Artifact REST Endpoint

A special endpoint enables you to access and manipulate the artifacts in the artifact store. The URLs of the created artifacts can be used as URIs of an application definition.

See the [REST API documentation](http://mesosphere.github.io/marathon/docs/generated/api.html#v2_artifacts) for more information.

### Upload an Artifact to the Artifact Store

To upload an artifact to the artifact stopre, you must perform a multipart form upload request.
The form parameter name has to be ```file```. The filename in the artifact store will be the same one you provide in the form parameter.

The location header of the response will contain the URL of the artifact in the artifact store.

**Request:**

```
http
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
http
HTTP/1.1 201 Created
Content-Length: 0
Location: hdfs://hd.cluster.bare.org:54310/artifact/test.txt
Server: Jetty(8.1.11.v20130520)
```

If you want to specify a specific path in the artifact store, specify the path in the url:

**Request:**

```
http
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
http
HTTP/1.1 201 Created
Content-Length: 0
Location: hdfs://localhost:54310/artifact/special/file/name.txt
Server: Jetty(8.1.11.v20130520)
```

### Get an artifact from the artifact store

Use the relative path in the artifact store:

**Request:**
```http
GET /v2/artifacts/special/file/name.txt HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Length: 14
Content-Type: text/plain
Last-Modified: Tue, 22 Jul 2014 11:52:23 GMT
Server: Jetty(8.1.11.v20130520)

...Content of the file...
```

### Delete an artifact from the artifact store

Use the relative path in the artifact store:

**Request:**
```http
DELETE /v2/artifacts/special/file/name.txt HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Length: 0
Content-Type: application/json
Server: Jetty(8.1.11.v20130520)
```


## Automatic Artifact Storage

An AppDefinition holds a sequence of URIs that Marathon fetches when it starts each app instance.

The artifact could be fetched directly from the source, or put into the artifact store.
One simple way to do this is automatic artifact storing.

You can automatically store artifacts in the artifact store with the ```storeURLs``` field of the AppDefinition. The ```storeURLs``` field holds an array of URL strings.
Every URL here is processed in this way:

* The URL is downloaded. 
* The byte stream is stored in the asset store.
* Marathon adds the asset store URL to the AppDefinition URIs list.
* Marathon removes the URL from the storeUrls array.

As a result, all storeUrls are accessible from the artifact store. All instances that run the application are able to load the necessary assets from the artifact store.

### Prerequisites

To use this feature, all assets need to be resolvable by marathon itself.
The HTTP server should support HTTP ETag Header in order to circumvent manual content hash creation.

### Create an Application Definition with Automatic Artifact Resolution

**Request:**
```http
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
        "http://downloads.mesosphere.com/misc/toggle.tgz"
    ]
}
```

**Response**
```http
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

When the app is deployed, all ```storeUrls``` will be stored in the artifact store and
the definition will modified to look like this:

**Request:**
```http
GET /v2/apps/app HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response**
```http
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
            "hdfs://localhost:54310/artifact/f1cc046e4b603a94d0932eb818854fcc52e1b563/toggle.tgz"
        ] 
    }
}
```



### Automatic Path creation
 
The path in the asset store is computed in the following way:

1. HEAD Request to the asset.
2. If ETag is available, take ETag HTTP header.
3. If ETag is not available, download the resource and compute the Sha-1 hash manually.
4. The filename of the url remains the same.

The complete path to the artifact is ```{artifact store base}/{ETag or ContentHash}/{filename of asset}```.

Marathon creates a path that is unique to the content of the resource.

If the same URL holds a different entity, a new path is created.

The same path is downloaded only once: if the path is available in the artifact store, it is not downloaded again.