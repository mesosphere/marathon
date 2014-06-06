/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "models/Task",
  "jsx!components/TimeFieldComponent",
  "jsx!components/TaskHealthComponent"
], function(React, BackboneMixin, Task,
  TimeFieldComponent, TaskHealthComponent) {

  return React.createClass({
    displayName: "TaskDetailComponent",
    mixins: [BackboneMixin],
    propTypes: {
      task: React.PropTypes.object.isRequired,
      onShowTaskList: React.PropTypes.func.isRequired
    },
    getResource: function() {
      return this.props.task;
    },
    handleShowTaskList: function (event) {
      event.preventDefault();
      this.props.onShowTaskList();
    },
    render: function() {
      var task = this.props.task;
      var hideError = this.props.fetchState !== this.props.STATES.STATE_ERROR && task.collection != null;
      var errorClassSet = React.addons.classSet({
        "text-center text-danger": true,
        "hidden" : hideError
      });

      var taskClassSet = React.addons.classSet({
        "hidden" : !hideError
      });

      var taskHealth = task.getHealth();
      var healthClassSet = React.addons.classSet({
        "text-healthy": taskHealth === Task.HEALTH.HEALTHY,
        "text-unhealthy": taskHealth === Task.HEALTH.UNHEALTHY,
        "text-muted": taskHealth === Task.HEALTH.UNKNOWN
      });

      var timeNodes = [
        {
          label: "Staged at",
          time: task.get("stagedAt")
        }, {
          label: "Started success",
          time: task.get("startedAt")
        }
      ];
      timeFields = timeNodes.map(function(timeNode, index) {
        return (
          <TimeFieldComponent
            key={index}
            label={timeNode.label}
            time={timeNode.time} />
        );
      });
      return (
        <div>
          <ol className="breadcrumb">
            <li>
              <a href="#" onClick={this.handleShowTaskList}>Task List</a>
            </li>
            <li className="active">{task.get("id")}</li>
          </ol>
          <p className={errorClassSet}>
            Error fetching task details. Go to <a href="#" onClick={this.handleShowTaskList}>Task List</a> to see the full list.
          </p>
          <h5 className={taskClassSet + " text-right text-muted"}>Task Details</h5>
          <dl className={taskClassSet + " dl-horizontal"}>
            <dt>Host</dt>
            <dd>{task.get("host")}</dd>
            <dt>Ports</dt>
            <dd>[{task.get("ports").toString()}]</dd>
            <dt>Status</dt>
            <dd>{task.get("status")}</dd>
            {timeFields}
            <dt>Version</dt>
            <dd>
              <time dateTime={task.get("version")}>
                {task.get("version").toLocaleString()}
              </time>
            </dd>
            <dt>Health</dt>
            <dd className={healthClassSet}>{this.props.formatTaskHealthMessage(task)}</dd>
          </dl>
          <TaskHealthComponent className={taskClassSet} task={task} />
        </div>
      );
    }

  });
});
