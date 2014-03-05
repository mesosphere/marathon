## POST `/v2/eventSubscriptions?callbackUrl={callbackUrl}`

Register a callback URL as event subscriber.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber http_callback`_

### Example

**Request:**


```
POST /v2/eventSubscriptions?callbackUrl=http://localhost:9292/callback HTTP/1.1
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
    "eventType": "subscribe_event"
}
```