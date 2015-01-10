/** @jsx React.DOM */

var React = require("react/addons");

var States = require("../constants/States");

var AppVersion = require("../components/AppVersion");
var AppVersionListItem = require("../components/AppVersionListItem");
var PagedContent = require("../components/PagedContent");
var PagedNav = require("../components/PagedNav");

var BackboneMixin = require("../mixins/BackboneMixin");

var App = require("../models/App");

var AppVersionList = React.createClass({
  displayName: "AppVersionList",

  mixins: [BackboneMixin],

  propTypes: {
    app: React.PropTypes.instanceOf(App).isRequired,
    fetchAppVersions: React.PropTypes.func.isRequired,
    onRollback: React.PropTypes.func
  },

  componentWillMount: function () {
    this.props.fetchAppVersions();
  },

  getBackboneModels: function () {
    return [this.props.app.versions];
  },

  getInitialState: function () {
    return {
      currentPage: 0,
      itemsPerPage: 8
    };
  },

  handleRefresh: function () {
    this.props.fetchAppVersions();
  },

  handlePageChange: function (pageNum) {
    this.setState({currentPage: pageNum});
  },

  render: function () {
    // take out current version, to be displayed seperately
    var appVersions = this.props.app.versions.models.slice(1);

    var itemsPerPage = this.state.itemsPerPage;
    var currentPage = this.state.currentPage;

    var tableContents;

    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    /* jshint trailing:false, quotmark:false, newcap:false */
    if (this.props.fetchState === States.STATE_LOADING) {
      tableContents = (
        <p className="text-muted text-center">
          Loading versions...
        </p>
      );
    } else if (this.props.fetchState === States.STATE_SUCCESS) {

      tableContents =
        <PagedContent
            currentPage={currentPage}
            itemsPerPage={itemsPerPage}>
          {
            appVersions.map(function (v) {
              return (
                  <AppVersionListItem
                    app={this.props.app}
                    appVersion={v}
                    key={v.get("version")}
                    onRollback={this.props.onRollback} />
              );
            }, this)
          }
        </PagedContent>;
    } else {
      tableContents =
        <p className="text-danger text-center">
          Error fetching app versions
        </p>;
    }

    // at least two pages
    var pagedNav = appVersions.length > itemsPerPage ?
      <PagedNav
        className="pull-right"
        currentPage={currentPage}
        onPageChange={this.handlePageChange}
        itemsPerPage={itemsPerPage}
        noItems={appVersions.length}
        useArrows={true} /> :
      null;

    // at least one older version
    var versionTable = appVersions.length > 0 ?
      <div className="panel-group">
        <div
            className="panel panel-header panel-inverse">
          <div className="panel-heading">
            Older versions
            {pagedNav}
          </div>
        </div>
        {tableContents}
      </div> :
      null;

    return (
      <div>
        <h5>
          Current Version
          <button className="btn btn-sm btn-info pull-right" onClick={this.handleRefresh}>
            ↻ Refresh
          </button>
        </h5>
        <AppVersion
            app={this.props.app}
            appVersion={this.props.app.getCurrentVersion()}
            currentVersion={true} />
          {versionTable}
      </div>
    );
  }
});

module.exports = AppVersionList;
