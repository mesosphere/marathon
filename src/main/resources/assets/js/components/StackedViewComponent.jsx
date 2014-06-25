/** @jsx React.DOM */

define([
  "React"
], function(React) {

  return React.createClass({
    displayName: "StackedViewComponent",
    render: function() {
      return (
        <div>
          {this.props.children[this.props.activeViewIndex]}
        </div>
      );
    }
  });
});
