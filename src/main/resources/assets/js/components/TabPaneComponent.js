/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    getDefaultProps: function() {
      return {
        isActive: false
      };
    },
    render: function() {
      var className = this.props.isActive ?
        "tab-pane active" :
        "tab-pane";

      return (
        <div className={className}>
          {this.props.children}
        </div>
      );
    }
  });
});
