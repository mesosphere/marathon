## PUT|POST `/v2/artifacts/{path}`

Upload an artifact to the artifact store.
A multipart form upload request has to be performed.
The form parameter name has to be ```file```.
The path used to store the file is taken from the url path.
The response holds the URL of the artifact in the artifact store in the Location Header.
Either PUT or POST requests are allowed to perform this operation.

**Request:**


```
PUT /v2/artifacts/special/file/name.txt HTTP/1.1
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
