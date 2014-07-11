 define([
  "Backbone",
  "Underscore",
  "models/App",
  "models/SortableCollection"
], function(Backbone, _, App, SortableCollection) {
  "use strict";

  function ValidationError(attribute, message) {
    this.attribute = attribute;
    this.message = message;
  }

  return SortableCollection.extend({
    model: App,
    initialize: function(models, options) {
      this.options = options;
      this.setComparator("-id");
      this.sort();
    },

    parse: function(response) {
      return response.apps;
    },

    create: function(attributes, options) {
      options || (options = {});
      var errorCallback = options.error;
      var successCallback = options.success;
      options.error = function(model, response) {
        this.validateResponse(response);
        if (_.isFunction(errorCallback)) {
          errorCallback(model, response);
        }
      }.bind(this);
      options.success = function(model, response) {
        this.validationError = [];
        if (_.isFunction(successCallback)) {
          successCallback(model, response);
        }
      }.bind(this);
      SortableCollection.prototype.create.apply(this, arguments);
    },

    clearValidation: function() {
      this.validationError = [];
    },

    validateResponse: function(response) {
      this.clearValidation();

      if (response.status === 422) {
        this.validationError.push(
          new ValidationError("id", "An app with this ID already exists")
        );
        return true;
      } else if (response.status >= 500) {
        this.validationError.push(
          new ValidationError("general", "Server error, could not create")
        );
        return true;
      } else {
        this.validationError.push(
          new ValidationError("general", "Creation unsuccessful")
        );
        return true;
      }
    },

    url: "/v2/apps"
  });
});
