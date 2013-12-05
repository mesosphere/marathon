/*globals $, _, Backbone */

/**
 * fastLiveFilter jQuery plugin 1.0.3
 *
 * Copyright (c) 2011, Anthony Bush
 * License: <http://www.opensource.org/licenses/bsd-license.php>
 * Project Website: http://anthonybush.com/projects/jquery_fast_live_filter/
 **/
jQuery.fn.fastLiveFilter = function(list, options) {
  // Options: input, list, timeout, callback
  options = options || {};
  list = jQuery(list);
  var input = this;
  var timeout = options.timeout || 0;
  var callback = options.callback || function() {};

  var keyTimeout;
  var lis = $('.app-list-item');
  var oldDisplay = lis.length > 0 ? lis[0].style.display : "block";

  // do a one-time callback on initialization to make sure everything's in sync
  callback(lis.length);

  input.change(function() {
    var filter = input.val().toLowerCase();
    var li, oli;
    var numShown = 0;

    var show = [];
    for (var i = 0; i < lis.length; i++) {
      oli = lis[i];
      li = $(oli).find('h3')[0];
      if ((li.textContent || li.innerText || "").toLowerCase().indexOf(filter) >= 0) {
        if (oli.style.display === "none") {
          oli.style.display = oldDisplay;
        }
        show.push(window.all.get(oli.classList[1]));
        numShown++;
      } else {
        if (oli.style.display !== "none") {
          oli.style.display = "none";
        }
      }
    }
    callback(numShown, show);
    return false;
  }).keydown(function() {
    // TODO: one point of improvement could be in here: currently the change event is
    // invoked even if a change does not occur (e.g. by pressing a modifier key or
    // something)
    clearTimeout(keyTimeout);
    keyTimeout = setTimeout(function() { input.change(); }, timeout);
  });

  // Maintain jQuery chainability
  return this;
};

(function(){

  (function(exports, Backbone){

    exports.Backpack = exports.Backpack || {};
    exports.Backpack.Models = exports.Backpack.Models || {};

    var LightboxModel = Backbone.Model.extend({

      defaults: {
        'open': false,
        'lock': false,
        'backgroundColor': 'rgba(0,0,0,0.9)'
      },

      setContent: function(content){
        this.set('content', content);
      },

      open: function(){
        this.set('open', true);
      },

      close: function(){
        this.trigger('close');
        this.set('open', false);
      },

      dismiss: function(){
        if (!this.get('lock')) {
          this.close();
        }
      },

      lock: function(){
        this.set('lock', true);
      },

      unlock: function(){
        this.set('lock', false);
      },

      color: function(color){
        this.set('backgroundColor', color);
      }

    });

    exports.Backpack.Models.Lightbox = LightboxModel;

  })(window, Backbone);

  (function(exports, $, _, Backbone){
    var Lightbox;

    exports.Backpack = exports.Backpack || {};
    exports.Backpack.Models = exports.Backpack.Models || {};

    Lightbox = Backbone.View.extend({

      template:  _.template($("#lightbox").text()),
      className: 'lightbox',
      events: {
        'click': 'dismiss',
        'click .lb-content': 'noop',
        'click [data-lightbox-close]': 'close'
      },

      bindings: function(){
        this.model.on('change:open', this.toggle, this);
        this.model.on('change:content', this.updateContent, this);
        this.model.on('change:backgroundColor', this.updateColor, this);
      },

      initialize: function(){
        this.model = new Backpack.Models.Lightbox();
        this.bindings();
        this.toggle();
        this.append();
        if (this.options.content) {
          this.content(this.options.content);
        }
      },

      render: function(){
        var template = this.template();
        this.$el.html(template);
        return this;
      },

      content: function(content){
        this.model.setContent(content);
        return this;
      },

      updateContent: function(){
        var content = this.model.get('content');
        var el = content.render().el;
        this.$content = this.$el.find('.lb-content');
        this.$content.html(el);
      },

      updateColor: function(){
        var color = this.model.get('backgroundColor');
        this.$el.css('background-color', color);
      },

      color: function(color){
        this.model.color(color);
      },

      append: function(){
        this.render();
        $('body').append(this.$el);
      },

      toggle: function(){
        var open = this.model.get('open');
        this.$el.toggle(open);
      },

      lock: function(){
        this.model.lock();
        return this;
      },

      unlock: function(){
        this.model.unlock();
        return this;
      },

      open: function(event){
        this.model.open();
        return this;
      },

      close: function(event){
        this.model.close();
        return this;
      },

      dismiss: function(event){
        this.model.dismiss();
        return this;
      },

      noop: function(event){
        event.stopPropagation();
      }

    });

    exports.Backpack.Lightbox = Lightbox;

  })(window, jQuery, _, Backbone);


  // Mustache style templats {{ }}
  _.templateSettings = {
    interpolate : /\{\{(.+?)\}\}/g
  };

  Backbone.emulateHTTP = true;

  var $terminal = $('#terminal'),
      $setter = $('#setter'),
      $systemText = $('#system-text'),
      $promptText = $('#prompt-text'),
      caret = $('#caret');

  $terminal.click(function(e){
    $setter.focus();
  });

  $setter.keydown(function(e){
    key(this.value);
  });

  $setter.keyup(function(e){
    key(this.value);
  });

  $setter.keypress(function(e){
    key(this.value);
  });

  function key(text) {
    write(text);
    move(text.length);
  }

  function write(text) {
    $systemText.html(text);
  }

  function move(length) {

  }

  $setter.focus();

  var Item = Backbone.Model.extend({
    url: 'v1/apps/start',

    defaults: function() {
      return {
        id: _.uniqueId('app_'),
        cmd: null,
        mem: 10.0,
        cpus: 0.1,
        instances: 1,
        uris: []
      };
    },

    sync: function(method, model, options) {
      options = options || {};

      if (method === 'delete') {
        options = _.extend(options, {
          url: 'v1/apps/stop',
          contentType: 'application/json',
          data: JSON.stringify(options.attrs || model.toJSON(options))
        });
      } else if (method === 'scale') {
        method = 'create';
        options = _.extend(options, {
          url: 'v1/apps/scale',
          contentType: 'application/json',
          data: JSON.stringify(options.attrs || model.toJSON(options))
        });
      }

      Backbone.sync.apply(this, [method, model, options]);
    },

    scale: function(num, options) {
      options = options || {};
      this.set('instances', num);
      this.sync('scale', this, options);
    }
  });

  var ItemList = Backbone.Collection.extend({
    comparator: 'id',
    model: Item,
    url: 'v1/apps/'
  });

  // Simple base view to keep the render logic similar.
  var MarathonView = Backbone.View.extend({

    data: function() {
      console.log(this.model);
      return this.model.toJSON()
    },

    remove: function() {
      this.$el.remove();
    },

    render: function() {
      var data = this.data(),
          html = this.template(data);
      this.$el.html(html);
      return this;
    }
  });

  var AppItemView = MarathonView.extend({
    tagName: 'tr',
    template: _.template($("#app-item").text()),

    events: {
      'click': 'showDetails'
    },

    initialize: function() {
      this.listenTo(this.model, {
        'change:instances': this.render,
        destroy: this.remove
      });

      this.$el.addClass(this.model.get('id'));
    },

    data: function() {
      var attr = this.model.toJSON(),
          total = (attr.cpus * attr.instances),
          uriCount = attr.uris.length,
          uris = (attr.uris.join('<li>'));
      attr = _.extend(attr, {
        total: total,
        uris: uris,
        uriCount: uriCount
      });
      return attr;
    },

    showDetails: function() {
      var lightbox = new Backpack.Lightbox();
      var detail_view = new AppItemDetail({
        model: this.model,
        lightbox: lightbox
      });
      lightbox.content(detail_view);
      lightbox.open();
    }
  });

  var AppItemDetail = MarathonView.extend({
    className: 'window',
    template: _.template($("#app-item-detail").text()),

    events: {
      'click .suspend': 'suspend',
      'click .destroy': 'destroy',
      'click .scale': 'scale'
    },

    initialize: function(opts) {
      this.lightbox = opts.lightbox;
    },

    suspend: function(e) {
      if (confirm("Suspend " + this.model.id + "?\n\nThe application will be scaled to 0 instances.")) {
        this.model.scale(0);
      }

      e.preventDefault();
    },

    destroy: function(e) {
      var ok = confirm("Destroy application " + this.model.id + "?\n\nThis is irreversible.");
      if (ok) {
        this.model.destroy();
        this.lightbox.close();
      }

      e.preventDefault();
    },

    scale: function(e) {
      var instances = prompt('How many instances?', this.model.get('instances'));
      if (instances) {
        this.model.scale(instances);
      }

      e.preventDefault();
    }

  });

  var FormView = MarathonView.extend({
    className: 'window',
    template: _.template($("#form-view").text()),

    events: {
      'submit form': 'save'
    },

    render: function() {
      this.$el.html(this.template(this.model.toJSON()));
      return this;
    },

    save: function(e) {
      e.preventDefault();

      var $inputs = this.$('input');
      var data = {};

      $inputs.each(function(index, el) {
        var $el = $(el),
            name = $el.attr('name'),
            val = $el.val();

        if (name === 'uris') {
          val = val.split(',');
          // strip whitespace
          val = _.map(val, function(s){return s.replace(/ /g,''); });
          // reject empty
          val = _.reject(val, function(s){return (s === '');});
        }

        data[name] = val;
      });

      this.trigger('save', data);
    }
  });

  var HomeView = MarathonView.extend({
    el: '.start-view-list',

    events: {
      'click .add-button': 'addNew',
    },

    initialize: function() {
      this.$addButton = this.$('.add-button');
      this.$table = this.$(".item-table > tbody");
      this.lightbox = new Backpack.Lightbox();

      this.listenTo(this.collection, {
        add: this.add,
        reset: this.render
      });
    },

    render: function() {
      var self = this;
      this.collection.each(function(model) {
        var view = new AppItemView({model: model});
        self.$table.append(view.render().el);
      });
      return this;
    },

    add: function(model, collection, options) {
      var view = new AppItemView({model: model});
      this.$table.append(view.render().el);
    },

    addNew: function() {
      this.formView = new FormView({model: new Item()});
      this.formView.once('save', function(data) {
        this.collection.create(data);
        this.lightbox.close();
      }, this);

      this.lightbox.content(this.formView);
      this.lightbox.open();
    }
  });


  var Router = Backbone.Router.extend({
    routes: {
      '': 'index'
    },

    index: function() {
      var apps = window.all = new ItemList();
      new HomeView({
        collection: apps
      }).render();

      apps
        .fetch({reset: true})
        .done(function() {
          var $input = $('#setter');
          var $caret = $('.system-caret');

          $input.fastLiveFilter('.start-view-list');

          $input.focusin(function() {
            $caret.addClass('focus');
          });

          $input.focusout(function() {
            $caret.removeClass('focus');
          });
        });
    }

  });

  window.router = new Router();
  Backbone.history.start({
    pushState: true
  });

})();
