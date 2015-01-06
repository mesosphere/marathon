/** @jsx React.DOM */

define([
  "React",
  "constants/States",
  "models/App",
  "models/AppVersion",
  "mixins/BackboneMixin",
  "jsx!components/AppVersionComponent",
  "jsx!components/AppVersionListItemComponent",
  "jsx!components/PagedContentComponent",
  "jsx!components/PagedNavComponent"
], function(React, States, App, AppVersion, BackboneMixin, AppVersionComponent,
    AppVersionListItemComponent, PagedContentComponent, PagedNavComponent) {
  "use strict";

  return React.createClass({
    displayName: "AppVersionListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onRollback: React.PropTypes.func
    },

    componentWillMount: function() {
      this.props.fetchAppVersions();
      this.props.app.on("error", this.handleAppUpdateError);
      this.props.app.on("sync", this.handleAppUpdateSuccess);
    },

    componentWillUnmount: function() {
      this.props.app.off("error", this.handleAppUpdateError);
      this.props.app.off("sync", this.handleAppUpdateSuccess);
    },

    getResource: function() {
      return this.props.app.versions;
    },

    getInitialState: function() {
      return {
        currentPage: 0,
        itemsPerPage: 8,
        hasChanged: false
      };
    },

    handleRefresh: function(event) {
      this.props.fetchAppVersions();
    },

    handlePageChange: function(pageNum) {
      this.setState({currentPage: pageNum});
    },

    handleSubmit: function(event) {
      event.preventDefault();

      if (!this.props.app.isValid()) {
        this.forceUpdate();
        return;
      }

      this.props.app.save();
    },

    handleAppUpdateError: function(appModel, response) {
      this.setState({
        serverError: JSON.parse(response.responseText)
      });
    },

    handleAppUpdateSuccess: function() {
      this.setState({
        hasChanged: false
      });
    },

    handleChange: function() {
      this.setState({
        hasChanged: true
      });
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

      var saveButton = this.state.hasChanged ? (
        <button className="btn btn-sm btn-info pull-right" onClick={this.handleSave} type="submit">
          Save changes
        </button>
      ) : null;

      return (
        <form method="post" role="form" onSubmit={this.handleSubmit}>
          { this.state.serverError ? <p className="alert alert-warning">{this.state.serverError}</p> : null }
          <h5>
            Current Version
            <button className="btn btn-sm btn-info pull-right" onClick={this.handleRefresh} type="button">
              ↻ Refresh
            </button>
            {saveButton}
          </h5>
          <AppVersionComponent
              app={this.props.app}
              appVersion={this.props.app.getCurrentVersion()}
              currentVersion={true}
              onChange={this.handleChange} />
            {versionTable}
        </form>
      );
    }
  });
});
