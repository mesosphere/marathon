## GET `/v2/apps/{appId}/versions`

List the versions of the application with id `appId`.

### Example

**Request:**

```
GET /v2/apps/product/service/myapp/versions HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "versions": [
        "2014-03-01T23:42:20.938Z"
    ]
}
```
