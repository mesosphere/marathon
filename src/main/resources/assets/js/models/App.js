define([
  "Backbone",
  "Underscore",
  "models/TaskCollection"
], function(Backbone, _, TaskCollection) {
  return Backbone.Model.extend({
    defaults: function() {
      return {
        id: _.uniqueId("app_"),
        cmd: null,
        mem: 10.0,
        cpus: 0.1,
        instances: 1,
        uris: []
      };
    },
    initialize: function() {
      this.set("tasks", new TaskCollection(null, {appId: this.id}));
      this.listenTo(this, "change:id", function(model, value, options) {
        this.get("tasks").options.appId = value;
      });
    },
    scale: function(instances) {
      this.set("instances", instances);
      this.save();
    },
    sync: function(method, model, options) {
      var localOptions = options || {};
      var localMethod = options.add ? "create" : method;
      var upperCaseMethod = localMethod.toUpperCase();

      if (upperCaseMethod in model.urls) {
        options.contentType = "application/json";
        options.data = JSON.stringify(model.toJSON());
        options.method = "POST";
        options.url = model.urls[upperCaseMethod];
      }

      Backbone.sync.apply(this, [localMethod, model, localOptions]);
    },
    urls: {
      "CREATE": "v1/apps/start",
      "DELETE": "v1/apps/stop",
      "UPDATE": "v1/apps/scale"
    },
    validate: function(attrs, options) {
      var errors = [];

      if (!_.isNumber(attrs.mem) || attrs.mem < 0) {
        errors.push("Memory must be a non-negative Number");
      }

      if (!_.isNumber(attrs.cpus) || attrs.cpus < 0) {
        errors.push("CPUs must be a non-negative Number");
      }

      if (!_.isNumber(attrs.instances) || attrs.instances < 0) {
        errors.push("Instances must be a non-negative Number");
      }

      if (!_.isString(attrs.id) || attrs.id.length < 1) {
        errors.push("ID must be a non-empty String");
      }

      if (!_.isString(attrs.cmd) || attrs.cmd.length < 1) {
        errors.push("Cmd must be a non-empty String");
      }

      if (errors.length > 0) return errors;
    }
  });
});
