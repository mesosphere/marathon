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
    "filters": [],
    "eventType": "subscribe_event"
}
```

Messages could be filtered by event type. To register filters provide JSON body
with event filters definitions.

### Example

example below will subscribe callback that recieve only events with type
`deployment_info`,`deployment_step_success` or `api_post_event`


**Request:**

```
POST /v2/eventSubscriptions?callbackUrl=http://127.0.0.1:8001 HTTP/1.1
Content-Type: application/json
Cache-Control: no-cache


[
    {
      "query": "event_type",
      "values": ["deployment_info","deployment_step_success","api_post_event"]
    }
]
```

**Response**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
  "clientIp": "127.0.0.1",
  "callbackUrl": "http://127.0.0.1:8001",
  "filters": [
    {
      "query": "event_type",
      "values": [
        "deployment_info",
        "deployment_step_success",
        "api_post_event"
      ]
    }
  ],
  "eventType": "subscribe_event",
  "timestamp": "2016-11-19T22:31:07.573Z"
}
```