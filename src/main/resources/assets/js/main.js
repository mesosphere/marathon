require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/JSXTransformer-0.10.0.max",
    "jquery": "libs/jquery-2.0.3",
    "React": "libs/react-with-addons-0.10.0",
    "Underscore": "libs/underscore-min"
  },
  shim: {
    Backbone: {
      deps: ["Underscore", "jquery"],
      exports: "Backbone"
    },
    Underscore: {
      exports: "_"
    }
  }
});

require([
  "React",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/NewAppButtonComponent"
], function(React, AppCollection, AppListComponent, NewAppButtonComponent) {
  var appCollection = new AppCollection();

  React.renderComponent(
    NewAppButtonComponent({collection: appCollection}),
    document.getElementById("new-app-button-container")
  );

  React.renderComponent(
    AppListComponent({collection: appCollection}),
    document.getElementById("job-list")
  );

  appCollection.fetch({reset: true});
});
