define([
  "Backbone",
  "Underscore",
  "models/TaskCollection"
], function(Backbone, _, TaskCollection) {
  function ValidationError(attribute, message) {
    this.attribute = attribute;
    this.message = message;
  }

  var EDITABLE_ATTRIBUTES = ["cmd", "constraints", "container", "cpus", "env",
    "executor", "id", "instances", "mem", "ports", "uris"];

  return Backbone.Model.extend({
    defaults: function() {
      return {
        cmd: null,
        constraints: [],
        container: null,
        cpus: 0.1,
        env: {},
        executor: "",
        id: _.uniqueId("app_"),
        instances: 1,
        mem: 16.0,
        ports: [0],
        uris: []
      };
    },
    initialize: function(options) {
      // If this model belongs to a collection when it is instantiated, it has
      // already been persisted to the server.
      this.persisted = (this.collection != null);

      this.tasks = new TaskCollection(null, {appId: this.id});
      this.on({
        "change:id": function(model, value, options) {
          // Inform TaskCollection of new ID so it can send requests to the new
          // endpoint.
          this.tasks.options.appId = value;
        },
        "sync": function(model, response, options) {
          this.persisted = true;
        }
      });
    },
    isNew: function() {
      return !this.persisted;
    },
    allInstancesBooted: function() {
      return this.get("tasksRunning") === this.get("instances");
    },
    formatTasksRunning: function() {
      var tasksRunning = this.get("tasksRunning");
      return tasksRunning === undefined ? "-" : tasksRunning;
    },
    /* Sends only those attributes listed in `EDITABLE_ATTRIBUTES` to prevent
     * sending immutable values like "tasksRunning" and "tasksStaged" and the
     * "version" value, which when sent prevents any other attributes from being
     * changed.
     */
    save: function(attrs, options) {
      options || (options = {});

      var allAttrs;
      if (options.patch === true) {
        allAttrs = _.extend({}, this.changedAttributes, attrs);
      } else {
        allAttrs = _.extend({}, this.attributes, attrs);
      }

      // Filter out null and undefined values
      var filteredAttributes = _.filter(EDITABLE_ATTRIBUTES, function(attr) {
        return allAttrs[attr] != null;
      });

      var allowedAttrs = _.pick(allAttrs, filteredAttributes);

      /* When `options.data` is supplied, Backbone does not attempt to infer its
       * content type. It must be explicitly set for the content to be
       * interpreted as JSON.
       */
      options.contentType = "application/json";
      options.data = JSON.stringify(allowedAttrs);

      return Backbone.Model.prototype.save.call(
        this, allowedAttrs, options);
    },
    validate: function(attrs, options) {
      var errors = [];

      if (_.isNaN(attrs.mem) || !_.isNumber(attrs.mem) || attrs.mem < 0) {
        errors.push(
          new ValidationError("mem", "Memory must be a non-negative Number"));
      }

      if (_.isNaN(attrs.cpus) || !_.isNumber(attrs.cpus) || attrs.cpus < 0) {
        errors.push(
          new ValidationError("cpus", "CPUs must be a non-negative Number"));
      }

      if (_.isNaN(attrs.instances) || !_.isNumber(attrs.instances) ||
          attrs.instances < 0) {
        errors.push(
          new ValidationError("instances", "Instances must be a non-negative Number"));
      }

      if (!_.isString(attrs.id) || attrs.id.length < 1) {
        errors.push(
          new ValidationError("id", "ID must be a non-empty String"));
      }

      if (!_.every(attrs.ports, function(p) { return _.isNumber(p); })) {
        errors.push(
          new ValidationError("ports", "Ports must be a list of Numbers"));
      }

      if (!_.isString(attrs.cmd) || attrs.cmd.length < 1) {
        // Prevent erroring out on UPDATE operations like scale/suspend. 
        // If cmd string is empty, then don't error out if an executor and
        // container are provided.
        if (!_.isString(attrs.executor) || attrs.executor.length < 1 ||
            attrs.container == null || !_.isString(attrs.container.image) ||
            attrs.container.image.length < 1 ||
            attrs.container.image.indexOf('docker') != 0) {
          errors.push(
            new ValidationError("cmd",
              "Command must be a non-empty String if executor and container image are not provided"
            )
          );
        }
      }

      if (errors.length > 0) return errors;
    }
  });
});
