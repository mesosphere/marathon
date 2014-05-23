/** @jsx React.DOM */

define([
  "React",
], function(React) {
  function taskHostPortsToString(task) {
    var portsString;
    var ports = task.get("ports");
    if (ports.length > 1) {
      portsString = ":[" + ports.join(",") + "]";
    } else if (ports.length === 1) {
      portsString = ":" + ports[0];
    } else {
      portsString = "";
    }

    return task.get("host") + portsString;
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

    render: function() {
      var className = (this.props.isActive) ? "active" : "";
      var task = this.props.task;

      var statusNode;
      if (task.isStarted()) {
        statusNode =
          <span className="badge badge-default">
            {task.get("status")}
          </span>;
      } else if (task.isStaged()) {
        statusNode =
          <span className="badge badge-warning">
            {task.get("status")}
          </span>;
      }

      var updatedAtNode;
      if (task.get("updatedAt") != null) {
        updatedAtNode =
          <time timestamp={task.get("updatedAt")}>
            {task.get("updatedAt").toISOString()}
          </time>;
      }

      return (
        <tr className={className} onClick={this.handleClick}>
          <td width="1">
            <input type="checkbox"
              checked={this.props.isActive}
              onChange={this.handleCheckboxClick} />
          </td>
          <td>
            {task.get("id")}<br />
            <span className="text-muted">
              {taskHostPortsToString(task)}
            </span>
          </td>
          <td>{statusNode}</td>
          <td className="text-right">{updatedAtNode}</td>
        </tr>
      );
    }

  });
});
