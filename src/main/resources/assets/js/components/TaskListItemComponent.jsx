/** @jsx React.DOM */

define([
  "React",
  "models/Task"
], function(React, Task) {
  "use strict";

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
      task: React.PropTypes.object.isRequired,
      currentAppVersion: React.PropTypes.object.isRequired
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
      var currentAppVersion = this.props.currentAppVersion;
      var outdated = currentAppVersion && task.get("version") < currentAppVersion;

      var taskHealth = task.getHealth();
      var healthClassSet = React.addons.classSet({
        "health-dot": true,
        "health-dot-error": taskHealth === Task.HEALTH.UNHEALTHY,
        "health-dot-success": taskHealth === Task.HEALTH.HEALTHY,
        "health-dot-unknown": taskHealth === Task.HEALTH.UNKNOWN
      });

      var statusClassSet = React.addons.classSet({
        "text-warning": task.isStaged()
      });

      var versionClassSet = React.addons.classSet({
        "text-muted": !outdated,
        "text-warning": outdated
      });

      var updatedAtNode;
      if (task.get("updatedAt") != null) {
        updatedAtNode =
          <time dateTime={task.get("updatedAt").toISOString()}
              title={task.get("updatedAt").toISOString()}>
            {task.get("updatedAt").toLocaleString()}
          </time>;
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
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
          <td className="text-center">
            <span className={statusClassSet}>
              {task.get("status")}
            </span>
          </td>
          <td className="text-right">
            <span
              title={task.get("version").toISOString()}
              className={versionClassSet}>{outdated ? "Out-of-date" : "Current"}</span>
          </td>
          <td className="text-right">{updatedAtNode}</td>
          {
            hasHealth ?
              <td title={this.props.taskHealthMessage}
                className="text-center">
                  <span className={healthClassSet} />
              </td> :
              null
          }
        </tr>
      );
    }

  });
});
