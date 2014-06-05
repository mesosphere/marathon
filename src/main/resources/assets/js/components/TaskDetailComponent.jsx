/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "models/Task"
], function(React, BackboneMixin, Task) {
  var STATES = {
      STATE_LOADING: 0,
      STATE_ERROR: 1,
      STATE_SUCCESS: 2
    };

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
      var view;
      var healthNodeList;
      var task = this.props.task;
      breadcrumb =
        <ol key="breadcrumb" className="breadcrumb">
          <li>
            <a href="#" onClick={this.handleShowTaskList}>Task List</a>
          </li>
          <li className="active">{task.get("id")}</li>
        </ol>;
      if (this.props.fetchState === STATES.STATE_ERROR || task.collection == null) {
        // build error view
        view =
            <p className="text-center text-danger">
              Error fetching task details. Go to <a href="#" onClick={this.handleShowTaskList}>Task List</a> to see the full list.
            </p>;
      } else {
        // build health nodes
        var taskHealth = task.getHealth();
        var healthClassSet = React.addons.classSet({
          "text-healthy": taskHealth === Task.HEALTH.HEALTHY,
          "text-unhealthy": taskHealth === Task.HEALTH.UNHEALTHY,
          "text-muted": taskHealth === Task.HEALTH.UNKNOWN
        });
        var healthCheckResults = task.get("healthCheckResults");
        if (healthCheckResults != null) {
          healthNodeList = healthCheckResults.map(function (cResult, index) {
            if (cResult != null) {
              var fSuccessNode = (cResult.firstSuccess == null ?
                <dd className="text-muted">None</dd> :
                <dd>
                  <time dateTime={cResult.firstSuccess}>
                    {new Date(cResult.firstSuccess).toLocaleString()}
                  </time>
                </dd>);
              var lSuccessNode = (cResult.lastSuccess == null ?
                <dd className="text-muted">None</dd> :
                <dd>
                  <time dateTime={cResult.lastSuccess}>
                    {new Date(cResult.lastSuccess).toLocaleString()}
                  </time>
                </dd>);
              var lFailureNode = (cResult.lastFailure == null ?
                <dd className="text-muted">None</dd> :
                <dd>
                  <time dateTime={cResult.lastFailure}>
                    {new Date(cResult.lastFailure).toLocaleString()}
                  </time>
                </dd>);
              var cFailuresNode = (cResult.consecutiveFailures == null ?
                <dd className="text-muted">None</dd> :
                <dd>{cResult.consecutiveFailures}</dd>);
              var aliveNode = (cResult.alive == null ?
                <dd>No</dd> :
                <dd>Yes</dd>);
              return (
                <div key={index}>
                  <h5 className="text-right text-muted">Health Check Result {index+1}</h5>
                  <dl className="dl-horizontal">
                    <dt>First Success</dt>
                    {fSuccessNode}
                    <dt>Last Success</dt>
                    {lSuccessNode}
                    <dt>Last Failure</dt>
                    {lFailureNode}
                    <dt>Consecutive Failures</dt>
                    {cFailuresNode}
                    <dt>Alive</dt>
                    {aliveNode}
                  </dl>
                  <hr />
                </div>
              );
            }
          });
          if (healthNodeList.length > 0) {
            healthNodeList.unshift(<hr key="hr" />);
          }
        }
        // build view
        var stagedAtNode =
          (task.get("stagedAt") == null ?
            <dd className="text-muted">None</dd> :
            <dd>
              <time dateTime={task.get("stagedAt")}>
                {task.get("stagedAt").toLocaleString()}
              </time>
            </dd>);
        var startedAtNode =
          (task.get("startedAt") == null ?
            <dd className="text-muted">None</dd> :
            <dd>
              <time dateTime={task.get("startedAt")}>
                {task.get("startedAt").toLocaleString()}
              </time>
            </dd>);
          view = [<h5 key="header" className="text-right text-muted">Task Details</h5>];
          view.push(
            <dl key="details" className="dl-horizontal">
              <dt>Host</dt>
              <dd>{task.get("host")}</dd>
              <dt>Ports</dt>
              <dd>[{task.get("ports").toString()}]</dd>
              <dt>Status</dt>
              <dd>{task.get("status")}</dd>
              <dt>Staged at</dt>
              {stagedAtNode}
              <dt>Started at</dt>
              {startedAtNode}
              <dt>Version</dt>
              <dd>
                <time dateTime={task.get("version")}>
                  {task.get("version").toLocaleString()}
                </time>
              </dd>
              <dt>Health</dt>
              <dd className={healthClassSet}>{this.props.formatTaskHealthMessage(task)}</dd>
            </dl>
          );
        }
      return (
        <div>
          {breadcrumb}
          {view}
          {healthNodeList}
        </div>
      );
    }

  });
});
