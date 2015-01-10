define([
  "Backbone",
  "Underscore",
  "models/AppVersion",
  "models/AppVersionCollection",
  "models/Task",
  "models/TaskCollection"
], function(Backbone, _, AppVersion, AppVersionCollection, Task, TaskCollection) {
  "use strict";

  function ValidationError(attribute, message) {
    this.attribute = attribute;
    this.message = message;
  }

  var DEFAULT_HEALTH_MSG = "Unknown";
  var EDITABLE_ATTRIBUTES = ["cmd", "constraints", "container", "cpus", "env",
    "executor", "id", "instances", "mem", "disk", "ports", "uris"];
  var UPDATEABLE_ATTRIBUTES = ["instances", "tasksRunning", "tasksStaged", "deployments"];

  // Matches the command executor, like "//cmd", and custom executors starting
  // with or without a "/" but never two "//", like "/custom/exec". Double slash
  // is only permitted as a prefix to the cmd executor, "/custom//exec" is
  // invalid for example.
  var VALID_EXECUTOR_PATTERN = "^(|\\/\\/cmd|\\/?[^\\/]+(\\/[^\\/]+)*)$";
  var VALID_EXECUTOR_REGEX = new RegExp(VALID_EXECUTOR_PATTERN);

  var VALID_CONSTRAINTS = ["unique", "like", "unlike", "cluster", "group_by"];

  function findHealthCheckMsg(healthCheckResults, context) {
    return healthCheckResults.map(function (hc, index) {
      if (hc && !hc.alive) {
        var failedCheck = this.get("healthChecks")[index];
        return "Warning: Health check '" +
          (failedCheck.protocol ? failedCheck.protocol + " " : "") +
          (this.get("host") ? this.get("host") : "") +
          (failedCheck.path ? failedCheck.path : "") + "'" +
          (hc.lastFailureCause ?
            " returned with status: '" + hc.lastFailureCause + "'" :
            " failed") +
          ".";
      }
    }, context);
  }

  function isValidConstraint(p) {
    if (p.length < 2 || p.length > 3) {
      return false;
    }

    /* TODO: should be dynamic. It should be in Scala, but it's impossible to
     * return an error on a specific field.
     */
    var operator = p[1];
    return (_.indexOf(VALID_CONSTRAINTS, operator.toLowerCase()) !== -1);
  }

  return Backbone.Model.extend({
    defaults: function() {
      return {
        cmd: null,
        constraints: [],
        container: null,
        cpus: 0.1,
        deployments: [],
        env: {},
        executor: "",
        healthChecks: [],
        id: null,
        instances: 1,
        mem: 16.0,
        disk: 0.0,
        ports: [0],
        uris: []
      };
    },

    initialize: function(options) {
      _.bindAll(this, "formatTaskHealthMessage");
      // If this model belongs to a collection when it is instantiated, it has
      // already been persisted to the server.
      this.persisted = (this.collection != null);

      this.tasks = new TaskCollection(null, {appId: this.id});
      this.versions = new AppVersionCollection(null, {appId: this.id});
      this.on({
        "change:id": function(model, value, options) {
          // Inform AppVersionCollection and TaskCollection of new ID so it can
          // send requests to the new endpoint.
          this.tasks.options.appId = value;
          this.versions.options.appId = value;
        },
        "sync": function(model, response, options) {
          this.persisted = true;
        }
      });
    },

    isNew: function() {
      return !this.persisted;
    },

    isDeploying: function() {
      return !_.isEmpty(this.get("deployments"));
    },

    allInstancesBooted: function() {
      return this.get("tasksRunning") === this.get("instances");
    },

    formatTasksRunning: function() {
      var tasksRunning = this.get("tasksRunning");
      return tasksRunning == null ? "-" : tasksRunning;
    },

    formatTaskHealthMessage: function(task) {
      if (task) {
        var msg;

        switch(task.getHealth()) {
          case Task.HEALTH.HEALTHY:
            msg = "Healthy";
            break;
          case Task.HEALTH.UNHEALTHY:
            var healthCheckResults = task.get("healthCheckResults");
            if (healthCheckResults != null) {
              msg = findHealthCheckMsg(healthCheckResults, this);
            }
            break;
          default:
            msg = DEFAULT_HEALTH_MSG;
            break;
        }

        return msg;
      }
      return null;
    },

    parse: function(data) {
      // When PUTing the response is a 204 (No content) and should not be
      // parsed.
      if (data != null) {
        var parsedVersion = Date.parse(data.version);
        if (!isNaN(parsedVersion)) { data.version = new Date(parsedVersion); }
      }

      return data;
    },

    /* Updates only those attributes listed in `UPDATEABLE_ATTRIBUTES` to prevent
     * showing values that cannot be changed.
     */
    update: function(attrs) {

      var filteredAttributes = _.filter(UPDATEABLE_ATTRIBUTES, function(attr) {
        return attrs[attr] != null;
      });

      var allowedAttrs = _.pick(attrs, filteredAttributes);

      this.set(allowedAttrs);
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

    setVersion: function(version) {
      this.set(_.pick(version.attributes, EDITABLE_ATTRIBUTES));
    },

    getCurrentVersion: function() {
      var version = new AppVersion();
      version.set(this.attributes);

      // make sure date is a string
      version.set({
        "version": version.get("version").toISOString()
      });

      // transfer app id
      version.options = {
        appId: this.get("id")
      };

      return version;
    },

    suspend: function(options) {
      this.save({instances: 0}, options);
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

      if (_.isNaN(attrs.disk) || !_.isNumber(attrs.disk) || attrs.disk < 0) {
        errors.push(
          new ValidationError("disk", "Disk Space must be a non-negative Number"));
      }

      if (_.isNaN(attrs.instances) || !_.isNumber(attrs.instances) ||
          attrs.instances < 0) {
        errors.push(
          new ValidationError("instances", "Instances must be a non-negative Number"));
      }

      if (!_.isString(attrs.id) || attrs.id.length < 1) {
        errors.push(new ValidationError("id", "ID must not be empty"));
      }

      if (_.isString(attrs.executor) && !VALID_EXECUTOR_REGEX.test(attrs.executor)) {
        errors.push(
          new ValidationError(
            "executor",
            "Executor must be the string '//cmd', a string containing only single slashes ('/'), or blank."
          )
        );
      }

      if (!_.every(attrs.ports, function(p) { return _.isNumber(p); })) {
        errors.push(
          new ValidationError("ports", "Ports must be a list of Numbers"));
      }

      if (!attrs.constraints.every(isValidConstraint)) {
        errors.push(
          new ValidationError("constraints",
            "Invalid constraints format or operator. Supported operators are " +
            VALID_CONSTRAINTS.map(function(c) {
              return "`" + c + "`";
            }).join(", ") +
            ". See https://github.com/mesosphere/marathon/wiki/Constraints."
          )
        );
      }

      if (errors.length > 0) { return errors; }
    },

    url : function () {
      return this.isNew() ? "v2/apps" : "v2/apps/" + this.id;
    }
  }, {
    VALID_EXECUTOR_PATTERN: VALID_EXECUTOR_PATTERN
  });
});
