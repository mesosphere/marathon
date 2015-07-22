## POST `/v2/artifacts`

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
