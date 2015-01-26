var AppCollectionMockData = {
  "apps": [{
    "id": "/app-33",
    "cmd": "sleep 222",
    "args": null,
    "user": null,
    "env": {},
    "instances": 5,
    "cpus": 0.1,
    "mem": 16.0,
    "disk": 0.0,
    "executor": "",
    "constraints": [],
    "uris": [],
    "storeUrls": [],
    "ports": [10000],
    "requirePorts": false,
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "maxLaunchDelaySeconds": 3600,
    "container": null,
    "healthChecks": [],
    "dependencies": [],
    "upgradeStrategy": {
      "minimumHealthCapacity": 1.0
    },
    "version": "2015-01-10T21:29:06.246Z",
    "tasksStaged": 0,
    "tasksRunning": 0,
    "deployments": []
  }, {
    "id": "/docker-app",
    "cmd": null,
    "args": [],
    "user": null,
    "env": {},
    "instances": 1,
    "cpus": 0.1,
    "mem": 10.0,
    "disk": 0.0,
    "executor": "",
    "constraints": [],
    "uris": [],
    "storeUrls": [],
    "ports": [10004],
    "requirePorts": false,
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "maxLaunchDelaySeconds": 3600,
    "container": {
      "type": "DOCKER",
      "volumes": [{
        "containerPath": "/etc/a",
        "hostPath": "/var/data/a",
        "mode": "RO"
      }, {
        "containerPath": "/etc/b",
        "hostPath": "/var/data/b",
        "mode": "RW"
      }],
      "docker": {
        "image": "foobar",
        "privileged": false,
        "parameters": {}
      }
    },
    "healthChecks": [],
    "dependencies": [],
    "upgradeStrategy": {
      "minimumHealthCapacity": 1.0
    },
    "version": "2014-10-14T16:59:44.497Z",
    "tasksStaged": 0,
    "tasksRunning": 0,
    "deployments": []
  }, {
    "id": "/my-app",
    "cmd": "ruby toggleServer.rb -p ",
    "args": null,
    "user": null,
    "env": {},
    "instances": 1,
    "cpus": 0.1,
    "mem": 10.0,
    "disk": 0.0,
    "executor": "",
    "constraints": [],
    "uris": [],
    "storeUrls": [],
    "ports": [10001],
    "requirePorts": false,
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "maxLaunchDelaySeconds": 3600,
    "container": null,
    "healthChecks": [{
      "path": "/",
      "protocol": "HTTP",
      "portIndex": 0,
      "gracePeriodSeconds": 15,
      "intervalSeconds": 10,
      "timeoutSeconds": 2,
      "maxConsecutiveFailures": 3
    }],
    "dependencies": [],
    "upgradeStrategy": {
      "minimumHealthCapacity": 1.0
    },
    "version": "2014-10-02T16:49:36.448Z",
    "tasksStaged": 0,
    "tasksRunning": 0,
    "deployments": []
  }, {
    "id": "/ubuntu",
    "cmd": "while sleep 10; do date -u +%T; done",
    "args": null,
    "user": null,
    "env": {},
    "instances": 0,
    "cpus": 0.5,
    "mem": 512.0,
    "disk": 0.0,
    "executor": "",
    "constraints": [],
    "uris": [],
    "storeUrls": [],
    "ports": [10002],
    "requirePorts": false,
    "backoffSeconds": 1,
    "backoffFactor": 1.15,
    "maxLaunchDelaySeconds": 3600,
    "container": {
      "type": "DOCKER",
      "volumes": [{
        "containerPath": "/etc/a",
        "hostPath": "/var/data/a",
        "mode": "RO"
      }, {
        "containerPath": "/etc/b",
        "hostPath": "/var/data/b",
        "mode": "RW"
      }],
      "docker": {
        "image": "foobar",
        "privileged": false,
        "parameters": {}
      }
    },
    "healthChecks": [],
    "dependencies": [],
    "upgradeStrategy": {
      "minimumHealthCapacity": 1.0
    },
    "version": "2014-10-09T23:23:06.703Z",
    "tasksStaged": 0,
    "tasksRunning": 0,
    "deployments": []
  }]
};

module.exports = AppCollectionMockData;
