#### GET `/v2/events`

Attach to the marathon event stream.

##### Server Sent Events

To use this endpoint, the client has to accept the text/event-stream content type.
Please note: a request to this endpoint will not be closed by the server.
If an event happens on the server side, this event will be propagated to the client immediately.
See [Server Sent Events](http://www.w3schools.com/html/html5_serversentevents.asp) for a more detailed explanation.

**Request:**

```
GET /v2/events HTTP/1.1
Accept: text/event-stream
Accept-Encoding: gzip, deflate
Host: localhost:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, must-revalidate
Connection: close
Content-Type: text/event-stream;charset=UTF-8
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)

```

If an event happens on the server side, it is sent as plain json prepended with the mandatory `data:` field.

**Response:**
```
data: {"remoteAddress":"96.23.11.158","eventType":"event_stream_attached","timestamp":"2015-04-28T12:14:57.812Z"}

data: {"groupId":"/","version":"2015-04-28T12:24:12.098Z","eventType":"group_change_success","timestamp":"2015-04-28T12:24:12.224Z"}
```




