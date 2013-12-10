/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    onClick: function() {
      this.props.onClick(this.props.model);
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
