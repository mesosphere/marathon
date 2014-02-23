## GET `/v2/event_subscriptions`

List all event subscriber's callback urls.

_NOTE: To activate this endpoint, you need to startup a Marathon instance with `--event_subscriber http_callback`_

### Example

**Request:**

```
GET /v2/event_subscriptions HTTP/1.1
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

[
    "http://localhost:9090/callback",
    "http://localhost:9191/callback"
]
```

