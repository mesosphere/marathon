## GET `/v2/apps`

List all running applications.
The list of running applications can be filtered by application identifier, command and label selector.
All filters can be applied at the same time.

### Example

**Request:**

```
GET /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 0, 
            "tasksStaged": 1, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

## GET `/v2/apps?cmd={command}`

List all running applications, filtered by `command`.
Note: the specified command must be contained in the applications command (substring). 

### Example

**Request:**

```
GET /v2/apps?cmd=sleep%2060 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

## GET `/v2/apps?id={identifier}`

List all running applications, filtered by `id`.
Note: the specified id must be contained in the applications id (substring). 

### Example

**Request:**

```
GET /v2/apps?id=my HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

## GET `/v2/apps?label={labelSelectorQuery}`

List all running applications, filtered by `labelSelectorQuery`.
This filter selects applications by application labels.


### Label Selector Query

A label selector query contains one or more label selectors, which are comma separated.
Marathon supports three types of selectors: existence-based, equality-based and set-based. 
In the case of multiple selectors, all must be satisfied so comma separator acts as an AND logical operator.
Labels and values must consist of alphanumeric characters plus `-` `_` and `.`: `-A-Za-z0-9_.`. 
Any other character is possible, but must be escaped with a backslash character.

Example:
`environment==production, tier!=frontend\ tier, deployed in (us, eu), deployed notin (aa, bb)`  

#### Existence based Selector Query

Matches the existence of a label.
  
- {{label}}
 
Example:

- environment
 

#### Equality based Selector Query
  
Matches existence of labels and the (non) equality of the value.

- {{label}} == {{value}}
- {{label}} != {{value}} 

Example:
 
- environment = production
- tier != frontend

#### Set based Selector Query  

Matches existence of labels and the (non) existence of the value in a given set. 

- {{label}} in ( {{value}}, {{value}}, ... , {{value}} ) 
- {{label}} notin ( {{value}}, {{value}}, ... , {{value}} ) 

Example:

- environment in (production, qa)
- tier notin (frontend, backend)



### Example

**Request:**

```
GET /v2/apps?label=foo%3d%3done%2c+bla!%3done%2c+foo+in+(one%2c+two%2c+three)%2c+bla+notin+(one%2c+two%2c+three)%2c+existence%22 HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "labels": {
                "foo": "one", 
                "bla": "four", 
                "existence": "yes"
            }, 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```

## GET `/v2/apps/product/us-east/*

List all running applications, which live in the namespace /product/us-east

### Example

**Request:**

```
GET /v2/apps/product/us-east/* HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
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
    "apps": [
        {
            "id": "/product/us-east/service/myapp", 
            "cmd": "env && sleep 60", 
            "constraints": [
                [
                    "hostname", 
                    "UNIQUE", 
                    ""
                ]
            ], 
            "container": null, 
            "cpus": 0.1, 
            "env": {
                "LD_LIBRARY_PATH": "/usr/local/lib/myLib"
            }, 
            "executor": "", 
            "instances": 3, 
            "mem": 5.0, 
            "ports": [
                15092, 
                14566
            ], 
            "tasksRunning": 1, 
            "tasksStaged": 0, 
            "uris": [
                "https://raw.github.com/mesosphere/marathon/master/README.md"
            ], 
            "version": "2014-03-01T23:42:20.938Z"
        }
    ]
}
```
