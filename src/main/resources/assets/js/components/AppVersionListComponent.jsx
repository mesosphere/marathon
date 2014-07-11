/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "models/AppVersion",
  "mixins/BackboneMixin",
  "jsx!components/AppVersionListItemComponent",
  "jsx!components/PagedNavComponent",
  "jsx!components/PagedContentComponent",
], function(React, App, AppVersion, BackboneMixin, AppVersionListItemComponent,
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
        itemsPerPage: 10,
        noVisiblePages: 6
      };
    },

    handlePageChange: function(pageNum) {
      this.setState({currentPage: pageNum});
    },

    render: function() {
      var appVersions = this.props.appVersions.models;

      var itemsPerPage = this.state.itemsPerPage;
      var currentPage = this.state.currentPage;

      var useEndArrows =
        Math.ceil(appVersions.length / itemsPerPage) > this.state.noVisiblePages;

      var content;

      if (this.props.fetchState === this.props.STATES.STATE_LOADING) {
        content = <p className="text-muted text-center">Loading versions...</p>;
      } else if (this.props.fetchState === this.props.STATES.STATE_SUCCESS) {
        content =
          <div>
            <AppVersionListItemComponent
              app={this.props.app}
              appVersion={AppVersion.fromApp(this.props.app)}
              currentVersion={true} />
            <PagedContentComponent
              currentPage={currentPage}
              itemsPerPage={itemsPerPage}>
              {
                appVersions.map(function(v, i) {
                  if (i > 0) {
                    return (
                        <AppVersionListItemComponent
                          app={this.props.app}
                          appVersion={v}
                          currentVersion={false}
                          key={v.get("version")}
                          onRollback={this.props.onRollback} />
                    );
                  }
                }, this)
              }
            </PagedContentComponent>
          </div>
      } else {
        content =
          <p className="text-danger text-center">Error fetching app versions</p>;
      }

      return (
        <div>
          <p>
            <button className="btn btn-sm btn-info" onClick={this.props.fetchAppVersions}>
              ↻ Refresh
            </button>
            <div className="pull-right">
              {
                // is there at least two pages
                appVersions.length > itemsPerPage ?
                  <PagedNavComponent
                    currentPage={currentPage}
                    onPageChange={this.handlePageChange}
                    itemsPerPage={itemsPerPage}
                    noItems={appVersions.length}
                    noVisiblePages={this.state.noVisiblePages}
                    useEndArrows={useEndArrows} /> :
                  null
              }
            </div>
          </p>
          <div className="panel-group">
            <div className="panel panel-header panel-inverse">
              <div className="panel-heading">
                <div className="row">
                  <div className="col-xs-11">
                    Version
                  </div>
                  <div className="clickable text-center col-xs-1">
                  </div>
                </div>
              </div>
            </div>
              {content}
          </div>
        </div>
      );
    }
  });
});
