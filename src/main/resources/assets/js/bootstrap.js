/** @jsx React.DOM */

define([
  "React",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/NewAppButtonComponent"
], function(React, AppCollection, AppListComponent, NewAppButtonComponent) {
  var appCollection = window.appCollection = new AppCollection();

  React.renderComponent(
    <NewAppButtonComponent collection={appCollection} />,
    document.getElementById("new-app-button-container")
  );

  React.renderComponent(
    <AppListComponent collection={appCollection} />,
    document.getElementById("job-list")
  );

  appCollection.fetch({reset: true});
});
