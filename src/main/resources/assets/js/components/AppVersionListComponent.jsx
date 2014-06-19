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

    getInitialState: function() {
      return {
        expandedAppVersions: {}
      };
    },

    render: function() {
      var listItems;
      if (this.props.appVersions == null) {
        listItems = (
          <tr><td className="text-muted text-center">Loading versions...</td></tr>
        );
      } else {
        if (this.props.appVersions.length > 0) {
          listItems = this.props.appVersions.map(function(v) {
            return (
              <AppVersionListItemComponent
                app={this.props.app}
                appVersion={v}
                key={v.get("version")}
                onRollback={this.props.onRollback}
                onShowDetails={this.props.onShowDetails} />
            );
          }, this);
        } else {
          listItems = <tr><td className="text-muted text-center">No previous versions</td></tr>
        }
      }

      return (
        <table className="table table-selectable">
          <tbody>
            {listItems}
          </tbody>
        </table>
      );
    }
  });
});
