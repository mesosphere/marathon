/** @jsx React.DOM */

var React = require("react/addons");

module.exports = React.createClass({
    displayName: "PagedContentComponent",

    propTypes: {
      className: React.PropTypes.string,
      currentPage: React.PropTypes.number.isRequired,
      itemsPerPage: React.PropTypes.number,
      tag: React.PropTypes.string,
    },

    getDefaultProps: function () {
      return {
        itemsPerPage: 20,
        tag: "div"
      };
    },

    isHidden: function (child) {
      return child != null &&
        child.props != null &&
        child.props.className != null &&
        child.props.className.split(" ").indexOf("hidden") > -1;
    },

    getVisibleChildren: function (children) {
      return children.filter(function (child) {
        return !this.isHidden(child);
      }.bind(this));
    },

    getPageNodes: function () {
      var children = this.props.children;
      var begin = this.props.currentPage * this.props.itemsPerPage;
      var end = begin + this.props.itemsPerPage;
      var visibleChildren = this.getVisibleChildren(children);

      return React.Children.map(visibleChildren, function (child, i) {
        if (i >= begin && i < end) {
          return React.addons.cloneWithProps(child, {key: i});
        }
      });
    },

    render: function () {
      return (
        React.createElement(
          this.props.tag,
          {className: this.props.className},
          this.getPageNodes()
        )
      );
    }
  });
