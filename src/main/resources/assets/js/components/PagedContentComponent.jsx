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

    render: function () {

      var children = this.props.children;
      var begin = this.props.currentPage * this.props.itemsPerPage;
      var end = begin + this.props.itemsPerPage;
      var pageNodes = React.Children.map(children, function (child, i) {
        if (child != null && i >= begin && i < end) {
          return React.addons.cloneWithProps(child, {key: i});
        }
      });

      var Wrap = React.createElement(
        this.props.tag,
        {className: this.props.className},
        pageNodes
      );

      return (
        Wrap
      );
    }
  });
