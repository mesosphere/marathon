## DELETE `/v2/apps/{app_id}/tasks?host={host}&scale={true|false}`

Kill tasks that belong to the application `app_id`, optionally filtered by the task's `host`.

The query parameters `host` and `scale` are both optional.  If `host` is specified, only tasks running on the supplied slave are killed.  If `scale=true` is specified, then the application is scaled down by the number of killed tasks.  The `scale` parameter defaults to `false`.

### Example

**Request:**

```
DELETE /v2/apps/myApp/tasks?host=mesos.vm&scale=false HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 0
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2


```

**Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "tasks": [
        {
            "host": "mesos.vm", 
            "id": "myApp_0-1390455072426", 
            "ports": [
                31823, 
                31824
            ], 
            "stagedAt": "2014-01-23T05:31+0000", 
            "startedAt": null
        }
    ]
}
```
