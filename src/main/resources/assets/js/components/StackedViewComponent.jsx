/** @jsx React.DOM */

var React = require("react/addons");

var StackedViewComponent = React.createClass({
  displayName: "StackedViewComponent",
  render: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        {this.props.children[this.props.activeViewIndex]}
      </div>
    );
  }
});

module.exports = StackedViewComponent;
