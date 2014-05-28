/** @jsx React.DOM */

define([
  "React",
  "models/App"
], function(React, App) {
  var UNSPECIFIED_NODE =
    React.createClass({
      render: function() {
        return <dd className="text-muted">Unspecified</dd>;
      }
    });

  return React.createClass({
    displayName: "AppVersionComponent",

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired
    },

    render: function() {
      var app = this.props.app;

      var cmdNode = (app.get("cmd") == null) ?
        <UNSPECIFIED_NODE /> :
        <dd>{app.get("cmd")}</dd>;
      var constraintsNode = (app.get("constraints").length < 1) ?
        <UNSPECIFIED_NODE /> :
        app.get("constraints").map(function(c) {

          // Only include constraint parts if they are not empty Strings. For
          // example, a hostname uniqueness constraint looks like:
          //
          //     ["hostname", "UNIQUE", ""]
          //
          // it should print "hostname:UNIQUE" instead of "hostname:UNIQUE:", no
          // trailing colon.
          return (
            <dd key={c}>
              {c.filter(function(s) { return s !== ""; }).join(":")}
            </dd>
          );
        });
      var containerNode = (app.get("container") == null) ?
        <UNSPECIFIED_NODE /> :
        <dd>{JSON.stringify(app.get("container"))}</dd>;
      var envNode = (Object.keys(app.get("env")).length === 0) ?
        <UNSPECIFIED_NODE /> :

        // Print environment variables as key value pairs like "key=value"
        Object.keys(app.get("env")).map(function(k) {
          return <dd key={k}>{k + "=" + app.get("env")[k]}</dd>
        });
      var executorNode = (app.get("executor") === "") ?
        <UNSPECIFIED_NODE /> :
        <dd>{app.get("executor")}</dd>;
      var portsNode = (app.get("ports").length === 0 ) ?
        <UNSPECIFIED_NODE /> :
        <dd>{app.get("ports").join(",")}</dd>;
      var taskRateLimitNode = (app.get("taskRateLimit") == null) ?
        <UNSPECIFIED_NODE /> :
        <dd>{app.get("taskRateLimit")}</dd>;
      var urisNode = (app.get("uris").length === 0) ?
        <UNSPECIFIED_NODE /> :
        app.get("uris").map(function(u) {
          return <dd key={u}>{u}</dd>;
        });

      return (
        <dl className="dl-horizontal">
          <dt>Command</dt>
          {cmdNode}
          <dt>Constraints</dt>
          {constraintsNode}
          <dt>Container</dt>
          {containerNode}
          <dt>CPUs</dt>
          <dd>{app.get("cpus")}</dd>
          <dt>Environment</dt>
          {envNode}
          <dt>Executor</dt>
          {executorNode}
          <dt>ID</dt>
          <dd>{app.id}</dd>
          <dt>Instances</dt>
          <dd>{app.get("instances")}</dd>
          <dt>Memory (MB)</dt>
          <dd>{app.get("mem")}</dd>
          <dt>Ports</dt>
          {portsNode}
          <dt>Task Rate Limit</dt>
          {taskRateLimitNode}
          <dt>URIs</dt>
          {urisNode}
          <dt>Version</dt>
          <dd>
            <time dateTime={app.get("version").toISOString()}
                title={app.get("version").toISOString()}>
              {app.get("version").toLocaleString()}
            </time>
          </dd>
        </dl>
      );
    }
  });
});
