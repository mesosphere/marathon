require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/JSXTransformer-0.10.0.max",
    "jquery": "libs/jquery-2.0.3",
    "mousetrap": "libs/mousetrap-1.4.6",
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
  "mousetrap",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/NewAppButtonComponent"
], function(React, Mousetrap, AppCollection, AppListComponent,
    NewAppButtonComponent) {

  var appCollection = new AppCollection();

  var newAppButton = React.renderComponent(
    NewAppButtonComponent({collection: appCollection}),
    document.getElementById("new-app-button-container")
  );

  React.renderComponent(
    AppListComponent({collection: appCollection}),
    document.getElementById("job-list")
  );

  Mousetrap.bind("c", function() {
    newAppButton.showModal();
  }, "keyup");

  // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
  // input elements so the new app modal can be closed via "esc".
  var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
  Mousetrap.stopCallback = function(e, element, combo) {
    if (combo === "esc" || combo === "escape") {
      return false;
    }

    return mousetrapOriginalStopCallback.apply(null, arguments);
  };

  appCollection.fetch({reset: true});
});
