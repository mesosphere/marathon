/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppModalComponent"
], function(React, AppModalComponent) {
  return React.createClass({
    onClick: function() {
      React.renderComponent(
        <AppModalComponent model={this.props.model} />,
        document.getElementById("lightbox")
      );
    },
    render: function() {
      var model = this.props.model;

      return (
        <tr onClick={this.onClick}>
          <td>{model.get("id")}</td>
          <td>{model.get("cmd")}</td>
          <td>{model.get("mem")}</td>
          <td>{model.get("cpus")}</td>
          <td>{model.get("instances")}</td>
        </tr>
      );
    }
  });
});
