"use strict";

var assert = require("assert");
var Backbone = require("backbone");
var sinon = require("sinon");

var AppCollection = require("../../js/models/AppCollection");
var AppCollectionMockData = require("../mock/AppCollection.mockData");

module.exports = {

  "AppCollection": {

    beforeEach: function () {
      this.sinon = sinon.sandbox.create();
      this.ajaxStub = this.sinon.stub(Backbone, "ajax").
        yieldsTo("success", AppCollectionMockData);

      this.collection = new AppCollection();
    },

    afterEach: function () {
      this.sinon.restore();
      this.ajaxStub.restore();
    },

    "should be populated with data": function () {
      this.collection.fetch();
      assert.equal("ruby toggleServer.rb -p ", this.collection.get("/my-app").get("cmd"));
    }
  }
};
