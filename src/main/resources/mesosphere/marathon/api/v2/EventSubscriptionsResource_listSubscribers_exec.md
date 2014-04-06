## GET `/v2/eventSubscriptions/exec`

List all event subscriber exec commands.

_NOTE: To activate this endpoint, you need to startup a Marathon instance with `--event_subscriber exec_callback`_

### Example

**Request:**

```
GET /v2/eventSubscriptions/exec HTTP/1.1
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
    "execCmds": [
        "tee /tmp/last-marathon-event",
        "cat | /tmp/marathon-events"
    ]
}
```
