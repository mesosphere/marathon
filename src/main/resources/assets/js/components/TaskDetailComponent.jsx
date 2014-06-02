/** @jsx React.DOM */

define([
  "React",
], function(React) {

  return React.createClass({
    displayName: "TaskDetailComponent",

    propTypes: {
      task: React.PropTypes.object.isRequired,
      onShowTaskList: React.PropTypes.func.isRequired
    },

    render: function() {
      var task = this.props.task;
      var healthClassSet = React.addons.classSet({
        "healthy": task.get("health"),
        "unhealthy": !task.get("health")
      });

      var statusClassSet = React.addons.classSet({
        "badge text-left": true,
        "badge-default": task.isStarted(),
        "badge-warning": task.isStaged()
      });

      var updatedAtHead;
      var updatedAtNode;
      if (task.get("updatedAt") != null) {
        updatedAtHead = <dt>Updated</dt>;
        updatedAtNode =
          <dd><time timestamp={task.get("updatedAt")}>
            {task.get("updatedAt").toLocaleString()}
          </time></dd>;
      }

      return (
        <div>
          <p>
            <button className="btn btn-sm btn-default"
                onClick={this.props.onShowTaskList}>
              ◁ Task List
            </button>
          </p>
          <dl className="dl-horizontal">
            <dt>ID</dt>
            <dd>{task.get("id")}</dd>
            <dt>Host</dt>
            <dd>{task.get("host")}</dd>
            <dt>Ports</dt>
            <dd>[{task.get("ports").toString()}]</dd>
            <dt>Health</dt>
            <dd className={healthClassSet}>{task.get("healthMsg")}</dd>
            <dt>Status</dt>
            <dd>{task.get("status")}</dd>
            {updatedAtHead}
            {updatedAtNode}
            <dt>Version</dt>
            <dd><time timestamp={task.get("version")}>
            {task.get("version").toLocaleString()}
          </time></dd>
          </dl>
        </div>
      );
    }

  });
});
