## POST `/v2/eventSubscriptions/exec?execCmd={execCmd}`

Register a exec command as event subscriber.

_NOTE: To activate this endpoint, you need to startup Marathon with `--event_subscriber exec_callback`_

### Example

**Request:**


```
POST /v2/eventSubscriptions/exec?execCmd=tee%20/tmp/marathon-event HTTP/1.1
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
    "execCmd": "tee /tmp/marathon-event",
    "clientIp": "127.0.0.1",
    "eventType": "subscribe_event"
}
```