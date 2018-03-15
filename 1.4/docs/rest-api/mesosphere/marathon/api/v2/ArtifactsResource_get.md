## GET `/v2/artifacts/{path}`

Download an artifact from the artifact store.
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
