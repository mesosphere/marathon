/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppVersionListItemComponent",
], function(React, AppVersionListItemComponent) {
  return React.createClass({
    displayName: "AppVersionListComponent",

    propTypes: {
      app: React.PropTypes.object.isRequired,
      appVersions: React.PropTypes.array,
      onRollback: React.PropTypes.func
    },

    render: function() {
      var appVersions = this.props.appVersions;
      return (
        <ul className="list-group">
            {
              appVersions == null ?
                <li className="text-muted text-center col-md-12">Loading versions...</li> :
                appVersions.length > 0 ?
                  appVersions.map(function(v, i) {
                    return (
                        <AppVersionListItemComponent
                          app={this.props.app}
                          appVersion={v}
                          key={v.get("version")}
                          onRollback={this.props.onRollback}
                          currentVersion={this.props.app.get("version") === v.get("version")} />
                    );
                  }, this) :
                  <li className="text-danger text-center col-md-12">Error fetching app versions</li>
            }
        </ul>
      );
    }
  });
});
