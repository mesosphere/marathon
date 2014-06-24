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
                <span className="col-md-11">
                  Version
                </span>
                <span className="clickable text-center col-md-1">
                </span>
              </div>
            </div>
              {
                this.props.fetchState === this.props.STATES.STATE_LOADING ?
                  <p className="text-muted text-center">Loading versions...</p> :
                  this.props.fetchState === this.props.STATES.STATE_SUCCESS ?
                    <div>
                      <AppVersionListItemComponent
                        app={this.props.app}
                        appVersion={currentVersion}
                        currentVersion={true} />
                      <PagedContentComponent
                        itemsPerPage={20}
                        noVisiblePages={6}>
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
                    <p className="text-danger text-center">Error fetching app versions</p>
              }
          </div>
        </div>
      );
    }
  });
});
