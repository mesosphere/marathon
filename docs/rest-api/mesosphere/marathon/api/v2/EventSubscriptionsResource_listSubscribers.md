## GET `/v2/eventSubscriptions`

List all event subscriber callback URLs.

_NOTE: To activate this endpoint, you need to startup a Marathon instance with `--event_subscriber http_callback`_

### Example

**Request:**

```
GET /v2/eventSubscriptions HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "callbackUrls": [
        "http://localhost:9090/callback",
        "http://localhost:9191/callback"
    ]
}
```
