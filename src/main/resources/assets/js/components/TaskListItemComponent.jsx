/** @jsx React.DOM */

define([
  "React",
  "models/Task",
  "jsx!components/TaskDetailComponent"
], function(React, Task, TaskDetailComponent) {

  function buildHref(host, port) {
    return "http://" + host + ":" + port;
  }

  function buildTaskAnchors(task) {
    var taskAnchors;
    var ports = task.get("ports");
    var portsLength = ports.length;

    if (portsLength > 1) {
      // Linkify each port with the hostname. The port is the text of the
      // anchor, but the href contains the hostname and port, a full link.
      taskAnchors =
        <span className="text-muted">
          {task.get("host")}:[{ports.map(function(p, index) {
            return (
              <span key={p}>
                <a className="text-muted" href={buildHref(task.get("host"), p)}>{p}</a>
                {index < portsLength - 1 ? ", " : ""}
              </span>
            );
          })}]
        </span>;
    } else if (portsLength === 1) {
      // Linkify the hostname + port since there is only one port.
      taskAnchors =
        <a className="text-muted" href={buildHref(task.get("host"), ports[0])}>
          {task.get("host")}:{ports[0]}
        </a>;
    } else {
      // Ain't no ports; don't linkify.
      taskAnchors = <span className="text-muted">{task.get("host")}</span>;
    }

    return taskAnchors;
  }

  return React.createClass({
    displayName: "TaskListItemComponent",

    propTypes: {
      hasHealth: React.PropTypes.bool,
      isActive: React.PropTypes.bool.isRequired,
      onToggle: React.PropTypes.func.isRequired,
      onTaskDetailSelect: React.PropTypes.func.isRequired,
      task: React.PropTypes.object.isRequired
    },

    handleClick: function(event) {
      // If the click happens on the checkbox, let the checkbox's onchange event
      // handler handle it and skip handling the event here.
      if (event.target.nodeName !== "INPUT") {
        this.props.onToggle(this.props.task);
      }
    },

    handleCheckboxClick: function(event) {
      this.props.onToggle(this.props.task, event.target.checked);
    },

    handleTaskDetailSelect: function(event) {
      event.preventDefault();
      this.props.onTaskDetailSelect(this.props.task);
    },

    render: function() {
      var className = (this.props.isActive) ? "active" : "";
      var task = this.props.task;
      var hasHealth = !!this.props.hasHealth;

      var statusClassSet = React.addons.classSet({
        // "": true,
        // "badge-default": task.isStarted(),
        "badge badge-circlet": task.isStaged()
      });

      var taskHealth = task.getHealth();
      var healthClassSet = React.addons.classSet({
        "text-center": true,
        "text-healthy": taskHealth === Task.HEALTH.HEALTHY,
        "text-unhealthy": taskHealth === Task.HEALTH.UNHEALTHY,
        "text-muted": taskHealth === Task.HEALTH.UNKNOWN
      });

      var updatedAtNode;
      if (task.get("updatedAt") != null) {
        updatedAtNode =
          <time dateTime={task.get("updatedAt").toISOString()}
              title={task.get("updatedAt").toISOString()}>
            {task.get("updatedAt").toLocaleString()}
          </time>;
      }

      return (
        <tr className={className}>
          <td width="1" className="clickable" onClick={this.handleClick}>
            <input type="checkbox"
              checked={this.props.isActive}
              onChange={this.handleCheckboxClick} />
          </td>
          <td>
              <a href="#"
                onClick={this.handleTaskDetailSelect}>{task.get("id")}</a>
            <br />
            {buildTaskAnchors(task)}
          </td>
          <td>
            <span className={statusClassSet}>
              {task.get("status")}
            </span>
          </td>
          <td className="text-right">{updatedAtNode}</td>
          {
            hasHealth ?
              <td title={this.props.taskHealthMessage}
                className={healthClassSet}>●</td> :
              null
          }
        </tr>
      );
    }

  });
});
