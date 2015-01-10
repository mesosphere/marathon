/** @jsx React.DOM */

var React = require("react/addons");

module.exports = React.createClass({
  displayName: "StackedView",
  render: function () {
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <div>
        {this.props.children[this.props.activeViewIndex]}
      </div>
    );
  }
});
