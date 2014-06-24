/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";
  function noop() { return false; }

  return React.createClass({
    displayName: "PagedNavComponent",

    propTypes: {
      currentPage: React.PropTypes.number.isRequired,
      onPageChange: React.PropTypes.func.isRequired,
      itemsPerPage: React.PropTypes.number,
      noItems: React.PropTypes.number.isRequired,
      noVisiblePages: React.PropTypes.number
    },

    getDefaultProps: function() {
      return {
        noVisiblePages: 6,
        itemsPerPage: 20
      };
    },

    handlePageChange: function(pageNum) {
      this.props.onPageChange(pageNum);
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
              pageNumber === currentPage ?
              <li className="disabled" key={pageNumber}>
                <span>{pageNumber}</span>
              </li> :
              <li key={pageNumber}>
                <a href="#"
                  onClick={this.handlePageChange.bind(this, pageNumber)}>
                  {pageNumber}
                </a>
              </li>
            );
          }
          pageNumber++;
        }
      }

      var disableLeft = currentPage === 0;
      var disableRight = currentPage === noPages - 1;
      var leftArrowsClassSet = React.addons.classSet({
        "disabled": disableLeft
      });
      var rightArrowsClassSet = React.addons.classSet({
        "disabled": disableRight
      });
      var leftArrowsFunc = disableLeft ? noop : this.handlePageChange;
      var rightArrowsFunc = disableRight ? noop : this.handlePageChange;

      return (
        <ul className="pagination pagination-sm pagination-unstyled">
          <li className={leftArrowsClassSet}>
            <a href="#" onClick={leftArrowsFunc.bind(this, 0)}>
              «
            </a>
          </li>
          <li className={leftArrowsClassSet}>
            <a href="#" onClick={leftArrowsFunc.bind(this, currentPage - 1)}>
              ‹
            </a>
          </li>
          {pagination}
          <li className={rightArrowsClassSet}>
            <a href="#"
              onClick={rightArrowsFunc.bind(this, currentPage + 1)}>
              ›
            </a>
          </li>
          <li className={rightArrowsClassSet}>
            <a href="#"
              onClick={rightArrowsFunc.bind(this, noPages - 1)}>
              »
            </a>
          </li>
        </ul>
      );
    }
  });
});
