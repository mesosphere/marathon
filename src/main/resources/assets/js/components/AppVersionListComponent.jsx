/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/AppVersionListItemComponent",
  "jsx!components/PagedContentComponent",
], function(React, App, AppVersionListItemComponent, PagedContentComponent) {
  "use strict";

  return React.createClass({
    displayName: "AppVersionListComponent",
    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersions: React.PropTypes.object.isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onRollback: React.PropTypes.func
    },

    render: function() {
      var appVersions = this.props.appVersions.models;
      // Should I use this here?
      // new Date(this.props.app.get("version")).getTime() === new Date(v.get("version")).getTime()
      // this also adds the need to refresh the app configuration

      var currentVersion = this.props.appVersions.at(0);
      return (
        <div>
          <p>
            <button className="btn btn-sm btn-info" onClick={this.props.fetchAppVersions}>
              ↻ Refresh
            </button>
          </p>
          <div className="panel-group">
            <div className="panel panel-header panel-inverse">
              <div className="panel-heading row">
                <span className="col-md-7">
                  Version
                </span>
                <span className="text-center col-md-2">
                  Diff from
                </span>
                <span className="text-center col-md-2">
                  Diff to
                </span>
                <span className="clickable text-center col-md-1">
                </span>
              </div>
            </div>
              {
                this.props.fetchState === this.props.STATES.STATE_LOADING ?
                  <div className="text-muted text-center">Loading versions...</div> :
                  this.props.fetchState === this.props.STATES.STATE_SUCCESS ?
                    <div>
                      <AppVersionListItemComponent
                        app={this.props.app}
                        appVersion={currentVersion}
                        currentVersion={true} />
                      <PagedContentComponent itemsPerPage={20} >
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
                    </div> :
                    <div className="text-danger text-center">Error fetching app versions</div>
              }
          </div>
        </div>
      );
    }
  });
});
