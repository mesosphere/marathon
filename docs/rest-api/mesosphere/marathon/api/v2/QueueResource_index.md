## GET `/v2/queue`

List all the tasks queued up or waiting to be scheduled.  
This is mainly used for troubleshooting and occurs when scaling 
changes are requested and the volume of scaling changes out paces the ability to schedule those tasks.
  
In addition to the application in the queue, you see also the task count that needs to be started.

If the task has a rate limit, then a delay to the start gets applied.
You can see this delay for every application with the seconds to wait before the next launch will be tried.


### Example (as JSON)

**Request:**

```
GET /v2/queue HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Host: localhost:8080
User-Agent: HTTPie/0.9.2
```

**Response:**

```
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, must-revalidate
Content-Type: application/json
Expires: 0
Pragma: no-cache
Server: Jetty(8.1.15.v20140411)
Transfer-Encoding: chunked

{
    "queue": [
        {
            "app": {
                "id": "/my/app", 
                "instances": 100, 
                "maxLaunchDelaySeconds": 3600, 
                "mem": 16.0, 
                "requirePorts": false, 
                "version": "2015-07-06T15:29:04.554Z"
            }, 
            "count": 100, 
            "delay": {
                "overdue": false, 
                "timeLeftSeconds": 784
            }
        }
    ]
}

```

