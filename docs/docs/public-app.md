---
title: Make an Application Public
---

# Make an Application Public

If you are using DC/OS, you can make a Marathon application accessible to those outside the cluster by adding labels to your application definition. The DC/OS [Admin Router](https://github.com/mesosphere/adminrouter-public) reads these labels and exposes your app at /service/<marathon-app-name>.

These labels are

- `DCOS_SERVICE_NAME`: The name of your Marathon app.
- `DCOS_SERVICE_PROTOCOL`: This can be `https` or `http`.
- `DCOS_SERVICE_PORT_INDEX`: The port index can be any value greater than or equal to 0.


# Example Usage

```json
"labels": [
   {
     "DCOS_SERVICE_NAME": "my-marathon-app",
     "DCOS_SERVICE_PROTOCOL": "https",
     "DCOS_SERVICE_PORT_INDEX": "3"
   }
]
```
