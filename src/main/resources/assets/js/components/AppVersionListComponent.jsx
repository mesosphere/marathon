/** @jsx React.DOM */


var React = require("react/addons");
var States = require("../constants/States");
var App = require("../models/App");
var BackboneMixin = require("../mixins/BackboneMixin");
var AppVersionComponent = require("../components/AppVersionComponent");
var AppVersionListItemComponent = require("../components/AppVersionListItemComponent");
var PagedContentComponent = require("../components/PagedContentComponent");
var PagedNavComponent = require("../components/PagedNavComponent");

module.exports = React.createClass({
    displayName: "AppVersionListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onRollback: React.PropTypes.func
    },

    componentWillMount: function() {
      this.props.fetchAppVersions();
    },

    getResource: function() {
      return this.props.app.versions;
    },

    getInitialState: function() {
      return {
        currentPage: 0,
        itemsPerPage: 8
      };
    },

    handleRefresh: function() {
      this.props.fetchAppVersions();
    },

    handlePageChange: function(pageNum) {
      this.setState({currentPage: pageNum});
    },

    render: function() {
      // take out current version, to be displayed seperately
      var appVersions = this.props.app.versions.models.slice(1);

      var itemsPerPage = this.state.itemsPerPage;
      var currentPage = this.state.currentPage;

      var tableContents;

      if (this.props.fetchState === States.STATE_LOADING) {
        tableContents = <p className="text-muted text-center">Loading versions...</p>;
      } else if (this.props.fetchState === States.STATE_SUCCESS) {

        /* jshint trailing:false, quotmark:false, newcap:false */
        tableContents =
          <PagedContentComponent
              currentPage={currentPage}
              itemsPerPage={itemsPerPage}>
            {
              appVersions.map(function(v) {
                return (
                    <AppVersionListItemComponent
                      app={this.props.app}
                      appVersion={v}
                      key={v.get("version")}
                      onRollback={this.props.onRollback} />
                );
              }, this)
            }
          </PagedContentComponent>;
      } else {
        tableContents =
          <p className="text-danger text-center">
            Error fetching app versions
          </p>;
      }

      // at least two pages
      var pagedNav = appVersions.length > itemsPerPage ?
        <PagedNavComponent
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
          <div className="panel panel-header panel-inverse">
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
          <AppVersionComponent
              app={this.props.app}
              appVersion={this.props.app.getCurrentVersion()}
              currentVersion={true} />
            {versionTable}
        </div>
      );
    }
  });
