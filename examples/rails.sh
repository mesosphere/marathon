http localhost:8080/v1/apps/start < start_rails.json
http localhost:8080/v1/apps/scale id=Rails instances=2
http localhost:8080/v1/apps/stop id=Rails
