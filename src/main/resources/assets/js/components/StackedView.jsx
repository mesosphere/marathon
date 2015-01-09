/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
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
});
