/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
    displayName: "PagedNavComponent",

    propTypes: {
      currentPage: React.PropTypes.number.isRequired,
      onPageChange: React.PropTypes.func.isRequired,
      itemsPerPage: React.PropTypes.number,
      noItems: React.PropTypes.number.isRequired,
      noVisiblePages: React.PropTypes.number,
      useArrows: React.PropTypes.bool,
      useEndArrows: React.PropTypes.bool
    },

    getDefaultProps: function() {
      return {
        noVisiblePages: 6,
        itemsPerPage: 20
      };
    },

    handlePageChange: function(pageNum) {
      var noPages = Math.ceil(this.props.noItems / this.props.itemsPerPage);
      if (pageNum >= 0 &&
          pageNum < noPages &&
          pageNum !== this.props.currentPage) {
        this.props.onPageChange(pageNum);
      } else {
        return false;
      }
    },

    render: function() {
      var noItems = this.props.noItems;
      var currentPage = this.props.currentPage;
      var pageNumber = 0;
      var pagesOnEachSide = Math.floor(this.props.noVisiblePages/2);
      var noPages = Math.ceil(noItems / this.props.itemsPerPage);
      var lowerBound = Math.max(0, currentPage - pagesOnEachSide);
      var upperBound = Math.min(noPages, currentPage + pagesOnEachSide);
      if (currentPage < pagesOnEachSide) {
        upperBound += pagesOnEachSide - currentPage;
      }
      if (noPages < currentPage + pagesOnEachSide) {
        lowerBound -= currentPage + pagesOnEachSide - noPages;
      }
      var pagination = [];
      for (var i = 0; i < noItems; i++) {
        // create a page per each number of items on a single page
        if (i % this.props.itemsPerPage === 0) {
          // only draw those within the bounds
          if (pageNumber >= lowerBound && pageNumber <= upperBound) {
            pagination.push(
              <li className={pageNumber === currentPage ? "success disabled" : ""}
                  key={pageNumber}>
                <a href="#"
                  onClick={this.handlePageChange.bind(this, pageNumber)}>
                  {pageNumber + 1}
                </a>
              </li>
            );
          }
          pageNumber++;
        }
      }

      var leftArrowsClassSet = React.addons.classSet({
        "disabled": currentPage === 0
      });
      var rightArrowsClassSet = React.addons.classSet({
        "disabled": currentPage === noPages - 1
      });

      var leftArrow =
        this.props.useArrows ?
          <li className={leftArrowsClassSet}>
            <a href="#" onClick={this.handlePageChange.bind(this, currentPage - 1)}>
              ‹
            </a>
          </li> :
          null;
      var leftEndArrow =
        this.props.useEndArrows ?
          <li className={leftArrowsClassSet}>
            <a href="#" onClick={this.handlePageChange.bind(this, 0)}>
              «
            </a>
          </li> :
          null;
      var rightArrow =
        this.props.useArrows ?
          <li className={rightArrowsClassSet}>
            <a href="#" onClick={this.handlePageChange.bind(this, currentPage + 1)}>
              ›
            </a>
          </li> :
          null;
      var rightEndArrow =
        this.props.useEndArrows ?
          <li className={rightArrowsClassSet}>
            <a href="#"
              onClick={this.handlePageChange.bind(this, noPages - 1)}>
              »
            </a>
          </li> :
          null;

      return (
        <ul className="pagination pagination-sm pagination-unstyled">
          {leftEndArrow}
          {leftArrow}
          {pagination}
          {rightArrow}
          {rightEndArrow}
        </ul>
      );
    }
  });
});
