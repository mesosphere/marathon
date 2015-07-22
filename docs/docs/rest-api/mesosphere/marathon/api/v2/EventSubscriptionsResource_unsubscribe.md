## DELETE `/v2/eventSubscriptions?callbackUrl={callbackUrl}`

Unregister a callback URL from the event subscribers list.

_NOTE: To activate this endpoint, you need to startup a Marathon instance with `--event_subscriber http_callback`_

### Example

**Request:**


```
DELETE /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
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
    "callbackUrl": "http://localhost:9292/callback",
    "clientIp": "127.0.0.1",
    "eventType": "unsubscribe_event"
}
```
