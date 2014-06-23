/** @jsx React.DOM */

define([
  "Underscore",
  "React",
  "models/App",
  "models/AppVersion"
], function(_, React, App, AppVersion) {
  var UNSPECIFIED_NODE =
    React.createClass({
      render: function() {
        return <dd className="text-muted">Unspecified</dd>;
      }
    });

  return React.createClass({
    displayName: "AppVersionComponent",

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
      onRollback: React.PropTypes.func
    },

    handleSubmit: function(event) {
      if (_.isFunction(this.props.onRollback)) {
        event.preventDefault();
        this.props.onRollback(this.props.appVersion, event);
      }
    },

    render: function() {
      var appVersion = this.props.appVersion;
      if (appVersion) {

        var cmdNode = (appVersion.get("cmd") == null) ?
          <UNSPECIFIED_NODE /> :
          <dd>{appVersion.get("cmd")}</dd>;
        var constraintsNode = (appVersion.get("constraints").length < 1) ?
          <UNSPECIFIED_NODE /> :
          appVersion.get("constraints").map(function(c) {

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
        var containerNode = (appVersion.get("container") == null) ?
          <UNSPECIFIED_NODE /> :
          <dd>{JSON.stringify(appVersion.get("container"))}</dd>;
        var envNode = (Object.keys(appVersion.get("env")).length === 0) ?
          <UNSPECIFIED_NODE /> :

          // Print environment variables as key value pairs like "key=value"
          Object.keys(appVersion.get("env")).map(function(k) {
            return <dd key={k}>{k + "=" + appVersion.get("env")[k]}</dd>
          });
        var executorNode = (appVersion.get("executor") === "") ?
          <UNSPECIFIED_NODE /> :
          <dd>{appVersion.get("executor")}</dd>;
        var portsNode = (appVersion.get("ports").length === 0 ) ?
          <UNSPECIFIED_NODE /> :
          <dd>{appVersion.get("ports").join(",")}</dd>;
        var taskRateLimitNode = (appVersion.get("taskRateLimit") == null) ?
          <UNSPECIFIED_NODE /> :
          <dd>{appVersion.get("taskRateLimit")}</dd>;
        var urisNode = (appVersion.get("uris").length === 0) ?
          <UNSPECIFIED_NODE /> :
          appVersion.get("uris").map(function(u) {
            return <dd key={u}>{u}</dd>;
          });
        }

      return (
        <div>
          <dl className="dl-horizontal">
            <dt>Command</dt>
            {cmdNode}
            <dt>Constraints</dt>
            {constraintsNode}
            <dt>Container</dt>
            {containerNode}
            <dt>CPUs</dt>
            <dd>{appVersion.get("cpus")}</dd>
            <dt>Environment</dt>
            {envNode}
            <dt>Executor</dt>
            {executorNode}
            <dt>ID</dt>
            <dd>{appVersion.id}</dd>
            <dt>Instances</dt>
            <dd>{appVersion.get("instances")}</dd>
            <dt>Memory (MB)</dt>
            <dd>{appVersion.get("mem")}</dd>
            <dt>Ports</dt>
            {portsNode}
            <dt>Task Rate Limit</dt>
            {taskRateLimitNode}
            <dt>URIs</dt>
            {urisNode}
            <dt>Version</dt>
            {
              appVersion.get("version") ?
                <UNSPECIFIED_NODE /> :
                <dd>
                  <time dateTime={appVersion.get("version").toISOString()}
                      title={appVersion.get("version").toISOString()}>
                    {appVersion.get("version").toLocaleString()}
                  </time>
                </dd>
            }
          </dl>
          {
            this.props.currentVersion ?
              <span className="pull-right text-muted">Current version</span> :
              <span className="text-right">
                <form action={this.props.app.url()} method="post" onSubmit={this.handleSubmit}>
                    <input type="hidden" name="_method" value="put" />
                    <input type="hidden" name="version" value={appVersion.get("version")} />
                    <button type="submit" className="btn btn-sm btn-success">
                      Set as current
                    </button>
                </form>
              </span>
          }
        </div>
      );
    }
  });
});
