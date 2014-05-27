/** @jsx React.DOM */

define([
  "React",
], function(React) {

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

      var statusClassSet = React.addons.classSet({
        "badge": true,
        "badge-default": task.isStarted(),
        "badge-warning": task.isStaged()
      });

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
              {task.formatHostPorts()}
            </span>
          </td>
          <td>
            <span className={statusClassSet}>
              {task.get("status")}
            </span>
          </td>
          <td className="text-right">{updatedAtNode}</td>
        </tr>
      );
    }

  });
});
