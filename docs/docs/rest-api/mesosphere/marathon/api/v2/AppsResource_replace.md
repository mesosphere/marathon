#### PUT `/v2/apps/{appId}`

Replaces parameters of a running application. If no application with the given id
exists, it will be created. If there is an application with this id, all running instances get 
upgraded to the new definition. 


##### Example

**Request:**

```
PUT /v2/apps/myapp HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 126
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

{
    "cmd": "sleep 55",
    "constraints": [
        [
            "hostname",
            "UNIQUE",
            ""
        ]
    ],
    "cpus": "0.3",
    "instances": "2",
    "mem": "9",
    "ports": [
        9000
    ]
}
```

**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```

or

```
HTTP/1.1 201 Created
Content-Length: 0
Content-Type: application/json
Location: http://localhost:8080/v2/apps/myApp
Server: Jetty(8.y.z-SNAPSHOT)


```
