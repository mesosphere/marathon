## DELETE `/v2/artifacts/{path}`

Delete an artifact from the artifact store.
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
