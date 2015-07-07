## DELETE `/v2/queue/{app_id}/delay`

The application specific task launch delay can be reset by calling this endpoint 

### Example 

**Request:**

```
DELETE /v2/queue/myapp/delay HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Content-Length: 0
Host: localhost:8080
User-Agent: HTTPie/0.9.2
```

**Response:**

```
HTTP/1.1 204 No Content
Cache-Control: no-cache, no-store, must-revalidate
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)
```

If there is no application in the queue with this identifier a HTTP 404 is returned.