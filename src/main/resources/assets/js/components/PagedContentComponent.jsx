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
      itemsPerPage: React.PropTypes.number,
      noVisiblePages: React.PropTypes.number
    },

    getDefaultProps: function() {
      return {
        onPageChange: noop,
        itemsPerPage: 20,
        noVisiblePages: 6
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
      var end = begin + this.state.itemsPerPage;
      var pageNodes = React.Children.map(children, function(child, i) {
        if (child != null && i >= begin && i < end) {
          return React.addons.cloneWithProps(child, {key: i});
        }
      });

      return (
        <div>
          {pageNodes}
          {
            // is there at least two pages
            children.length > this.props.itemsPerPage ?
              <PagedNavComponent
                currentPage={this.state.currentPage}
                handlePageChange={this.handlePageChange}
                itemsPerPage={this.props.itemsPerPage}
                items={children}
                noVisiblePages={this.props.noVisiblePages} /> :
                null
          }
        </div>
      );
    }
  });
});
