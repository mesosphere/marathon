/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
    displayName: "PagedContentComponent",

    propTypes: {
      currentPage: React.PropTypes.number.isRequired,
      itemsPerPage: React.PropTypes.number
    },

    getDefaultProps: function() {
      return {
        itemsPerPage: 20
      };
    },

    render: function() {
      var children = this.props.children;
      var begin = this.props.currentPage * this.props.itemsPerPage;
      var end = begin + this.props.itemsPerPage;
      var pageNodes = React.Children.map(children, function(child, i) {
        if (child != null && i >= begin && i < end) {
          return React.addons.cloneWithProps(child, {key: i});
        }
      });

      return (
        <div>
          {pageNodes}
        </div>
      );
    }
  });
});
