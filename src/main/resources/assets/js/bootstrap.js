/** @jsx React.DOM */

define([
  "React",
  "models/AppCollection",
  "jsx!components/AppListComponent"
], function(React, AppCollection, AppListComponent) {
  var appCollection = window.appCollection = new AppCollection({});

  React.renderComponent(
    <AppListComponent collection={appCollection} />,
    document.getElementById("job-list")
  );

  appCollection.fetch({reset: true});
});
