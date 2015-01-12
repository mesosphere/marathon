/** @jsx React.DOM */


var React = require("react");

module.exports = React.createClass({
    displayName: "StackedViewComponent",
    render: function() {
      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          {this.props.children[this.props.activeViewIndex]}
        </div>
      );
    }
  });
