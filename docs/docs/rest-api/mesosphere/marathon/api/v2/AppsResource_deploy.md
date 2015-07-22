#### PUT `/v2/apps`

Change multiple applications either by upgrading exisitng ones or creating new ones.

If there is an update to an already running application, the application gets upgraded.
All instances of this application get replaced by the new version.
The order of dependencies will be applied correctly.
The upgradeStrategy defines the behaviour of the upgrade.

If the id of the application is not known, the application gets started.
The order of dependencies will be applied correctly.
It is possible to mix upgrades and installs.

If you have more complex scenarios with upgrades, use the groups endpoint.


##### Example

**Request:**

```
PUT /v2/apps HTTP/1.1
Accept: application/json
Accept-Encoding: gzip, deflate, compress
Content-Length: 126
Content-Type: application/json; charset=utf-8
Host: localhost:8080
User-Agent: HTTPie/0.7.2

[{
    "id": "/test/sleep60",
    "cmd": "sleep 60",
    "cpus": "0.3",
    "instances": "2",
    "mem": "9",
    "dependencies": [ "/test/sleep120",  "/other/namespace/or/app"]
},{
    "id": "/test/sleep120",
    "cmd": "sleep 120",
    "cpus": "0.3",
    "instances": "2",
    "mem": "9"
}]
```

**Response:**

```
HTTP/1.1 204 No Content
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)


```

