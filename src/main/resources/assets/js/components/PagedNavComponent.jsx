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
      handlePageChange: React.PropTypes.func.isRequired,
      itemsPerPage: React.PropTypes.number.isRequired,
      items: React.PropTypes.array.isRequired,
      noVisiblePages: React.PropTypes.number
    },

    getDefaultProps: function() {
      return {
        noVisiblePages: 6
      };
    },

    handlePageChange: function(pageNum) {
      this.props.handlePageChange(pageNum);
    },

    render: function() {
      var noItems = this.props.items.length;
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
      var pagination = React.Children.map(this.props.items, function(c, k) {
        var page;
        // create a page per each number of items on a single page
        if (k % this.props.itemsPerPage === 0) {
          // only draw those within the bounds
          if (pageNumber >= lowerBound && pageNumber <= upperBound) {
            page = (
              pageNumber === currentPage ?
              <li className="disabled">
                <span key={"pageNumber" + pageNumber}>{pageNumber}</span>
              </li> :
              <li>
                <a href="#" key={"pageNumber" + pageNumber}
                  onClick={this.handlePageChange.bind(this, pageNumber)}>
                  {pageNumber}
                </a>
              </li>
            );
          }
          pageNumber++;
        }
        if (page != null) {
          return page;
        }
      }, this);

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
        <div className="text-center">
          <ul className="pagination row">
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
        </div>
      );
    }
  });
});
