/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "models/AppVersion",
  "mixins/BackboneMixin",
  "jsx!components/AppVersionComponent",
  "jsx!components/AppVersionListItemComponent",
  "jsx!components/PagedNavComponent",
  "jsx!components/PagedContentComponent",
], function(React, App, AppVersion, BackboneMixin, AppVersionComponent, AppVersionListItemComponent,
    PagedNavComponent, PagedContentComponent) {
  "use strict";

  return React.createClass({
    displayName: "AppVersionListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersions: React.PropTypes.object.isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onRollback: React.PropTypes.func
    },

    getResource: function() {
      return this.props.appVersions;
    },

    getInitialState: function() {
      return {
        currentPage: 0,
        itemsPerPage: 9
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
      var appVersions = this.props.appVersions.models.slice(1);

      var itemsPerPage = this.state.itemsPerPage;
      var currentPage = this.state.currentPage;

      var useArrows = appVersions.length > itemsPerPage;

      var tableContents;

      if (this.props.fetchState === this.props.STATES.STATE_LOADING) {
        tableContents = <p className="text-muted text-center">Loading versions...</p>;
      } else if (this.props.fetchState === this.props.STATES.STATE_SUCCESS) {
        tableContents =
          <div>
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
            </PagedContentComponent>
          </div>
      } else {
        tableContents =
          <p className="text-danger text-center">Error fetching app versions</p>;
      }

      // at least two pages
      var pagedNav = appVersions.length > itemsPerPage ?
        <PagedNavComponent
          currentPage={currentPage}
          onPageChange={this.handlePageChange}
          itemsPerPage={itemsPerPage}
          noItems={appVersions.length}
          useArrows={useArrows} /> :
        null;

      // at least one older version
      var versionTable = appVersions.length > 0 ?
        <div className="panel-group">
            <div className="panel panel-header panel-inverse">
              <div className="panel-heading">
                <div className="row">
                  <div className="col-xs-6">
                    Older versions
                  </div>
                  <div className="col-xs-6 text-right">
                    {pagedNav}
                  </div>
                </div>
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
            appVersion={AppVersion.fromApp(this.props.app)}
            currentVersion={true} />
          {versionTable}
        </div>
      );
    }
  });
});
