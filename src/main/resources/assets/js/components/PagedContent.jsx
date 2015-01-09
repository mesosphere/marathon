/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
    displayName: "PagedContentComponent",

    propTypes: {
      className: React.PropTypes.string,
      currentPage: React.PropTypes.number.isRequired,
      itemsPerPage: React.PropTypes.number,
      element: React.PropTypes.string,
    },

    getDefaultProps: function() {
      return {
        itemsPerPage: 20,
        element: "div"
      };
    },

    render: function() {
      var Wrap = React.DOM[this.props.element];

      var children = this.props.children;
      var begin = this.props.currentPage * this.props.itemsPerPage;
      var end = begin + this.props.itemsPerPage;
      var pageNodes = React.Children.map(children, function(child, i) {
        if (child != null && i >= begin && i < end) {
          return React.addons.cloneWithProps(child, {key: i});
        }
      });

      return (
        <Wrap className={this.props.className}>
          {pageNodes}
        </Wrap>
      );
    }
  });
});
