require.config({
  paths: {
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.1",
    "JSXTransformer": "libs/JSXTransformer-0.8.0.max",
    "jquery": "libs/jquery-2.0.3",
    "React": "libs/react-0.8.0",
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
  "jsx!bootstrap"
], function() {
  // We be bootstrappin'. The bootstrapping can't happen here because it needs
  // to be rendered as JSX. RequireJS's "data-main" is always loaded as type
  // "text/javascript", which won't work here.
});
