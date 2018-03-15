#### DELETE `/v2/deployments/{deploymentId}`

##### Parameters

<table class="table table-bordered">
  <thead>
    <tr>
      <th>Name</th>
      <th>Type</th>
      <th>Description</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>force</code></td>
      <td><code>boolean</code></td>
      <td>
        If set to <code>false</code> (the default) then the deployment is
        canceled and a new deployment is created to revert the changes of this
        deployment. Without concurrent deployments, this restores the configuration before this
        deployment. If set to <code>true</code>, then the deployment
        is still canceled but no rollback deployment is created.
        Default: <code>false</code>.</td>
    </tr>
  </tbody>
</table>

##### Example

Revert the deployment with `deploymentId` by creating a new deployment which reverses
all changes.

**Request:**

```http
DELETE /v2/deployments/867ed450-f6a8-4d33-9b0e-e11c5513990b HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 0
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
Transfer-Encoding: chunked

{
    "deploymentId": "0b1467fc-d5cd-4bbc-bac2-2805351cee1e",
    "version": "2014-08-26T08:20:26.171Z"
}
```

##### Example

Cancel the deployment with `deploymentId`, and do not create a new rollback deployment.

**Request:**

```http
DELETE /v2/deployments/177b7556-1287-4e09-8432-3d862981a987?force=true HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Content-Length: 0
Host: mesos.vm:8080
User-Agent: HTTPie/0.8.0
```

**Response:**

```http
HTTP/1.1 202 Accepted
Content-Length: 0
Content-Type: application/json
Server: Jetty(8.y.z-SNAPSHOT)
```