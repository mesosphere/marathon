/** @jsx React.DOM */

define([
  "React",
], function(React) {

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

    propTypes: {
      isActive: React.PropTypes.bool.isRequired,
      onToggle: React.PropTypes.func.isRequired,
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

    showHealth: function(event) {
      alert(this.props.task.get("healthMsg"));
    },

    render: function() {
      var className = (this.props.isActive) ? "active" : "";
      var task = this.props.task;
      var healthClassName = task.get("health") ? "text-center healthy" : "text-center unhealthy clickable";
      var handleHealthClick = task.get("health") ? function (){} : this.showHealth;

      var statusClassSet = React.addons.classSet({
        "badge": true,
        "badge-default": task.isStarted(),
        "badge-warning": task.isStaged()
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
            {task.get("id")}<br />
            {buildTaskAnchors(task)}
          </td>
          <td>
            <span className={statusClassSet}>
              {task.get("status")}
            </span>
          </td>
          <td className="text-right">{updatedAtNode}</td>
          <td className={healthClassName} onClick={handleHealthClick}>●</td>
        </tr>
      );
    }

  });
});
