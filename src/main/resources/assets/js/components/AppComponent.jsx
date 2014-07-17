/** @jsx React.DOM */

define([
  "React",
  "jsx!components/BadgeComponent"
], function(React, BadgeComponent) {
  return React.createClass({
    onClick: function() {
      this.props.onClick(this.props.model);
    },

    render: function() {
      var model = this.props.model;

      return (
        // Set `title` on cells that potentially overflow so hovering on the
        // cells will reveal their full contents.
        <tr onClick={this.onClick}>
          <td className="overflow-ellipsis" title={model.get("id")}>
            {model.get("id")}
          </td>
          <td className="overflow-ellipsis" title={model.get("cmd")}>
            {model.get("cmd")}
          </td>
          <td className="text-right">{model.get("mem")}</td>
          <td className="text-right">{model.get("cpus")}</td>
          <td className="text-right">
            <BadgeComponent types={{"warning": !model.allInstancesBooted()}}>
              {model.formatTasksRunning()} / {model.get("instances")}
            </BadgeComponent>
          </td>
        </tr>
      );
    }
  });
});
