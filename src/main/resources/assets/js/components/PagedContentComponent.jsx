/** @jsx React.DOM */

define([
  "React",
  "jsx!components/PagedNavComponent"
], function(React, PagedNavComponent) {
  "use strict";
  function noop() {}

  return React.createClass({
    displayName: "PagedContentComponent",

    propTypes: {
      onPageChange: React.PropTypes.func,
      itemsPerPage: React.PropTypes.number
    },

    getDefaultProps: function() {
      return {
        onPageChange: noop,
        itemsPerPage: 20
      };
    },

    getInitialState: function() {
      return {
        currentPage: 0,
        itemsPerPage: this.props.itemsPerPage
      };
    },

    handlePageChange: function(pageNum) {
      this.setState({currentPage: pageNum});
      this.props.onPageChange(pageNum);
    },

    render: function() {
      var children = this.props.children;
      var begin = this.state.currentPage * this.state.itemsPerPage;
      var end = (this.state.currentPage + 1) * this.state.itemsPerPage;
      var pageNodes = React.Children.map(children, function(child, i) {
          if (i >= begin && i < end) {
            return child;
          }
      });

      return (
        <div>
          {pageNodes}
          <PagedNavComponent
            currentPage={this.state.currentPage}
            handlePageChange={this.handlePageChange}
            itemsPerPage={this.props.itemsPerPage}
            items={children}
            noVisiblePages={6} />
        </div>
      );
    }
  });
});
