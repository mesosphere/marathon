# Marathon Observation Deck

A 3D Visualisation Tool for Marathon


# Testing

This tool comes with a script, `capture.sh` which is able to "capture" the JSON
responses from a runnng instance of Marathon. The script will send a `curl` GET
request every second to the Marathon UI `/v2/apps` endpoint and save the JSON to
 a text file. This file can then be fed to the `server/index.js` which will
replay the log at one line per request.

To start capturing a Marathon session simply do:

```
./capture.sh [marathon_host:port]

Example:
./capture.sh http://localhost:8080
```

This will start polling the specified address and save the output to a file with
 a filename of the format `yyyy-mm-dd-hh:mm:ss.log`.

To have the server script replay the log, simply copy this file over to
`server/data.log` and set line 7 of `data.js` to hit the `/replay` endpoint.
