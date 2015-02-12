/** @jsx React.DOM */

var React = require("react/addons");
var Moment = require("moment");
var Task = require("../models/Task");

function buildHref(host, port) {
  return "http://" + host + ":" + port;
}

function buildTaskAnchors(task) {
  var taskAnchors;
  var ports = task.get("ports");
  var portsLength = ports.length;

  /* jshint trailing:false, quotmark:false, newcap:false */
  /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  if (portsLength > 1) {
    // Linkify each port with the hostname. The port is the text of the
    // anchor, but the href contains the hostname and port, a full link.
    taskAnchors =
      <span className="text-muted">
        {task.get("host")}:[{ports.map(function (p, index) {
          return (
            <span key={p}>
              <a className="text-muted"
                href={buildHref(task.get("host"), p)}>{p}</a>
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

var TaskListItemComponent = React.createClass({
  displayName: "TaskListItemComponent",

  propTypes: {
    appId: React.PropTypes.string.isRequired,
    hasHealth: React.PropTypes.bool,
    isActive: React.PropTypes.bool.isRequired,
    onToggle: React.PropTypes.func.isRequired,
    task: React.PropTypes.object.isRequired
  },

  handleClick: function (event) {
    // If the click happens on the checkbox, let the checkbox's onchange event
    // handler handle it and skip handling the event here.
    if (event.target.nodeName !== "INPUT") {
      this.props.onToggle(this.props.task);
    }
  },

  handleCheckboxClick: function (event) {
    this.props.onToggle(this.props.task, event.target.checked);
  },

  render: function () {
    var task = this.props.task;
    var hasHealth = !!this.props.hasHealth;
    var version = task.get("version").toISOString();
    var taskId = task.get("id");
    var taskUri = "#apps/" +
      encodeURIComponent(this.props.appId) +
      "/" + encodeURIComponent(taskId);

    var taskHealth = task.getHealth();

    var listItemClassSet = React.addons.classSet({
      "active": this.props.isActive
    });

    var healthClassSet = React.addons.classSet({
      "health-dot": true,
      "health-dot-error": taskHealth === Task.HEALTH.UNHEALTHY,
      "health-dot-success": taskHealth === Task.HEALTH.HEALTHY,
      "health-dot-unknown": taskHealth === Task.HEALTH.UNKNOWN
    });

    var statusClassSet = React.addons.classSet({
      "text-warning": task.isStaged()
    });

    var hasHealthClassSet = React.addons.classSet({
      "text-center": true,
      "hidden": !hasHealth
    });

    var updatedAtNodeClassSet = React.addons.classSet({
      "hidden": task.get("updatedAt") == null
    });

    var updatedAtISO;
    var updatedAtLocal;
    if (task.get("updatedAt") != null) {
      updatedAtISO = task.get("updatedAt").toISOString();
      updatedAtLocal = task.get("updatedAt").toLocaleString();
    }

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <tr className={listItemClassSet}>
        <td width="1" className="clickable" onClick={this.handleClick}>
          <input type="checkbox"
            checked={this.props.isActive}
            onChange={this.handleCheckboxClick} />
        </td>
        <td>
            <a href={taskUri}>{taskId}</a>
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
            title={version}>
            {new Moment(version).fromNow()}
          </span>
        </td>
        <td className="text-right">
          <time className={updatedAtNodeClassSet}
              dateTime={updatedAtISO}
              title={updatedAtISO}>
            {updatedAtLocal}
          </time>
        </td>
        <td className={hasHealthClassSet} title={this.props.taskHealthMessage}>
            <span className={healthClassSet} />
        </td>
      </tr>
    );
  }
});

module.exports = TaskListItemComponent;
