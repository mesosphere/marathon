/** @jsx React.DOM */

"use strict";

var React = require("react/addons");

var PagedContent = React.createClass({
  displayName: "PagedContent",

  propTypes: {
    className: React.PropTypes.string,
    currentPage: React.PropTypes.number.isRequired,
    itemsPerPage: React.PropTypes.number,
    element: React.PropTypes.string,
  },

  getDefaultProps: function () {
    return {
      itemsPerPage: 20,
      element: "div"
    };
  },

  render: function () {
    var Wrap = React.DOM[this.props.element];

    var children = this.props.children;
    var begin = this.props.currentPage * this.props.itemsPerPage;
    var end = begin + this.props.itemsPerPage;
    var pageNodes = React.Children.map(children, function (child, i) {
      if (child != null && i >= begin && i < end) {
        return React.addons.cloneWithProps(child, {key: i});
      }
    });

    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <Wrap className={this.props.className}>
        {pageNodes}
      </Wrap>
    );
  }
});

module.exports = PagedContent;
