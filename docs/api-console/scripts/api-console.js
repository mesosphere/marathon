(function (window) {
  'use strict';

  // Namespaces
  RAML.Directives     = {};
  RAML.Services       = {};
  RAML.Filters        = {};
  RAML.Services.TryIt = {};
  RAML.Security       = {};
  RAML.Settings       = RAML.Settings || {};

  // Angular Modules
  angular.module('RAML.Directives', []);
  angular.module('RAML.Services', ['raml']);
  angular.module('RAML.Security', []);
  angular.module('ramlConsoleApp', [
    'RAML.Directives',
    'RAML.Services',
    'RAML.Security',
    'hc.marked',
    'ui.codemirror',
    'hljs',
    'ngSanitize'
  ]).config(['hljsServiceProvider', function (hljsServiceProvider) {
    hljsServiceProvider.setOptions({
      classPrefix: 'raml-console-hljs-'
    });
  }]);

  var loc = window.location;
  var uri = loc.protocol + '//' + loc.host + loc.pathname.replace(/\/$/, '');

  window.hljs.configure({
    classPrefix: 'raml-console-hljs-'
  });

  // Settings
  RAML.Settings.proxy             = RAML.Settings.proxy || false;
  RAML.Settings.oauth2RedirectUri = RAML.Settings.oauth2RedirectUri || uri + '/authentication/oauth2.html';
  RAML.Settings.oauth1RedirectUri = RAML.Settings.oauth1RedirectUri || uri + '/authentication/oauth1.html';
  RAML.Settings.marked            = {
    gfm: true,
    tables: true,
    breaks: true,
    pedantic: false,
    sanitize: false,
    smartLists: true,
    silent: false,
    langPrefix: 'lang-',
    smartypants: false,
    headerPrefix: '',
    renderer: new window.marked.Renderer(),
    xhtml: false,
    highlight: function (code, lang) {
      var result = [
        '<pre class="raml-console-resource-pre raml-console-hljs hljs">',
        lang ? window.hljs.highlightAuto(code).value : code,
        '</pre>'
      ];

      return result.join('');
    }
  };
})(window);

(function () {
  'use strict';

  RAML.Directives.clickOutside = function ($document) {
    return {
      restrict: 'A',
      link: function ($scope, $element, $attrs) {
        function onClick (e) {
          if ($element[0] === e.target || $element.has(e.target).length) {
            return;
          }

          $scope.$apply($attrs.clickOutside);
        }

        $document.on('click', onClick);

        $scope.$on('$destroy', function () {
          $document.off('click', onClick);
        });
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('clickOutside', ['$document', RAML.Directives.clickOutside]);
})();

(function () {
  'use strict';

  RAML.Directives.closeButton = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/close-button.tpl.html',
      replace: true,
      controller: ['$scope', '$rootScope', function($scope, $rootScope) {
        $scope.close = function () {
          var $inactiveElements = jQuery('.raml-console-tab').add('.raml-console-resource').add('li');

          $rootScope.$broadcast('resetData');
          $inactiveElements.removeClass('raml-console-is-active');
          $scope.showPanel = false;
          $scope.traits = null;
          $scope.methodInfo = {};
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('closeButton', RAML.Directives.closeButton);
})();

(function () {
  'use strict';

  RAML.Directives.documentation = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/documentation.tpl.html',
      replace: true,
      controller: ['$scope', function($scope) {
        var defaultSchemaKey = Object.keys($scope.securitySchemes).sort()[0];
        var defaultSchema    = $scope.securitySchemes[defaultSchemaKey];

        $scope.markedOptions = RAML.Settings.marked;
        $scope.documentationSchemeSelected = defaultSchema;

        $scope.isSchemeSelected = function isSchemeSelected(scheme) {
          return scheme.id === $scope.documentationSchemeSelected.id;
        };

        $scope.selectDocumentationScheme = function selectDocumentationScheme(scheme) {
          $scope.documentationSchemeSelected = scheme;
        };

        $scope.schemaSettingsDocumentation = function schemaSettingsDocumentation(settings) {
          var doc = settings;

          if (typeof settings === 'object') {
            doc = settings.join(', ');
          }

          return doc;
        };

        $scope.unique = function (arr) {
          return arr.filter (function (v, i, a) { return a.indexOf (v) === i; });
        };

        $scope.currentStatusCode = '200';

        if ($scope.methodInfo.responseCodes && $scope.methodInfo.responseCodes.length > 0) {
          $scope.currentStatusCode = $scope.methodInfo.responseCodes[0];
        }

        $scope.$on('resetData', function() {
          if ($scope.methodInfo.responseCodes && $scope.methodInfo.responseCodes.length > 0) {
            $scope.currentStatusCode = $scope.methodInfo.responseCodes[0];
          }
        });

        function beautify(body, contentType) {
          if(contentType.indexOf('json') !== -1) {
            body = vkbeautify.json(body, 2);
          }

          if(contentType.indexOf('xml') !== -1) {
            body = vkbeautify.xml(body, 2);
          }

          return body;
        }

        $scope.getBeatifiedExample = function (value) {
          var result = value;

          try {
            result = beautify(value, $scope.currentBodySelected);
          }
          catch (e) { }

          return result;
        };

        $scope.getColorCode = function (code) {
          return code[0] + 'xx';
        };

        $scope.showCodeDetails = function (code) {
          $scope.currentStatusCode = code;
        };

        $scope.isActiveCode = function (code) {
          return $scope.currentStatusCode === code;
        };

        $scope.showRequestDocumentation = true;
        $scope.toggleRequestDocumentation = function () {
          $scope.showRequestDocumentation = !$scope.showRequestDocumentation;
        };

        $scope.showResponseDocumentation = true;
        $scope.toggleResponseDocumentation = function () {
          $scope.showResponseDocumentation = !$scope.showResponseDocumentation;
        };

        $scope.parameterDocumentation = function (parameter) {
          var result = '';

          if (parameter) {
            if (parameter.required) {
              result += 'required, ';
            }

            if (parameter['enum']) {
              var enumValues = $scope.unique(parameter['enum']);

              if (enumValues.length > 1) {
                result += 'one of ';
              }

              result += '(' + enumValues.filter(function (value) { return value !== ''; }).join(', ') + ')';

            } else {
              result += parameter.type || '';
            }

            if (parameter.pattern) {
              result += ' matching ' + parameter.pattern;
            }

            if (parameter.minLength && parameter.maxLength) {
              result += ', ' + parameter.minLength + '-' + parameter.maxLength + ' characters';
            } else if (parameter.minLength && !parameter.maxLength) {
              result += ', at least ' + parameter.minLength + ' characters';
            } else if (parameter.maxLength && !parameter.minLength) {
              result += ', at most ' + parameter.maxLength + ' characters';
            }


            if (parameter.minimum && parameter.maximum) {
              result += ' between ' + parameter.minimum + '-' + parameter.maximum;
            } else if (parameter.minimum && !parameter.maximum) {
              result += ' ≥ ' + parameter.minimum;
            } else if (parameter.maximum && !parameter.minimum) {
              result += ' ≤ ' + parameter.maximum;
            }

            if (parameter.repeat) {
              result += ', repeatable';
            }

            if (parameter['default'] !== undefined) {
              result += ', default: ' + parameter['default'];
            }
          }

          return result;
        };

        $scope.toggleTab = function ($event) {
          var $this        = jQuery($event.currentTarget);
          var $eachTab     = $this.parent().children('.raml-console-toggle-tab');
          var $panel       = $this.closest('.raml-console-resource-panel');
          var $eachContent = $panel.find('.raml-console-resource-panel-content');

          if (!$this.hasClass('raml-console-is-active')) {
            $eachTab.toggleClass('raml-console-is-active');
            $eachContent.toggleClass('raml-console-is-active');
          }
        };

        $scope.changeType = function ($event, type, code) {
          var $this        = jQuery($event.currentTarget);
          var $panel       = $this.closest('.raml-console-resource-body-heading');
          var $eachContent = $panel.find('span');

          $eachContent.removeClass('raml-console-is-active');
          $this.addClass('raml-console-is-active');

          $scope.responseInfo[code].currentType = type;
        };

        $scope.changeResourceBodyType = function ($event, type) {
          var $this        = jQuery($event.currentTarget);
          var $panel       = $this.closest('.raml-console-request-body-heading');
          var $eachContent = $panel.find('span');

          $eachContent.removeClass('raml-console-is-active');
          $this.addClass('raml-console-is-active');

          $scope.currentBodySelected = type;
        };

        $scope.getBodyId = function (bodyType) {
          return jQuery.trim(bodyType.toString().replace(/\W/g, ' ')).replace(/\s+/g, '_');
        };

        $scope.bodySelected = function (value) {
          return value === $scope.currentBodySelected;
        };

        $scope.$watch('currentBodySelected', function (value) {
          var $container = jQuery('.raml-console-request-body-heading');
          var $elements  = $container.find('span');

          $elements.removeClass('raml-console-is-active');
          $container.find('.raml-console-body-' + $scope.getBodyId(value)).addClass('raml-console-is-active');
        });
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('documentation', RAML.Directives.documentation);
})();

(function () {
  'use strict';
  angular.module('RAML.Directives').directive('dynamicName', ['$parse', function($parse) {
    return {
      restrict: 'A',
      controller: ['$scope', '$element', '$attrs', function($scope, $element, $attrs){
        var name = $parse($attrs.dynamicName)($scope);

        delete($attrs.dynamicName);
        $element.removeAttr('data-dynamic-name');
        $element.removeAttr('dynamic-name');
        $attrs.$set('name', name);
      }]
    };
  }]);
})();

(function () {
  'use strict';

  RAML.Directives.examples = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/examples.tpl.html',
      scope: {
        exampleContainer: '=',
        getBeatifiedExampleRef: '&'
      },
      controller: ['$scope', function($scope) {
        $scope.getBeatifiedExample = $scope.getBeatifiedExampleRef();
        $scope.examples = transformExample($scope.exampleContainer);
        $scope.currentExample = 0;

        $scope.isXML = $scope.exampleContainer.name === 'application/xml';

        $scope.changeExample = function(example) {
          $scope.currentExample = example;
        };

        $scope.$watch('exampleContainer', function (value) {
          $scope.examples = transformExample(value);
        });
      }]
    };
  };

  function transformExample(exampleContainer) {
    if (exampleContainer.example) {
      return [{
        name: 'Example',
        content: (typeof exampleContainer.example === 'object') ?
            JSON.stringify(exampleContainer.example) : exampleContainer.example
      }];
    } else if (exampleContainer.examples) {
      if (Array.isArray(exampleContainer.examples)) {
        return exampleContainer.examples.map(function (example, index) {
          return {
            name: example.name || 'Example ' + index,
            content: JSON.stringify(example.value, null, 2)
          };
        });
      } else {
        return Object.keys(exampleContainer.examples).sort().map(function (key) {
          return {
            name: key,
            content: exampleContainer.examples[key].value
          };
        });
      }
    }
  }

  angular.module('RAML.Directives')
    .directive('examples', RAML.Directives.examples);
})();

(function () {
  'use strict';

  RAML.Directives.markdown = function() {
    return {
      restrict: 'A',
      scope: {
        markdown: '='
      },
      controller: ['$scope', '$sanitize', '$window', '$element', function($scope, $sanitize, $window, $element) {
        $scope.$watch('markdown', function (markdown) {
          var allowUnsafeMarkdown = $scope.$parent.allowUnsafeMarkdown;
          var html = $window.marked(markdown || '', RAML.Settings.marked);

          if (!allowUnsafeMarkdown) {
            html = $sanitize(html);
          }

          $element.html(html);
        });
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('markdown', RAML.Directives.markdown);
})();

(function () {
  'use strict';

  RAML.Directives.methodList = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/method-list.tpl.html',
      replace: true,
      controller: ['$scope', '$timeout', '$rootScope', function($scope, $timeout, $rootScope) {
        function loadExamples () {
          $scope.context.uriParameters.reset($scope.resource.uriParametersForDocumentation);
          $scope.context.queryParameters.reset($scope.methodInfo.queryParameters);
          $scope.context.headers.reset($scope.methodInfo.headers.plain);

          function beautify(body, contentType) {
            if(contentType.indexOf('json') !== -1) {
              body = vkbeautify.json(body, 2);
            }

            if(contentType.indexOf('xml') !== -1) {
              body = vkbeautify.xml(body, 2);
            }

            return body;
          }

          $scope.getBeatifiedExample = function (value) {
            var result = value;

            try {
              result = beautify(value, $scope.currentBodySelected);
            }
            catch (e) { }

            return result;
          };

          if ($scope.context.bodyContent) {
            var definitions = $scope.context.bodyContent.definitions;

            Object.keys(definitions).map(function (key) {
              if (typeof definitions[key].reset !== 'undefined') {
                definitions[key].reset($scope.methodInfo.body[key].formParameters);
              } else {
                definitions[key].fillWithExample();
                if (definitions[key].value) {
                  definitions[key].value = $scope.getBeatifiedExample(definitions[key].value);
                }
              }
            });
          }
        }

        function getResponseInfo() {
          var responseInfo = {};
          var responses    = $scope.methodInfo.responses;

          if (responses) {
            Object.keys(responses).map(function (key) {
              if(responses[key] && typeof responses[key].body !== 'undefined' && responses[key].body) {
                responseInfo[key] = {};

                Object.keys(responses[key].body).sort().reverse().map(function (type) {
                  responseInfo[key][type] = responses[key].body[type];
                  responseInfo[key].currentType = type;
                });
              }
            });
          }

          return responseInfo;
        }

        function toUIModel (collection) {
          if(collection) {
            Object.keys(collection).forEach(function (key) {
              collection[key][0].id = key;
              if (collection[key][0].properties) {
                toUIModel(collection[key][0].properties);
              }
            });
          }
        }

        function beautifyCustomSecuritySchemeName (name) {
          return (name.charAt(0).toUpperCase() + name.slice(1)).replace(/_/g, ' ');
        }

        $scope.readTraits = function (traits) {
          var list = [];
          var traitList = traits || [];

          traitList = traitList.concat($scope.resource.traits);

          traitList.map(function (trait) {
            if (trait) {
              if (typeof trait === 'object') {
              trait = Object.keys(trait).join(', ');
              }

              if (list.indexOf(trait) === -1) {
                list.push(trait);
              }
            }
          });

          return list.join(', ');
        };

        $scope.generateId = function (path) {
          return jQuery.trim(path.toString().replace(/\W/g, ' ')).replace(/\s+/g, '_');
        };

        var $inactiveElements = jQuery('.raml-console-tab').add('.raml-console-resource')
                                                           .add('li')
                                                           .add('.raml-console-tab');

        $scope.$on('openMethod', function(event, $currentScope) {
          if ($scope.$id !== $currentScope.$id) {
            $inactiveElements.removeClass('raml-console-is-active');
            $scope.showPanel = false;
          }
        });

        $scope.showResource = function ($event, $index) {
          var $this             = jQuery($event.currentTarget);
          var $resource         = $this.closest('.raml-console-resource');
          var methodInfo        = $scope.resource.methods[$index];
          var $inactiveElements = jQuery('.raml-console-tab').add('.raml-console-resource')
                                                             .add('li')
                                                             .add('.raml-console-tab');

          $scope.methodInfo               = methodInfo;
          $scope.responseInfo             = getResponseInfo();
          $scope.context                  = new RAML.Services.TryIt.Context($scope.raml.baseUriParameters, $scope.resource, $scope.methodInfo);
          $scope.requestUrl               = '';
          $scope.response                 = {};
          $scope.requestOptions           = {};
          $scope.requestEnd               = false;
          $scope.showRequestMetadata      = false;
          $scope.showMoreEnable           = true;
          $scope.showSpinner              = false;
          $scope.securitySchemes          = $scope.methodInfo.securitySchemes();
          $scope.traits                   = $scope.readTraits($scope.methodInfo.is);
          $scope.context.customParameters = { headers: [], queryParameters: [] };
          $scope.currentBodySelected      = methodInfo.body ? Object.keys(methodInfo.body)[0] : 'application/json';

          toUIModel($scope.methodInfo.queryParameters);
          toUIModel($scope.methodInfo.headers.plain);
          toUIModel($scope.resource.uriParametersForDocumentation);

          Object.keys($scope.securitySchemes).map(function (key) {
            var type = $scope.securitySchemes[key].type;

            $scope.securitySchemes[key].name = type;
            $scope.securitySchemes[key].id = type + '|' + key;

            if (type === 'x-custom') {
              $scope.securitySchemes[key].name = beautifyCustomSecuritySchemeName(key);
              $scope.securitySchemes[key].id = type + '|' + key;
            }
          });

          $rootScope.$broadcast('resetData');

          /*jshint camelcase: false */
          // Digest Authentication is not supported
          delete $scope.securitySchemes.digest_auth;
          /*jshint camelcase: true */

          loadExamples();

          // Hack for codemirror
          setTimeout(function () {
            var editors = jQuery('.raml-console-sidebar-content-wrapper #sidebar-body .raml-console-codemirror-body-editor .CodeMirror');

            editors.map(function (index) {
              var bodyEditor = editors[index].CodeMirror;

              if (bodyEditor && $scope.context.bodyContent) {
                bodyEditor.setOption('mode', $scope.context.bodyContent.selected);
                bodyEditor.refresh();
              }
            });
          }, 10);

          if (!$resource.hasClass('raml-console-is-active')) {
            var hash = $scope.generateId($scope.resource.pathSegments);

            $rootScope.$broadcast('openMethod', $scope);
            jQuery($this).addClass('raml-console-is-active');
            $scope.showPanel = true;

            $timeout(function () {
              jQuery('html, body').animate({
                scrollTop: jQuery('#'+hash).offset().top + 'px'
              }, 'fast');
            }, 10);

          } else if (jQuery($this).hasClass('raml-console-is-active')) {
            $scope.showPanel = false;
            $inactiveElements.removeClass('raml-console-is-active');
            $scope.traits = null;
            $scope.methodInfo = {};
          } else {
            jQuery($this).addClass('raml-console-is-active');
            jQuery($this).siblings('.raml-console-tab').removeClass('raml-console-is-active');
          }
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('methodList', RAML.Directives.methodList);
})();

(function () {
  'use strict';

  RAML.Directives.namedParameters = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/named-parameters.tpl.html',
      replace: true,
      scope: {
        src: '=',
        context: '=',
        type: '@',
        title: '@'
      },
      controller: ['$scope', '$attrs', function ($scope, $attrs) {
        $scope.markedOptions = RAML.Settings.marked;

        if ($attrs.hasOwnProperty('enableCustomParameters')) {
          $scope.enableCustomParameters = true;
        }

        if ($attrs.hasOwnProperty('showBaseUrl')) {
          $scope.showBaseUrl = true;
        }

        $scope.segments = [];

        var baseUri = $scope.$parent.raml.baseUri;

        if (typeof baseUri !== 'undefined' && baseUri.templated) {
          var tokens = baseUri.tokens;

          for (var i = 0; i < tokens.length; i++) {
            $scope.segments.push({
              name: tokens[i],
              templated: typeof baseUri.parameters[tokens[i]] !== 'undefined' ? true : false
            });
          }
        }

        $scope.$parent.resource.pathSegments.map(function (element) {
          var tokens = element.tokens;

          for (var i = 0; i < tokens.length; i++) {
            $scope.segments.push({
              name: tokens[i],
              templated: element.templated && typeof element.parameters[tokens[i]] !== 'undefined' ? true : false
            });
          }
        });

        $scope.addCustomParameter = function () {
          $scope.context.customParameters[$scope.type].push({});
        };

        $scope.removeCutomParam = function (param) {
          $scope.context.customParameters[$scope.type] = $scope.context.customParameters[$scope.type].filter(function (el) {
            return el.name !== param.name;
          });
        };

        $scope.isValueProvided = function isValueProvided(value) {
          if (!value) {
            return false;
          }

          if (typeof value !== 'object') {
            return true;
          }

          return Object.keys(value).filter(function (k) {
            return $scope.isValueProvided(value[k]);
          }).length > 0;
        };

        $scope.cleanupValue = RAML.Inspector.Properties.cleanupPropertyValue;
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('namedParameters', RAML.Directives.namedParameters);
})();

(function () {
  'use strict';

  var PATTERN_PATTERN = /^\/[^\/]*\/$/;

  RAML.Directives.properties = function(RecursionHelper) {
    return {
      restrict: 'E',
      templateUrl: 'directives/properties.tpl.html',
      replace: true,
      scope: {
        list: '=',
        collapsible: '=',
        isNestedProperty: '=',
        hideTypeLinks: '=',
        hidePropertyDetails: '=',
        showExamples: '='
      },
      controller: ['$scope', '$rootScope', function ($scope, $rootScope) {
        if (!Array.isArray($scope.list)) {
          $scope.listArray = Object.keys($scope.list).map(function (key) {
            return $scope.list[key];
          });

          $scope.listArray = RAML.Inspector.Properties.normalizeNamedParameters($scope.list);
        } else {
          $scope.listArray = $scope.list;
        }

        var getArrayTypes = function(arrayType) {
          if (arrayType.items.type || Array.isArray(arrayType.items.type)) {
            return arrayType.items.type;
          }

          return [arrayType.items];
        };

        $scope.getType = function (type) {
          var newType = $scope.mergeType(type);
          newType.type = RAML.Inspector.Types.ensureArray(newType.type);

          if (newType.type[0] === 'array') {
            newType.type = getArrayTypes(newType).map(function (aType) {
              return aType + '[]';
            });
            newType.properties = newType.items.properties;
          }

          return newType;
        };

        $scope.mergeType = function (type) {
          var newType = angular.copy(type);

          if (!$scope.isNestedProperty && $rootScope.types) {
            return RAML.Inspector.Types.mergeType(newType, $rootScope.types);
          }
          return newType;
        };

        $scope.isNativeType = RAML.Inspector.Types.isNativeType;

        $scope.isPattern = function (propertyName) {
          return propertyName.match(PATTERN_PATTERN);
        };

        $scope.isSchema = RAML.Inspector.Types.isSchema;

        $scope.isCollapsible = function isCollapsible(property) {
          return $scope.collapsible && !!(property.description || property.properties || $scope.isSchema(property.type[0]));
        };

        $scope.parameterDocumentation = function (parameter) {
          var result = [];

          if (parameter) {
            if (parameter.required) {
              result.push('required');
            }

            if (parameter.discriminator) {
              result.push('discriminator');
            }

            if (parameter['enum']) {
              var enumValues = $scope.unique(parameter['enum']);
              var enumDescription = '';

              if (enumValues.length > 1) {
                enumDescription += 'one of ';
              }

              enumDescription += '(' + enumValues.filter(function (value) { return value !== ''; }).join(', ') + ')';

              result.push(enumDescription);
            }

            if (parameter.pattern) {
              result.push(' matching ' + parameter.pattern);
            }

            if (parameter.minLength && parameter.maxLength) {
              result.push(parameter.minLength + '-' + parameter.maxLength + ' characters');
            } else if (parameter.minLength && !parameter.maxLength) {
              result.push('at least ' + parameter.minLength + ' characters');
            } else if (parameter.maxLength && !parameter.minLength) {
              result.push('at most ' + parameter.maxLength + ' characters');
            }

            if (parameter.minimum && parameter.maximum) {
              result.push('between ' + parameter.minimum + '-' + parameter.maximum);
            } else if (parameter.minimum && !parameter.maximum) {
              result.push('≥ ' + parameter.minimum);
            } else if (parameter.maximum && !parameter.minimum) {
              result.push('≤ ' + parameter.maximum);
            }

            if (parameter.repeat) {
              result.push('repeatable');
            }

            if (parameter['default'] !== undefined) {
              result.push('default: ' + parameter['default']);
            }
          }

          return result.join(', ');
        };

        $scope.typeDocumentation = function (type) {
          var result = [];

          if (type.minItems) {
            result.push('minItems: ' + type.minItems);
          }

          if (type.maxItems) {
            result.push('maxItems: ' + type.maxItems);
          }

          if (type['enum']) {
            var enumValues = type['enum'];
            var enumDescription = '';

            if (enumValues.length > 1) {
              enumDescription += 'one of ';
            }

            enumDescription += '(' + enumValues.filter(function (value) { return value !== ''; }).join(', ') + ')';

            result.push(enumDescription);
          }

          if (type.pattern) {
            result.push('pattern: ' + type.pattern);
          }

          if (type.minLength) {
            result.push('minLength: ' + type.minLength);
          }

          if (type.maxLength) {
            result.push('maxLength: ' + type.maxLength);
          }

          if (type.minimum) {
            result.push('minimum: ' + type.minimum);
          }

          if (type.format) {
            result.push('format: ' + type.format);
          }

          if (type.multipleOf) {
            result.push('multipleOf: ' + type.multipleOf);
          }

          if (type.fileTypes) {
            result.push('fileTypes: ' + type.fileTypes.join(', '));
          }

          return result.join(', ');
        };

        $scope.unique = function (arr) {
          return arr.filter (function (v, i, a) { return a.indexOf (v) === i; });
        };
      }],
      compile: function (element) {
        return RecursionHelper.compile(element);
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('properties', ['RecursionHelper', RAML.Directives.properties]);
})();

(function () {
  'use strict';

  RAML.Directives.ramlBody = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/raml-body.tpl.html',
      scope: {
        body: '=',
        getBeatifiedExampleRef: '&'
      },
      controller: ['$scope', '$rootScope', function($scope, $rootScope) {
        $scope.getBeatifiedExample = $scope.getBeatifiedExampleRef();

        $scope.$watch('body', function () {
          $scope.identifyBodyType();
        });

        $scope.getTopSchema = function (name) {
          return RAML.Inspector.Types.findSchema(name, $rootScope.schemas);
        };

        $scope.getTopType = function (name) {
          name = Array.isArray(name) ? name[0] : name;
          return RAML.Inspector.Types.findType(name, $rootScope.types);
        };

        $scope.identifyBodyType = function () {
          var node = angular.copy($scope.body);
          node.type = node.type || node.schema;
          $scope.isType = false;
          $scope.isSchema = false;

          if (node.type) {
            node.type = Array.isArray(node.type) ? node.type : [node.type];
            node.type.forEach(function (aType) {
              if (typeof aType !== 'object') {
                var isNative = RAML.Inspector.Types.isNativeType(aType);

                if (isNative) {
                  $scope.isType = true;
                } else {
                  var declaredType = RAML.Inspector.Types.findType(aType, $rootScope.types);
                  var declaredSchema = RAML.Inspector.Types.findSchema(aType, $rootScope.schemas);

                  if (declaredType) {
                    var typeParts = declaredType.type[0].split('|');
                    var firstType = RAML.Inspector.Types.cleanupTypeName(typeParts[0]);

                    if (RAML.Inspector.Types.isNativeType(firstType) ||
                        RAML.Inspector.Types.findType(firstType, $rootScope.types)) {
                      $scope.isType = true;
                    } else {
                      $scope.isSchema = true;
                      $scope.definition = declaredType.type[0];
                    }
                  } else {
                    $scope.isSchema = true;
                    if (declaredSchema) {
                      if (declaredSchema.type) {
                        $scope.definition = declaredSchema.type[0];
                      } else {
                        $scope.definition = declaredSchema;
                      }
                    } else {
                      if (aType.indexOf('|') !== -1) {
                        $scope.isSchema = false;
                        $scope.isType = true;
                      } else {
                        $scope.definition = aType;
                      }
                    }
                  }
                }
              } else {
                $scope.isSchema = true;
                $scope.definition = JSON.stringify(aType, null, 2);
              }
            });
          }
        };

        $scope.showSchema = function ($event) {
          var $this   = jQuery($event.currentTarget);
          var $panel  = $this.closest('.raml-console-schema-container');
          var $schema = $panel.find('.raml-console-resource-pre-toggle');

          $this.toggleClass('raml-console-is-active');

          if (!$schema.hasClass('raml-console-is-active')) {
            $this.text('Hide Schema');
            $schema
              .addClass('raml-console-is-active')
              .velocity('slideDown');
          } else {
            $this.text('Show Schema');
            $schema
              .removeClass('raml-console-is-active')
              .velocity('slideUp');
          }
        };

        $scope.identifyBodyType();
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('ramlBody', RAML.Directives.ramlBody);
})();

(function () {
  'use strict';

  var generator = window.ramlClientGenerator;

  function downloadClient (language, ast) {
    var zip    = new window.JSZip();
    var output = generator[language](ast);
    var title  = window.slug(output.context.title);

    Object.keys(output.files).forEach(function (key) {
      zip.file(key, output.files[key]);
    });

    var content  = zip.generate({ type: 'blob' });
    var filename = title + '-' + language + '.zip';

    // Download as a zip file with an appropriate language name.
    window.saveAs(content, filename);
  }

  RAML.Directives.ramlClientGenerator = function () {
    return {
      restrict: 'E',
      templateUrl: 'directives/raml-client-generator.tpl.html',
      controller: ['$scope', function ($scope) {
        $scope.downloadJavaScriptClient = function () {
          return downloadClient('javascript', $scope.rawRaml);
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('ramlClientGenerator', RAML.Directives.ramlClientGenerator);
})();

(function () {
  'use strict';

  angular.module('RAML.Directives')
    .directive('ramlConsoleLoader', function ramlConsoleLoader() {
      return {
        restrict:    'E',
        templateUrl: 'directives/raml-console-loader.tpl.html',
        replace:     true,
        controller:  'RamlConsoleLoaderController',
        scope:       {
          src:     '@',
          options: '='
        }
      };
    })
    .controller('RamlConsoleLoaderController', function RamlConsoleLoaderController(
      $scope,
      $window,
      ramlParser
    ) {
      $scope.vm = {
        error:   void(0),
        loaded:  false,
        options: $scope.options,
        raml:    void(0),
        src:     $scope.src
      };

      // ---

      (function activate() {
        loadFromUrl($scope.vm.src);
      })();

      // ---

      function loadFromUrl(url) {
        $scope.vm.raml   = void(0);
        $scope.vm.loaded = false;
        $scope.vm.error  = void(0);

        return ramlParser.loadPath($window.resolveUrl(url), null, $scope.options)
          .then(function (raml) {
            $scope.vm.raml = raml;
          })
          .catch(function (error) {
            $scope.vm.error = angular.extend(error, {
              /*jshint camelcase: false */
              buffer: (error.context_mark || error.problem_mark).buffer
              /*jshint camelcase: true */
            });
          })
          .finally(function () {
            $scope.vm.loaded = true;
          })
        ;
      }
    })
  ;
})();

(function () {
  'use strict';

  angular.module('RAML.Directives')
    .directive('ramlConsoleSpinner', function ramlConsoleSpinner() {
      return {
        restrict:    'E',
        templateUrl: 'directives/raml-console-spinner.tpl.html',
        replace:     true
      };
    })
  ;
})();

(function () {
  'use strict';

  angular.module('RAML.Directives')
    .directive('ramlConsole', function ramlConsole() {
      return {
        restrict:    'E',
        templateUrl: 'directives/raml-console.tpl.html',
        replace:     true,
        controller:  'RamlConsoleController',
        scope:       {
          raml:    '=',
          options: '='
        }
      };
    })
    .controller('RamlConsoleController', 
      ['$attrs', '$scope', '$rootScope', '$timeout', '$window', function RamlConsoleController(
      $attrs, $scope, $rootScope, $timeout, $window
    ) {
      $scope.allowUnsafeMarkdown        = $attrs.hasOwnProperty('allowUnsafeMarkdown');
      $scope.collapseAll                = collapseAll;
      $scope.credentials                = {};
      $scope.disableRamlClientGenerator = $attrs.hasOwnProperty('disableRamlClientGenerator');
      $scope.disableThemeSwitcher       = $attrs.hasOwnProperty('disableThemeSwitcher');
      $scope.disableTitle               = $attrs.hasOwnProperty('disableTitle');
      $scope.disableTryIt               = $attrs.hasOwnProperty('disableTryIt');
      $scope.documentationCollapsed     = $attrs.hasOwnProperty('documentationCollapsed');
      $scope.proxy                      = $window.RAML.Settings.proxy;
      $scope.readResourceTraits         = readResourceTraits;
      $scope.resourcesCollapsed         = $attrs.hasOwnProperty('resourcesCollapsed');
      $scope.singleView                 = $attrs.hasOwnProperty('singleView');
      $scope.hasResourcesWithChilds     = hasResourcesWithChilds;
      $scope.toggle                     = toggle;
      $scope.updateProxyConfig          = updateProxyConfig;

      // ---

      (function activate() {
        $scope.options && [
          'allowUnsafeMarkdown',
          'disableRamlClientGenerator',
          'disableThemeSwitcher',
          'disableTitle',
          'disableTryIt',
          'documentationCollapsed',
          'resourcesCollapsed',
          'singleView'
        ].forEach(function (property) {
          if ($scope.options[property]) {
            $scope[property] = true;
          }
        });

        $scope.$watch('raml', function (raml) {
          if (!raml) {
            return;
          }
          delete $scope.types;
          delete $rootScope.types;

          $timeout(function () {
            var securitySchemes = raml.securitySchemes ? angular.copy(raml.securitySchemes) : [];
            var librarySecuritySchemes = getSecuritySchemes();

            if (securitySchemes || librarySecuritySchemes) {
              raml.securitySchemes = securitySchemes.concat(librarySecuritySchemes);
            }

            inspectRaml(raml);

            var types = raml.types ? angular.copy(raml.types) : [];
            var libraryTypes = getLibraryTypes();

            if (types.length || libraryTypes.length) {
              $scope.types = types.concat(libraryTypes);

              $rootScope.types = $scope.types.map(function (type) {
                var theType = type[Object.keys(type)[0]];
                theType.properties = RAML.Inspector.Properties.normalizeNamedParameters(theType.properties);
                return type;
              });
            }

            var schemas = raml.schemas ? angular.copy(raml.schemas) : [];
            var librarySchemas = getLibrarySchemas();

            if (schemas || librarySchemas) {
              $rootScope.schemas = schemas.concat(librarySchemas);
            }
          });

          function getLibraryTypes() {
            var result = [] ;
            if (raml.uses) {
              Object.keys(raml.uses).forEach(function (usesKey) {
                var usesTypes = raml.uses[usesKey].types;
                if (usesTypes) {
                  usesTypes.forEach(function (aType) {
                    Object.keys(aType).forEach(function (typeKey) {
                      var tempType = {};
                      convertType(aType[typeKey], usesKey);

                      tempType[usesKey + '.' + typeKey] = aType[typeKey];
                      tempType[usesKey + '.' + typeKey].displayName = usesKey + '.' + typeKey;
                      result.push(tempType);
                    });
                  });
                }
              });
            }

            return result;
          }

          function convertType(typeNode, usesKey) {
            typeNode.type = typeNode.type.map(function (typeName) {
              if (!RAML.Inspector.Types.isSchema(typeName)) {
                var typeInfo = RAML.Inspector.Types.getTypeInfo(typeName);

                typeInfo.parts = typeInfo.parts.map(function (theType) {
                  if (!RAML.Inspector.Types.isNativeType(theType)) {
                    return usesKey + '.' + RAML.Inspector.Types.cleanupTypeName(theType);
                  }
                  return theType;
                });

                return RAML.Inspector.Types.getTypeFromTypeInfo(typeInfo);
              }
              return typeName;
            });

            if (typeNode.properties) {
              Object.keys(typeNode.properties).forEach(function (propertiesKey) {
                convertType(typeNode.properties[propertiesKey], usesKey);
              });
            }
          }

          function getLibrarySchemas() {
            var result = [];
            if (raml.uses) {
              Object.keys(raml.uses).forEach(function (usesKey) {
                var usesSchemas = raml.uses[usesKey].schemas;
                if (usesSchemas) {
                  usesSchemas.forEach(function (aSchema) {
                    Object.keys(aSchema).forEach(function (schemaKey) {
                      var tempSchema = {};
                      tempSchema[usesKey + '.' + schemaKey] = aSchema[schemaKey];
                      result.push(tempSchema);
                    });
                  });
                }
              });
            }

            return result;
          }

          function getSecuritySchemes() {
            var result = [];
            if (raml.uses) {
              Object.keys(raml.uses).forEach(function (usesKey) {
                var usesSecuritySchemes = raml.uses[usesKey].securitySchemes;
                if (usesSecuritySchemes) {
                  usesSecuritySchemes.forEach(function (aScheme) {
                    Object.keys(aScheme).forEach(function (schemaKey) {
                      var tempSchema = {};
                      tempSchema[usesKey + '.' + schemaKey] = aScheme[schemaKey];
                      result.push(tempSchema);
                    });
                  });
                }
              });
            }

            return result;
          }
        });
      })();

      // ---

      function collapseAll($event, collection, flagKey) {
        var $this = jQuery($event.currentTarget);

        if ($this.hasClass('raml-console-resources-expanded')) {
          $scope[flagKey] = true;
        } else {
          if (flagKey === 'resourcesCollapsed') {
            jQuery('.raml-console-resource-description').removeClass('ng-hide');
          }
          $scope[flagKey] = false;
        }

        jQuery('.raml-console-resources-' + flagKey).find('ol.raml-console-resource-list').toggleClass('raml-console-is-collapsed');

        toggleCollapsed($scope[flagKey], collection);
      }

      function readResourceTraits(traits) {
        var list = [];

        if (traits) {
          traits.map(function (trait) {
            if (trait) {
              if (typeof trait === 'object') {
                list.push(Object.keys(trait).join(', '));
              } else {
                list.push(trait);
              }
            }
          });
        }

        return list.join(', ');
      }

      function toggle($event, index, collection, flagKey) {
        var $this    = jQuery($event.currentTarget);
        var $section = $this
          .closest('.raml-console-resource-list-item')
          .find('.raml-console-resource-list');

        collection[index] = !collection[index];

        $scope[flagKey] = checkItemStatus(false, collection) ? false : $scope[flagKey];
        $scope[flagKey] = checkItemStatus(true, collection) ? true : $scope[flagKey];

        $section.toggleClass('raml-console-is-collapsed');
      }

      function updateProxyConfig(status) {
        $window.RAML.Settings.disableProxy = status;
      }

      // ---

      function toggleCollapsed(status, collection) {
        for (var i = 0; i < collection.length; i++) {
          collection[i] = collection[i] !== null ? status : collection[i];
        }
      }

      function checkItemStatus(status, collection) {
        return collection.filter(function (el) { return el === status || el === null; }).length === collection.length;
      }

      function hasResourcesWithChilds() {
        return $scope.inspectedRaml && $scope.inspectedRaml.resourceGroups.filter(function (el) {
          return el.length > 1;
        }).length > 0;
      }

      function inspectRaml(raml) {
        $scope.inspectedRaml = RAML.Inspector.create(raml);
        $scope.resourceList  = [];
        $scope.documentList  = [];

        for (var i = 0; i < $scope.inspectedRaml.resourceGroups.length; i++) {
          var resources = $scope.inspectedRaml.resourceGroups[i];
          var status    = resources.length > 1 ? false : null;
          $scope.resourceList.push($scope.resourcesCollapsed ? true : status);
        }

        if ($scope.inspectedRaml.documentation) {
          for (var j = 0; j < $scope.inspectedRaml.documentation.length; j++) {
            $scope.documentList.push($scope.documentationCollapsed ? true : false);
          }
        }
      }
    }])
  ;
})();

(function () {
  'use strict';

  RAML.Directives.ramlField = function(RecursionHelper) {
    return {
      restrict: 'E',
      templateUrl: 'directives/raml-field.tpl.html',
      replace: true,
      scope: {
        context: '=',
        type: '=',
        model: '=',
        param: '='
      },
      controller: ['$scope', function($scope) {
        var bodyContent = $scope.context.bodyContent;
        var context     = $scope.context[$scope.type];

        if (bodyContent) {
          context = context || bodyContent.definitions[bodyContent.selected];
        }

        Object.keys(context.plain).map(function (key) {
          var definition = context.plain[key].definitions[0];

          if (typeof definition['enum'] !== 'undefined') {
            context.values[definition.id][0] = definition['enum'][0];
          }
        });

        $scope.isArray = function (param) {
          return param.type[0].indexOf('[]') !== -1;
        };

        $scope.addArrayElement = function (model) {
          model.push([undefined]);
        };

        $scope.removeArrayElement = function (model, index) {
          model.splice(index, 1);
        };

        $scope.canOverride = function (definition) {
          return definition.type === 'boolean' ||  typeof definition['enum'] !== 'undefined';
        };

        $scope.overrideField = function ($event, definition) {
          var $this      = jQuery($event.currentTarget);
          var $container = $this.closest('p');
          var $el        = $container.find('#' + definition.id);
          var $checkbox  = $container.find('#checkbox_' + definition.id);
          var $select    = $container.find('#select_' + definition.id);

          $el.toggleClass('raml-console-sidebar-override-show');
          $checkbox.toggleClass('raml-console-sidebar-override-hide');
          $select.toggleClass('raml-console-sidebar-override-hide');

          $this.text('Override');

          if($el.hasClass('raml-console-sidebar-override-show')) {
            definition.overwritten = true;
            $this.text('Cancel override');
          } else {
            definition.overwritten = false;
            $scope.context[$scope.type].values[definition.id][0] = definition['enum'][0];
          }
        };

        $scope.onChange = function () {
          $scope.context.forceRequest = false;
        };

        $scope.isDefault = function (definition) {
          return typeof definition['enum'] === 'undefined' && definition.type !== 'boolean';
        };

        $scope.isEnum = function (definition) {
          return typeof definition['enum'] !== 'undefined';
        };

        $scope.isBoolean = function (definition) {
          return definition.type === 'boolean';
        };

        $scope.hasExampleValue = function (value) {
          return $scope.isEnum(value) ? false : value.type === 'boolean' ? false : typeof value['enum'] !== 'undefined' ? false : (typeof value.example !== 'undefined' || typeof value.examples !== 'undefined') ? true : false;
        };

        $scope.reset = function (param) {
          var type = $scope.type || 'bodyContent';
          var info = {};

          info[param.id] = [param];

          $scope.context[type].reset(info, param.id);
        };

        $scope.unique = function (arr) {
          return arr.filter (function (v, i, a) { return a.indexOf (v) === i; });
        };

        $scope.toString = function toString(value) {
          return Array.isArray(value) ? value.join(', ') : value;
        };
      }],
      compile: function (element) {
        return RecursionHelper.compile(element);
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('ramlField', ['RecursionHelper', RAML.Directives.ramlField]);
})();

(function () {
  'use strict';

  angular.module('RAML.Directives')
    .directive('ramlInitializer', function ramlInitializer() {
      return {
        restrict:    'E',
        templateUrl: 'directives/raml-initializer.tpl.html',
        replace:     true,
        controller:  'RamlInitializerController'
      };
    })
    .controller('RamlInitializerController', ['$scope', '$window', 'ramlParser', function RamlInitializerController(
      $scope, $window, ramlParser
    ) {
      $scope.vm = {
        codeMirror: {
          gutters:      ['CodeMirror-lint-markers'],
          lineNumbers:  true,
          lineWrapping: true,
          lint:         null,
          mode:         'yaml',
          tabSize:      2,
          theme:        'raml-console'
        },

        error:           null,
        isLoadedFromUrl: false,
        isLoading:       false,
        loadFromString:  loadFromString,
        loadFromUrl:     loadFromUrl,
        raml:            null
      };

      // ---

      (function activate() {
        if (document.location.search.indexOf('?raml=') !== -1) {
          loadFromUrl(document.location.search.replace('?raml=', ''));
        }
      })();

      // ---

      function loadFromUrl(url) {
        $scope.vm.ramlUrl = url;
        return loadFromPromise(ramlParser.loadPath($window.resolveUrl(url)), {isLoadingFromUrl: true});
      }

      function loadFromString(string) {
        $scope.vm.ramlString = string;
        return loadFromPromise(ramlParser.load(string));
      }

      // ---

      /**
       * @param {Promise} promise
       * @param {Boolean} options.isLoadingFromUrl
       */
      function loadFromPromise(promise, options) {
        options                   = options || {};
        $scope.vm.error           = null;
        $scope.vm.raml            = null;
        $scope.vm.isLoading       = true;
        $scope.vm.isLoadedFromUrl = false;
        $scope.vm.codeMirror.lint = null;

        return promise
          .then(function (raml) {
            $scope.vm.raml = raml;
          })
          .catch(function (error) {
            $scope.vm.error           = error;
            $scope.vm.codeMirror.lint = lintFromError(error);
          })
          .finally(function () {
            $scope.vm.isLoading       = false;
            $scope.vm.isLoadedFromUrl = options.isLoadingFromUrl;
          })
        ;
      }

      function lintFromError(error) {
        return function getAnnotations() {
          return (error.parserErrors || []).map(function (error) {
            return {
              message:  error.message,
              severity: error.isWarning ? 'warning' : 'error',
              from:     CodeMirror.Pos(error.line),
              to:       CodeMirror.Pos(error.line)
            };
          });
        };
      }
    }])
  ;
})();

(function () {
  'use strict';

  RAML.Directives.resourcePanel = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/resource-panel.tpl.html',
      replace: true
    };
  };

  angular.module('RAML.Directives')
    .directive('resourcePanel', RAML.Directives.resourcePanel);
})();

(function () {
  'use strict';

  RAML.Directives.resourceType = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/resource-type.tpl.html',
      replace: true,
      controller: ['$scope', function ($scope) {
        var resourceType = $scope.resource.resourceType;

        if (typeof resourceType === 'object') {
          $scope.resource.resourceType = Object.keys(resourceType).join();
        }
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('resourceType', RAML.Directives.resourceType);
})();

(function () {
  'use strict';

  RAML.Directives.rootDocumentation = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/root-documentation.tpl.html',
      replace: true,
      controller: ['$scope', '$timeout', function($scope, $timeout) {
        $scope.markedOptions = RAML.Settings.marked;
        $scope.selectedSection = 'all';

        $scope.hasDocumentationWithIndex = function () {
          var regex = /(^#|^##)+\s(.*)$/gim;

          return $scope.raml.documentation.filter(function (el) {
            return regex.test(el.content);
          }).length > 0;
        };

        $scope.generateDocId = function (path) {
          return jQuery.trim(path.toString().replace(/\W/g, ' ')).replace(/\s+/g, '_').toLowerCase();
        };

        $scope.toggleSection = function ($event, key, section) {
          var $el = jQuery($event.currentTarget).closest('.raml-console-documentation');
          $scope.selectedDocumentSection = key;
          $scope.documentationEnabled = $el.hasClass('raml-console-documentation-active') ? false : true;

          jQuery('.raml-console-resource-list-item').removeClass('raml-console-documentation-active');

          $el[!$scope.documentationEnabled ? 'removeClass' : 'addClass']('raml-console-documentation-active');

          $timeout(function () {
            jQuery('html, body').animate({
              scrollTop: jQuery('#'+$scope.generateDocId(section)).offset().top + 'px'
            }, 'fast');
          }, 10);
        };

        $scope.closeDocumentation = function ($event) {
          var $container = jQuery($event.currentTarget).closest('.raml-console-documentation');
          $container.toggleClass('raml-console-documentation-active');
          $scope.documentationEnabled = false;
          jQuery('.raml-console-resource-list-item').removeClass('raml-console-documentation-active');
        };

        $scope.sectionChange = function (value) {
          $scope.selectedDocumentSection = value;
        };

        $scope.getDocumentationContent = function (content, selected) {
          var lines  = content.split('\n');
          var index  = lines.indexOf(selected);
          var result = [];
          var regex  = /(^#|^##)+\s(.*)$/gim;

          result.push(lines[index]);

          for (var i = index+1; i < lines.length; i++) {
            var line = lines[i];

            if (regex.test(line)) {
              break;
            }

            result.push(line);
          }

          return !selected || selected === 'all' ? content : result.join('\n');
        };

        $scope.filterHeaders = function (c) {
          return c.filter(function (el) {
            return el.heading <= 2;
          });
        };

        $scope.getMarkdownHeaders = function (content) {
          var headers = content.match(/^#+\s(.*)$/gim);
          var result  = [];
          var regex   = new RegExp(/(^#|^##)+\s(.*)$/gim);

          if (headers) {
            var key = headers[0];

            headers.map(function(el) {
              if(el.match(regex) !== null) {
                key = el;
              }

              result.push({
                value: key,
                heading: el.match(/#/g).length,
                label: el.replace(/#/ig, '').trim()
              });
            });
          }

          return result;
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('rootDocumentation', RAML.Directives.rootDocumentation);
})();

(function () {
  'use strict';

  RAML.Directives.rootTypes = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/root-types.tpl.html',
      replace: true,
      scope: {
        types: '='
      },
      controller: ['$scope', function ($scope) {
        $scope.convertTypes = function () {
          var types = {};
          $scope.types.forEach(function (type) {
            types[Object.keys(type)[0]] = type[Object.keys(type)[0]];
          });
          $scope.theTypes = RAML.Inspector.Properties.normalizeNamedParameters(types);
        };

        $scope.$watch('types', function () {
          $scope.convertTypes();
        });
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('rootTypes', RAML.Directives.rootTypes);
})();

(function () {
  'use strict';

  RAML.Directives.sidebar = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/sidebar.tpl.html',
      replace: true,
      controller: ['$scope', '$timeout', function ($scope, $timeout) {
        var defaultSchemaKey = Object.keys($scope.securitySchemes).sort()[0];
        var defaultSchema    = $scope.securitySchemes[defaultSchemaKey];
        var defaultAccept    = 'application/json';

        $scope.markedOptions     = RAML.Settings.marked;
        $scope.currentSchemeType = defaultSchema.type;
        $scope.currentScheme     = defaultSchema.id;
        $scope.responseDetails   = false;

        function readCustomSchemeInfo (name) {
          if (!$scope.methodInfo.headers.plain) {
            $scope.methodInfo.headers.plain = {};
          }

          if (!$scope.methodInfo.queryParameters) {
            $scope.methodInfo.queryParameters = {};
          }

          updateContextData('headers', name, $scope.methodInfo.headers.plain, $scope.context.headers);
          updateContextData('queryParameters', name, $scope.methodInfo.queryParameters, $scope.context.queryParameters);
        }

        if (defaultSchema.type === 'x-custom') {
          readCustomSchemeInfo(defaultSchema.id.split('|')[1]);
        }

        function completeAnimation (element) {
          jQuery(element).removeAttr('style');
        }

        function parseHeaders(headers) {
          var parsed = {}, key, val, i;

          if (!headers) {
            return parsed;
          }

          headers.split('\n').forEach(function(line) {
            i   = line.indexOf(':');
            key = line.substr(0, i).trim().toLowerCase();
            val = line.substr(i + 1).trim();

            if (key) {
              if (parsed[key]) {
                parsed[key] += ', ' + val;
              } else {
                parsed[key] = val;
              }
            }
          });

          return parsed;
        }

        function apply () {
          $scope.$apply.apply($scope, arguments);
        }

        function beautify(body, contentType) {
          if(contentType.indexOf('json')) {
            body = vkbeautify.json(body, 2);
          }

          if(contentType.indexOf('xml')) {
            body = vkbeautify.xml(body, 2);
          }

          return body;
        }

        function handleResponse(jqXhr, err) {
          $scope.response.status = jqXhr ? jqXhr.status : err ? (err.status ? err.status : err.message) : 0;

          if (jqXhr) {
            $scope.response.headers = parseHeaders(jqXhr.getAllResponseHeaders());

            if ($scope.response.headers['content-type']) {
              $scope.response.contentType = $scope.response.headers['content-type'].split(';')[0];
            }

            try {
              $scope.response.body = beautify(jqXhr.responseText, $scope.response.contentType);
            }
            catch (e) {
              $scope.response.body = jqXhr.responseText;
            }
          }

          $scope.requestEnd      = true;
          $scope.showMoreEnable  = true;
          $scope.showSpinner     = false;
          $scope.responseDetails = true;

          // If the response fails because of CORS, responseText is null
          var editorHeight = 50;

          if (jqXhr && jqXhr.responseText) {
            var lines = $scope.response.body.split('\n').length;
            editorHeight = lines > 100 ? 2000 : 25*lines;
          }

          $scope.editorStyle = {
            height: editorHeight + 'px'
          };

          apply();

          var hash = 'request_' + $scope.generateId($scope.resource.pathSegments);

          $timeout(function () {
            if (jqXhr) {
              var $editors = jQuery('.raml-console-sidebar-content-wrapper .CodeMirror').toArray();

              $editors.forEach(function (editor) {
                var cm = editor.CodeMirror;
                cm.setOption('mode', $scope.response.contentType);
                cm.refresh();
              });
            }

            jQuery('html, body').animate({
              scrollTop: jQuery('#'+hash).offset().top + 'px'
            }, 'fast');
          }, 10);
        }

        function resolveSegementContexts(pathSegments, uriParameters) {
          var segmentContexts = [];

          pathSegments.forEach(function (element) {
            if (element.templated) {
              var segment = {};
              Object.keys(element.parameters).map(function (key) {
                segment[key] = uriParameters[key];
              });
              segmentContexts.push(segment);
            } else {
              segmentContexts.push({});
            }
          });

          return segmentContexts;
        }

        function validateForm(form) {
          var keys = Object.keys(form.form).filter(function (key) { return key.indexOf('$') === -1;});

          keys.forEach(function (fieldName) {
            form.form[fieldName].$setDirty();
          });

          return form.form.$valid;
        }

        function getParameters (context, type) {
          var params           = {};
          var customParameters = context.customParameters[type];

          if (!RAML.Utils.isEmpty(context[type].data())) {
            params = angular.copy(context[type].data());
          }

          Object.keys(params).forEach(function (key) {
            if (Array.isArray(params[key][0])) {
              var input = angular.copy(params[key][0]);

              input.forEach(function (each, index) {
                params[key][index] = each[0];
              });
            }

            params[key].forEach(function (param, index) {
              if (typeof param === 'object') {
                params[key][index] = JSON.stringify(
                  RAML.Inspector.Properties.cleanupPropertyValue(params[key][index]));
              }
            });

            // Remove empty array property
            if (params[key][0] === '[null]') {
              delete params[key];
            }
          });

          if (customParameters.length > 0) {
            for(var i = 0; i < customParameters.length; i++) {
              var key = customParameters[i].name;

              params[key] = [];
              params[key].push(customParameters[i].value);
            }
          }

          return params;
        }

        function clearCustomFields (types) {
          types.map(function (type) {
            var custom = $scope.context.customParameters[type];

            for (var i = 0; i < custom.length; i++) {
              custom[i].value = '';
            }
          });
        }

        $scope.$watch('methodInfo', function () {
          $scope.protocols       = $scope.methodInfo.protocols || $scope.raml.protocols;
          $scope.currentProtocol = $scope.protocols && $scope.protocols.length ? $scope.protocols[0] : null;
        });

        $scope.$on('resetData', function() {
          var defaultSchemaKey = Object.keys($scope.securitySchemes).sort()[0];
          var defaultSchema    = $scope.securitySchemes[defaultSchemaKey];

          $scope.currentSchemeType           = defaultSchema.type;
          $scope.currentScheme               = defaultSchema.id;
          $scope.protocols                   = $scope.methodInfo.protocols || $scope.raml.protocols;
          $scope.currentProtocol             = $scope.protocols && $scope.protocols.length ? $scope.protocols[0] : null;
          $scope.documentationSchemeSelected = defaultSchema;
          $scope.responseDetails             = null;

          cleanSchemeMetadata($scope.methodInfo.headers.plain, $scope.context.headers);
          cleanSchemeMetadata($scope.methodInfo.queryParameters, $scope.context.queryParameters);
        });

        $scope.cancelRequest = function () {
          $scope.showSpinner = false;
        };

        $scope.prefillBody = function (current) {
          var definition   = $scope.context.bodyContent.definitions[current];
          definition.fillWithExample();

          if (definition.value) {
            definition.value = $scope.getBeatifiedExample(definition.value);
          }
        };

        $scope.clearFields = function () {
          $scope.context.uriParameters.clear($scope.resource.uriParametersForDocumentation);
          $scope.context.queryParameters.clear($scope.methodInfo.queryParameters);
          $scope.context.headers.clear($scope.methodInfo.headers.plain);
          if ($scope.context.bodyContent) {
            $scope.context.bodyContent.definitions[$scope.context.bodyContent.selected].value = '';
          }
          $scope.context.forceRequest = false;

          if ($scope.credentials) {
            Object.keys($scope.credentials).map(function (key) {
              $scope.credentials[key] = '';
            });
          }

          clearCustomFields(['headers', 'queryParameters']);

          if ($scope.context.bodyContent) {
            var current    = $scope.context.bodyContent.selected;
            var definition = $scope.context.bodyContent.definitions[current];

            if (typeof definition.clear !== 'undefined') {
              definition.clear($scope.methodInfo.body[current].formParameters);
            } else {
              definition.value = '';
            }
          }
        };

        $scope.resetFormParameter = function (param) {
          var current    = $scope.context.bodyContent.selected;
          var definition = $scope.context.bodyContent.definitions[current];

          definition.reset($scope.methodInfo.body[current].formParameters, param.id);
        };

        $scope.resetFields = function () {
          $scope.context.uriParameters.reset($scope.resource.uriParametersForDocumentation);
          $scope.context.queryParameters.reset($scope.methodInfo.queryParameters);
          $scope.context.headers.reset($scope.methodInfo.headers.plain);

          if ($scope.context.bodyContent) {
            var current    = $scope.context.bodyContent.selected;
            var definition = $scope.context.bodyContent.definitions[current];

            if (typeof definition.reset !== 'undefined') {
              definition.reset($scope.methodInfo.body[current].formParameters);
            } else {
              definition.value = definition.contentType.example;
            }
          }

          $scope.context.forceRequest = false;
        };

        $scope.requestBodySelectionChange = function (bodyType) {
          $scope.currentBodySelected = bodyType;
        };

        $scope.toggleBodyType = function ($event, bodyType) {
          var $this  = jQuery($event.currentTarget);
          var $panel = $this.closest('.raml-console-sidebar-toggle-type').find('button');

          $panel.removeClass('raml-console-is-active');
          $this.addClass('raml-console-is-active');

          $scope.context.bodyContent.selected = bodyType;
        };

        $scope.getHeaderValue = function (header) {
          if (typeof header === 'string') {
            return header;
          }

          return header[0];
        };

        $scope.hasExampleValue = function (value) {
          return typeof value !== 'undefined' ? true : false;
        };

        $scope.context.forceRequest = false;

        function cleanSchemeMetadata(collection, context) {
          if (collection) {
            Object.keys(collection).map(function (key) {
              if (collection[key][0].isFromSecurityScheme) {
                delete collection[key];
              }

              if (context.plain[key].definitions[0].isFromSecurityScheme) {
                delete context.plain[key];
              }
            });
          }
        }

        function updateContextData (type, scheme, collection, context) {
          var details         = jQuery.extend({}, $scope.securitySchemes[scheme].describedBy || {});
          var securityHeaders = details[type] || {};

          if (securityHeaders) {
            Object.keys(securityHeaders).map(function (key) {
              if (!securityHeaders[key]) {
                securityHeaders[key] = {
                  id: key,
                  type: 'string'
                };
              }

              securityHeaders[key].id                   = key;
              securityHeaders[key].displayName          = key;
              securityHeaders[key].isFromSecurityScheme = true;
              collection[key] = [securityHeaders[key]];

              context.plain[key] = {
                definitions: [securityHeaders[key]],
                selected: securityHeaders[key].type
              };
              context.values[key] = [undefined];
            });
          }
        }

        $scope.protocolChanged = function protocolChanged(protocol) {
          $scope.currentProtocol = protocol;
        };

        $scope.securitySchemeChanged = function securitySchemeChanged(scheme) {
          var info            = scheme.split('|');
          var type            = info[0];
          var name            = info[1];

          $scope.currentSchemeType = type;
          $scope.context.forceRequest = false;

          cleanSchemeMetadata($scope.methodInfo.headers.plain, $scope.context.headers);
          cleanSchemeMetadata($scope.methodInfo.queryParameters, $scope.context.queryParameters);

          $scope.documentationSchemeSelected = $scope.securitySchemes[name];

          if (type === 'x-custom') {
            readCustomSchemeInfo(name);
          }
        };

        $scope.setFormScope = function (form) {
          $scope.form = form;
        };

        $scope.tryIt = function ($event) {
          $scope.requestOptions  = null;
          $scope.responseDetails = false;
          $scope.response        = {};

          if (!$scope.context.forceRequest) {
            jQuery($event.currentTarget).closest('form').find('.ng-invalid').first().focus();
          }

          if($scope.context.forceRequest || validateForm($scope.form)) {
            var url;
            var context         = $scope.context;
            var segmentContexts = resolveSegementContexts($scope.resource.pathSegments, $scope.context.uriParameters.data());

            $scope.showSpinner = true;
            $scope.queryStringHasError = false;
            $scope.toggleRequestMetadata($event, true);

            try {
              var pathBuilder = context.pathBuilder;
              var client      = RAML.Client.create($scope.raml, function(client) {
                if ($scope.raml.baseUriParameters) {
                  Object.keys($scope.raml.baseUriParameters).map(function (key) {
                    var uriParameters = $scope.context.uriParameters.data();
                    pathBuilder.baseUriContext[key] = uriParameters[key][0];
                    delete uriParameters[key];
                  });
                }
                client.baseUriParameters(pathBuilder.baseUriContext);
              });

              client.baseUri = client.baseUri.replace(/(https)|(http)/, $scope.currentProtocol.toLocaleLowerCase());
              url = client.baseUri + pathBuilder(segmentContexts);
            } catch (e) {
              console.error(e);
              $scope.response = {};
              return;
            }
            var request = RAML.Client.Request.create(url, $scope.methodInfo.method);

            $scope.parameters = getParameters(context, 'queryParameters');

            if (context.queryString) {
              var parameters;
              try {
                parameters = JSON.parse(context.queryString);
              } catch (e) {
                $scope.queryStringHasError = true;
                $scope.response = {};

                $scope.showSpinner = false;
                return;
              }
              Object.keys(parameters).forEach(function (key) {
                if (!$scope.parameters[key]) {
                  $scope.parameters[key] = [];
                }
                var value = parameters[key];
                if (typeof value === 'object') {
                  value = JSON.stringify(value);
                }
                $scope.parameters[key].push(value);
              });
            }

            request.queryParams($scope.parameters);
            request.header('Accept', $scope.raml.mediaType || defaultAccept);
            request.headers(getParameters(context, 'headers'));

            if (context.bodyContent) {
              request.header('Content-Type', context.bodyContent.selected);
              request.data(context.bodyContent.data());
            }

            var authStrategy;

            try {
              var securitySchemes = $scope.methodInfo.securitySchemes();
              var scheme;

              Object.keys(securitySchemes).map(function(key) {
                if (securitySchemes[key].type === $scope.currentSchemeType) {
                  scheme = securitySchemes && securitySchemes[key];
                  return;
                }
              });

              //// TODO: Make a uniform interface
              if (scheme && scheme.type === 'OAuth 2.0') {
                authStrategy = new RAML.Client.AuthStrategies.Oauth2(scheme, $scope.credentials);
                authStrategy.authenticate(request.toOptions(), function (jqXhr, err) {
                  $scope.requestOptions = request.toOptions();
                  handleResponse(jqXhr, err);
                });
                return;
              }
              authStrategy = RAML.Client.AuthStrategies.forScheme(scheme, $scope.credentials);
              authStrategy.authenticate().then(function(token) {
                token.sign(request);
                $scope.requestOptions = request.toOptions();
                jQuery.ajax(request.toOptions()).then(
                  function(data, textStatus, jqXhr) { handleResponse(jqXhr); },
                  function(jqXhr) { handleResponse(jqXhr); }
                );
              });

              $scope.requestOptions = request.toOptions();
            } catch (e) {
              console.error(e);
              $scope.customStrategyError = true;
              $scope.response = {};

              $scope.showSpinner = false;
            }
          } else {
            $scope.context.forceRequest = true;
          }
        };

        $scope.documentationEnabled = true;

        $scope.closeSidebar = function ($event) {
          var $this         = jQuery($event.currentTarget);
          var $panel        = $this.closest('.raml-console-resource-panel');
          var $sidebar      = $panel.find('.raml-console-sidebar');
          var sidebarWidth  = 0;

          if (jQuery(window).width() > 960) {
            sidebarWidth = 430;
          }

          $scope.documentationEnabled = true;
          $sidebar.velocity(
            { width: 0 },
            {
              duration: 200,
              complete: function (element) {
                jQuery(element).removeAttr('style');
                $sidebar.removeClass('raml-console-is-fullscreen');
              }
            }
          );
          $sidebar.toggleClass('raml-console-is-collapsed');
          $sidebar.removeClass('raml-console-is-responsive');
          $panel.toggleClass('raml-console-has-sidebar-collapsed');
        };

        $scope.toggleSidebar = function ($event) {
          var $this        = jQuery($event.currentTarget);
          var $panel       = $this.closest('.raml-console-resource-panel');
          var $sidebar     = $panel.find('.raml-console-sidebar');
          var sidebarWidth = 0;

          if (jQuery(window).width() > 960) {
            sidebarWidth = 430;
          }

          if ($sidebar.hasClass('raml-console-is-fullscreen')) {
            $scope.documentationEnabled = true;
            $sidebar.velocity(
              { width: $scope.singleView ? 0 : sidebarWidth },
              {
                duration: 200,
                complete: function (element) {
                  jQuery(element).removeAttr('style');
                  $sidebar.removeClass('raml-console-is-fullscreen');
                }
              }
            );
            $sidebar.removeClass('raml-console-is-responsive');
            $panel.removeClass('raml-console-has-sidebar-fullscreen');
          } else {
            $sidebar.velocity(
              { width: '100%' },
              {
                duration: 200,
                complete: function (element) {
                  jQuery(element).removeAttr('style');
                  $scope.documentationEnabled = false;
                  apply();
                }
              }
            );

            $sidebar.addClass('raml-console-is-fullscreen');
            $sidebar.addClass('raml-console-is-responsive');
            $panel.addClass('raml-console-has-sidebar-fullscreen');
          }

          if ($scope.singleView) {
            $sidebar.toggleClass('raml-console-is-collapsed');
            $panel.toggleClass('raml-console-has-sidebar-collapsed');
          }
        };

        $scope.collapseSidebar = function ($event) {
          var $this         = jQuery($event.currentTarget);
          var $panel        = $this.closest('.raml-console-resource-panel');
          var $panelContent = $panel.find('.raml-console-resource-panel-primary');
          var $sidebar      = $panel.find('.raml-console-sidebar');
          var animation     = 430;
          var speed         = 200;

          if ((!$sidebar.hasClass('raml-console-is-fullscreen') && !$sidebar.hasClass('raml-console-is-collapsed')) || $sidebar.hasClass('raml-console-is-responsive')) {
            animation = 0;
          }

          if ($scope.singleView) {
            $panel.toggleClass('raml-console-has-sidebar-fullscreen');
            speed = 0;
          }

          $sidebar.velocity(
            { width: animation },
            {
              duration: speed,
              complete: function (element) {
                jQuery(element).removeAttr('style');
                if ($scope.singleView) {
                  $scope.documentationEnabled = false;
                }
                apply();
              }
            }
          );

          $panelContent.velocity(
            { 'padding-right': animation },
            {
              duration: speed,
              complete: completeAnimation
            }
          );

          $sidebar.toggleClass('raml-console-is-collapsed');
          $sidebar.removeClass('raml-console-is-responsive');
          $panel.toggleClass('raml-console-has-sidebar-collapsed');

          if ($sidebar.hasClass('raml-console-is-fullscreen') || $scope.singleView) {
            $sidebar.toggleClass('raml-console-is-fullscreen');
          }
        };

        $scope.toggleRequestMetadata = function (enabled) {
          if ($scope.showRequestMetadata && !enabled) {
            $scope.showRequestMetadata = false;
          } else {
            $scope.showRequestMetadata = true;
          }
        };

        $scope.showResponseMetadata = true;

        $scope.toggleResponseMetadata = function () {
          $scope.showResponseMetadata = !$scope.showResponseMetadata;
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('sidebar', RAML.Directives.sidebar);
})();

(function () {
  'use strict';

  RAML.Directives.spinner = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/spinner.tpl.html',
      replace: true,
      link: function($scope, $element) {
        $scope.$on('loading-started', function() {
          $element.css({ 'display': ''});
        });

        $scope.$on('loading-complete', function() {
          $element.css({ 'display': 'none' });
        });
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('spinner', RAML.Directives.spinner);
})();

(function () {
  'use strict';

  RAML.Directives.theme = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/theme-switcher.tpl.html',
      replace: true,
      link: function($scope, $element) {
        $element.on('click', function() {
          // var $link = jQuery('head link.theme');
          var $theme = jQuery('head').find('#raml-console-theme-dark');

          // $link.attr('href', 'styles/light-theme.css');
          // $element.removeClass('raml-console-theme-toggle-dark');

          if ($theme.length === 0) {
            jQuery.ajax({
              url: 'styles/api-console-dark-theme.css'
            }).done(function (data) {
              jQuery('head').append('<style id="raml-console-theme-dark">' + data + '</style>');
              jQuery('head').find('#raml-console-theme-light').remove();
            });
          } else {
            jQuery.ajax({
              url: 'styles/api-console-light-theme.css'
            }).done(function (data) {
              jQuery('head').append('<style id="raml-console-theme-light">' + data + '</style>');
              jQuery('head').find('#raml-console-theme-dark').remove();
            });
          }
        });
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('themeSwitcher', RAML.Directives.theme);
})();

(function () {
  'use strict';

  RAML.Directives.typeProperties = function() {
    return {
      restrict: 'E',
      templateUrl: 'directives/type-properties.tpl.html',
      replace: true,
      scope: {
        type: '='
      },
      controller: ['$scope', function ($scope) {
        $scope.properties = {
          body: [$scope.type]
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('typeProperties', RAML.Directives.typeProperties);
})();

(function () {
  'use strict';

  RAML.Directives.type = function() {
    var TOGGLE_POPOVER = 'popover:toggle';
    return {
      restrict: 'E',
      templateUrl: 'directives/type.tpl.html',
      scope: {
        typeName: '=',
        hideTypeLinks: '=',
        items: '='
      },
      controller: ['$scope', '$rootScope', '$timeout', function ($scope, $rootScope, $timeout) {
        $scope.typeInfo = RAML.Inspector.Types.getTypeInfo($scope.typeName, $scope.items);

        $scope.closePopover = function () {
          $scope.selectedType = null;
        };

        $rootScope.$on(TOGGLE_POPOVER, function () {
          $scope.closePopover();
        });

        $scope.showTypeLink = function (type) {
          return !$scope.hideTypeLinks && !RAML.Inspector.Types.isNativeType(type);
        };

        $scope.cleanupTypeName = RAML.Inspector.Types.cleanupTypeName;

        $scope.getSupertTypes = function (type) {
          return RAML.Inspector.Types.findType(type.type[0], $rootScope.types).type.map(function (aTypeName) {
            return aTypeName;
          });
        };

        $scope.selectType = function ($event, type) {
          jQuery(document).one('click', function () {
            $timeout(function () {
              $rootScope.$broadcast(TOGGLE_POPOVER);
            });
          });

          $rootScope.$broadcast(TOGGLE_POPOVER);

          $timeout(function () {
            $scope.selectedType = RAML.Inspector.Types.mergeType({
                displayName: type,
                type: [type]
              },
              $rootScope.types);
          });

          $event.stopPropagation();
        };
      }]
    };
  };

  angular.module('RAML.Directives')
    .directive('type', RAML.Directives.type);
})();

(function () {
  'use strict';

  RAML.Directives.validate = function($parse) {
    return {
      require: 'ngModel',
      link: function ($scope, $element, $attrs, $ctrl) {
        function clear ($ctrl, rules) {
          Object.keys(rules).map(function (key) {
            $ctrl.$setValidity(key, true);
          });
        }

        function validate(value) {
          var sanitizer = (new RAMLSanitize())(sanitationRules);
          var validator = (new RAMLValidate())(validationRules);
          var current   = {};
          var errors;

          value = typeof value !== 'undefined' && value !== null && value.length === 0 ? undefined : value;
          current[validation.id] = value;

          errors = validator(sanitizer(current)).errors;

          if (errors.length > 0) {
            control.$setValidity(errors[0].rule, errors[0].valid);
            // Note: We want to allow invalid errors for testing purposes
            return value;
          } else {
            clear(control, validationRules[validation.id]);
            return value;
          }
        }

        var validation      = $parse($attrs.validate)($scope);
        var sanitationRules = {};
        var validationRules = {};
        var control         = $ctrl;

        sanitationRules[validation.id] = {
          type: validation.type || null,
          repeat: validation.repeat || null
        };

        sanitationRules[validation.id] = RAML.Utils.filterEmpty(sanitationRules[validation.id]);

        validationRules[validation.id] = {
          type: validation.type || null,
          minLength: validation.minLength || null,
          maxLength: validation.maxLength || null,
          required: validation.required || null,
          'enum': validation['enum'] || null,
          pattern: validation.pattern || null,
          minimum: validation.minimum || null,
          maximum: validation.maximum || null,
          repeat: validation.repeat || null
        };

        validationRules[validation.id] = RAML.Utils.filterEmpty(validationRules[validation.id]);

        $ctrl.$formatters.unshift(function(value) {
          return validate(value);
        });

        $ctrl.$parsers.unshift(function(value) {
          return validate(value);
        });
      }
    };
  };

  angular.module('RAML.Directives')
    .directive('validate', ['$parse', RAML.Directives.validate]);
})();

(function () {
  'use strict';

  angular.module('raml', [])
    .factory('ramlParser', ['$http', '$q', '$window', function ramlParser(
      $http,
      $q,
      $window
    ) {
      var jsonOptions=  {
        serializeMetadata: false,
        dumpSchemaContents: true
      };

      return {
        load:     toQ(load),
        loadPath: toQ(loadPath)
      };

      // ---

      function load(text, contentAsyncFn, options) {
        var virtualPath = '/' + Date.now() + '.raml';
        return loadApi(virtualPath, function contentAsync(path) {
          return (path === virtualPath) ? $q.when(text) : (contentAsyncFn ? contentAsyncFn(path) : $q.reject(new Error('ramlParser: load: contentAsync: ' + path + ': no such path')));
        }, options);
      }

      function loadPath(path, contentAsyncFn, options) {
        return loadApi(path, function contentAsync(path) {
          return contentAsyncFn ? contentAsyncFn(path) : $q.reject(new Error('ramlParser: loadPath: contentAsync: ' + path + ': no such path'));
        }, options);
      }

      // ---

      function toQ(fn) {
        return function toQWrapper() {
          return $q.when(fn.apply(this, arguments));
        };
      }

      /**
       * @param  {String}   path
       * @param  {Function} contentAsyncFn
       * @param  {Object}   options
       * @param  {Boolean}  options.bypassProxy
       */
      function loadApi(path, contentAsyncFn, options) {
        options = options || {};
        return RAML.Parser.loadApi(path, {
          attributeDefaults: true,
          rejectOnErrors:    true,
          fsResolver:        {
            contentAsync: contentAsyncFn,
            content:      content
          },
          httpResolver:      {
            getResourceAsync: function getResourceAsync(url) {
              var settings = ($window.RAML || {}).Settings || {};
              var proxy    = (options.bypassProxy ? {} : settings).proxy || '';
              var req      = {
                method: 'GET',
                url: proxy + url,
                headers: {
                  'Accept': 'application/raml+yaml'
                },
                transformResponse: null
              };
              return $http(req)
                .then(function (res) {
                  return {content: res.data};
                })
              ;
            }
          }
        })
          .then(function (api) {
            var apiJSON;

            api = api.expand ? api.expand() : api;
            apiJSON = api.toJSON(jsonOptions);
            if (api.uses && api.uses()) {
              apiJSON.uses = {};
              api.uses().forEach(function (usesItem) {
                var libraryAST = usesItem.ast();
                libraryAST = libraryAST.expand ? libraryAST.expand() : libraryAST;
                apiJSON.uses[usesItem.key()] = libraryAST.toJSON(jsonOptions);
              });
            }

            return apiJSON;
          })
        ;

        // ---

        function content(path) {
          throw new Error('ramlParser: loadPath: loadApi: content: ' + path + ': no such path');
        }
      }
    }])
  ;
})();

(function () {
  'use strict';

  RAML.Security.basicAuth = function() {
    return {
      restrict: 'E',
      templateUrl: 'security/basic_auth.tpl.html',
      replace: true,
      scope: {
        credentials: '='
      },
      controller: ['$scope', function ($scope) {
        $scope.onChange = function () {
          $scope.$parent.context.forceRequest = false;
        };
      }]
    };
  };

  angular.module('RAML.Security')
    .directive('basicAuth', RAML.Security.basicAuth);
})();

(function () {
  'use strict';

  RAML.Security.oauth1 = function() {
    return {
      restrict: 'E',
      templateUrl: 'security/oauth1.tpl.html',
      replace: true,
      scope: {
        credentials: '='
      },
      controller: ['$scope', function ($scope) {
        $scope.onChange = function () {
          $scope.$parent.context.forceRequest = false;
        };
      }]
    };
  };

  angular.module('RAML.Security')
    .directive('oauth1', RAML.Security.oauth1);
})();

(function () {
  'use strict';

  RAML.Security.oauth2 = function() {
    return {
      restrict: 'E',
      templateUrl: 'security/oauth2.tpl.html',
      replace: true,
      controller: ['$scope', function ($scope) {
        $scope.onChange = function () {
          $scope.$parent.context.forceRequest = false;
        };

        $scope.ownerOptionsEnabled = function () {
          return $scope.credentials.grant === 'owner';
        };

        $scope.isImplicitEnabled = function () {
          return $scope.credentials.grant === 'token';
        };

        $scope.grants = [
          {
            label: 'Implicit',
            value: 'token'
          },
          {
            label: 'Implicit',
            value: 'implicit'
          },
          {
            label: 'Authorization Code',
            value: 'code'
          },
          {
            label: 'Authorization Code',
            value: 'authorization_code'
          },
          {
            label: 'Resource Owner Password Credentials',
            value: 'owner'
          },
          {
            label: 'Resource Owner Password Credentials',
            value: 'password'
          },
          {
            label: 'Client Credentials',
            value: 'credentials'
          },
          {
            label: 'Client Credentials',
            value: 'client_credentials'
          }
        ];

        /* jshint camelcase: false */
        $scope.getOAuth2Settings = function () {
          var result;
          for (var securitySchemesKey in $scope.$parent.securitySchemes) {
            if ($scope.$parent.securitySchemes.hasOwnProperty(securitySchemesKey)) {
              if ($scope.$parent.securitySchemes[securitySchemesKey].type === 'OAuth 2.0') {
                result = $scope.$parent.securitySchemes[securitySchemesKey].settings;
                break;
              }
            }
          }
          return result;
        };

        var oauth2Settings = $scope.getOAuth2Settings();
        var authorizationGrants = oauth2Settings.authorizationGrants;

        $scope.scopes = oauth2Settings.scopes;
        $scope.credentials.scopes = {};

        if (authorizationGrants) {
          $scope.grants = $scope.grants.filter(function (el) {
            return authorizationGrants.indexOf(el.value) > -1;
          });
        }
        /* jshint camelcase: true */

        $scope.credentials.grant = $scope.grants[0].value;
      }]
    };
  };


  angular.module('RAML.Security')
    .directive('oauth2', RAML.Security.oauth2);
})();

(function () {
  'use strict';
  angular.module('RAML.Services').factory('RecursionHelper', ['$compile', function ($compile) {
    return {
      /**
      * Manually compiles the element, fixing the recursion loop.
      * @param element
      * @param [link] A post-link function, or an object with function(s) registered via pre and post properties.
      * @returns An object containing the linking functions.
      */
      compile: function (element, link) {
        // Normalize the link parameter
        if (angular.isFunction(link)) {
          link = { post: link };
        }

        // Break the recursion loop by removing the contents
        var contents = element.contents().remove();
        var compiledContents;
        return {
          pre: (link && link.pre) ? link.pre : null,
          /**
          * Compiles and re-adds the contents
          */
          post: function (scope, element) {
            // Compile the contents
            if (!compiledContents) {
              compiledContents = $compile(contents);
            }
            // Re-add the compiled contents to the element
            compiledContents(scope, function (clone) {
              element.append(clone);
            });

            // Call the post-linking function, if any
            if (link && link.post) {
              link.post.apply(null, arguments);
            }
          }
        };
      }
    };
  }]);
})();

'use strict';

(function() {
  var Client = function(configuration) {
    this.baseUri = configuration.getBaseUri();
  };

  function createConfiguration(parsed) {
    var config = {
      baseUriParameters: {}
    };

    return {
      baseUriParameters: function(baseUriParameters) {
        config.baseUriParameters = baseUriParameters || {};
      },

      getBaseUri: function() {
        var template = RAML.Client.createBaseUri(parsed);
        config.baseUriParameters.version = parsed.version;

        return template.render(config.baseUriParameters);
      }
    };
  }

  RAML.Client = {
    create: function(parsed, configure) {
      var configuration = createConfiguration(parsed);

      if (configure) {
        configure(configuration);
      }

      return new Client(configuration);
    },

    createBaseUri: function(rootRAML) {
      var baseUri = rootRAML.baseUri.toString().replace(/\/+$/, '');

      return new RAML.Client.ParameterizedString(baseUri, rootRAML.baseUriParameters, { parameterValues: {version: rootRAML.version}, encodeURIComponent: true });
    },

    createPathSegment: function(resourceRAML) {
      return new RAML.Client.ParameterizedString(resourceRAML.relativeUri, resourceRAML.uriParameters, { encodeURIComponent: true });
    }
  };
})();

(function() {
  'use strict';

  RAML.Client.AuthStrategies = {
    forScheme: function(scheme, credentials) {
      if (!scheme) {
        return RAML.Client.AuthStrategies.anonymous();
      }

      switch(scheme.type) {
      case 'Basic Authentication':
        return new RAML.Client.AuthStrategies.Basic(scheme, credentials);
      case 'OAuth 2.0':
        return new RAML.Client.AuthStrategies.Oauth2(scheme, credentials);
      case 'OAuth 1.0':
        return new RAML.Client.AuthStrategies.Oauth1(scheme, credentials);
      case 'x-custom':
        return RAML.Client.AuthStrategies.anonymous();
      case 'Anonymous':
        return RAML.Client.AuthStrategies.anonymous();
      default:
        throw new Error('Unknown authentication strategy: ' + scheme.type);
      }
    }
  };
})();

'use strict';

(function() {
  var NO_OP_TOKEN = {
    sign: function() {}
  };

  var Anonymous = function() {};

  Anonymous.prototype.authenticate = function() {
    return {
      then: function(success) { success(NO_OP_TOKEN); }
    };
  };

  var anonymous = new Anonymous();

  RAML.Client.AuthStrategies.Anonymous = Anonymous;
  RAML.Client.AuthStrategies.anonymous = function() {
    return anonymous;
  };
})();

'use strict';

(function() {
  var Basic = function(scheme, credentials) {
    this.token = new Basic.Token(credentials);
  };

  Basic.prototype.authenticate = function() {
    var token = this.token;

    return {
      then: function(success) { success(token); }
    };
  };

  Basic.Token = function(credentials) {
    var words = CryptoJS.enc.Utf8.parse((credentials.username || '') + ':' + (credentials.password || ''));
    this.encoded = CryptoJS.enc.Base64.stringify(words);
  };

  Basic.Token.prototype.sign = function(request) {
    request.header('Authorization', 'Basic ' + this.encoded);
  };

  RAML.Client.AuthStrategies.Basic = Basic;
})();

(function() {
  'use strict';

  var Oauth1 = function(scheme, credentials) {
    var signerFactory = RAML.Client.AuthStrategies.Oauth1.Signer.createFactory(scheme.settings, credentials);
    this.requestTemporaryCredentials = RAML.Client.AuthStrategies.Oauth1.requestTemporaryCredentials(scheme.settings, signerFactory);
    this.requestAuthorization = RAML.Client.AuthStrategies.Oauth1.requestAuthorization(scheme.settings);
    this.requestTokenCredentials = RAML.Client.AuthStrategies.Oauth1.requestTokenCredentials(scheme.settings, signerFactory);
  };

  Oauth1.parseUrlEncodedData = function(data) {
    var result = {};

    data.split('&').forEach(function(param) {
      var keyAndValue = param.split('=');
      result[keyAndValue[0]] = keyAndValue[1];
    });

    return result;
  };

  Oauth1.prototype.authenticate = function() {
    return this.requestTemporaryCredentials().then(this.requestAuthorization).then(this.requestTokenCredentials);
  };

  RAML.Client.AuthStrategies.Oauth1 = Oauth1;
})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  var WINDOW_NAME = 'raml-console-oauth1';

  RAML.Client.AuthStrategies.Oauth1.requestAuthorization = function(settings) {
    return function requestAuthorization(temporaryCredentials) {
      var authorizationUrl = settings.authorizationUri + '?oauth_token=' + temporaryCredentials.token,
      deferred = jQuery.Deferred();

      window.RAML.authorizationSuccess = function(authResult) {
        temporaryCredentials.verifier = authResult.verifier;
        deferred.resolve(temporaryCredentials);
      };
      window.open(authorizationUrl, WINDOW_NAME);
      return deferred.promise();
    };
  };
})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  RAML.Client.AuthStrategies.Oauth1.requestTemporaryCredentials = function(settings, signerFactory) {
    return function requestTemporaryCredentials() {
      var request = RAML.Client.Request.create(settings.requestTokenUri, 'post');

      signerFactory().sign(request);

      return jQuery.ajax(request.toOptions()).then(function(rawFormData) {
        var data = RAML.Client.AuthStrategies.Oauth1.parseUrlEncodedData(rawFormData);

        return {
          token: data.oauth_token,
          tokenSecret: data.oauth_token_secret
        };
      });
    };
  };

})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  RAML.Client.AuthStrategies.Oauth1.requestTokenCredentials = function(settings, signerFactory) {
    return function requestTokenCredentials(temporaryCredentials) {
      var request = RAML.Client.Request.create(settings.tokenCredentialsUri, 'post');

      signerFactory(temporaryCredentials).sign(request);

      return jQuery.ajax(request.toOptions()).then(function(rawFormData) {
        var credentials = RAML.Client.AuthStrategies.Oauth1.parseUrlEncodedData(rawFormData);

        return signerFactory({
          token: credentials.oauth_token,
          tokenSecret: credentials.oauth_token_secret
        });
      });
    };
  };
})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  var Signer = RAML.Client.AuthStrategies.Oauth1.Signer = {};

  Signer.createFactory = function(settings, consumerCredentials) {
    settings = settings || {};

    return function createSigner(tokenCredentials) {
      var type = settings.signatureMethod === 'PLAINTEXT' ? 'Plaintext' : 'Hmac';
      var mode = tokenCredentials === undefined ? 'Temporary' : 'Token';

      return new Signer[type][mode](consumerCredentials, tokenCredentials);
    };
  };

  function baseParameters(consumerCredentials) {
    return {
      oauth_consumer_key: consumerCredentials.consumerKey,
      oauth_version: '1.0'
    };
  }

  Signer.generateTemporaryCredentialParameters = function(consumerCredentials) {
    var result = baseParameters(consumerCredentials);
    result.oauth_callback = RAML.Settings.oauth1RedirectUri;

    return result;
  };

  Signer.generateTokenCredentialParameters = function(consumerCredentials, tokenCredentials) {
    var result = baseParameters(consumerCredentials);

    result.oauth_token = tokenCredentials.token;
    if (tokenCredentials.verifier) {
      result.oauth_verifier = tokenCredentials.verifier;
    }

    return result;
  };

  // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/encodeURIComponent
  Signer.rfc3986Encode = function(str) {
    return encodeURIComponent(str).replace(/[!'()]/g, window.escape).replace(/\*/g, '%2A');
  };

  Signer.setRequestHeader = function(params, request) {
    var header = Object.keys(params).map(function(key) {
      return key + '="' + Signer.rfc3986Encode(params[key]) + '"';
    }).join(', ');

    request.header('Authorization', 'OAuth ' + header);
  };
})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  var generateTemporaryCredentialParameters = RAML.Client.AuthStrategies.Oauth1.Signer.generateTemporaryCredentialParameters,
      generateTokenCredentialParameters = RAML.Client.AuthStrategies.Oauth1.Signer.generateTokenCredentialParameters,
      rfc3986Encode = RAML.Client.AuthStrategies.Oauth1.Signer.rfc3986Encode,
      setRequestHeader = RAML.Client.AuthStrategies.Oauth1.Signer.setRequestHeader;

  function generateSignature(params, request, key) {
    params.oauth_signature_method = 'HMAC-SHA1';
    params.oauth_timestamp = Math.floor(Date.now() / 1000);
    params.oauth_nonce = CryptoJS.lib.WordArray.random(16).toString();

    var data = Hmac.constructHmacText(request, params);
    var hash = CryptoJS.HmacSHA1(data, key);
    params.oauth_signature = hash.toString(CryptoJS.enc.Base64);
  }

  var Hmac = {
    constructHmacText: function(request, oauthParams) {
      var options = request.toOptions();

      return [
        options.method.toUpperCase(),
        this.encodeURI(options.url),
        rfc3986Encode(this.encodeParameters(request, oauthParams))
      ].join('&');
    },

    encodeURI: function(uri) {
      var parser = document.createElement('a');
      parser.href = uri;

      var hostname = '';
      if (parser.protocol === 'https:' && parser.port === 443 || parser.protocol === 'http:' && parser.port === 80) {
        hostname = parser.hostname.toLowerCase();
      } else {
        hostname = parser.host.toLowerCase();
      }

      return rfc3986Encode(parser.protocol + '//' + hostname + parser.pathname);
    },

    encodeParameters: function(request, oauthParameters) {
      var params = request.queryParams();
      var formParams = {};
      if (request.toOptions().contentType === 'application/x-www-form-urlencoded') {
        formParams = request.data();
      }

      var result = [];
      for (var key in params) {
        result.push([rfc3986Encode(key), rfc3986Encode(params[key])]);
      }

      for (var formKey in formParams) {
        result.push([rfc3986Encode(formKey), rfc3986Encode(formParams[formKey])]);
      }

      for (var oauthKey in oauthParameters) {
        result.push([rfc3986Encode(oauthKey), rfc3986Encode(oauthParameters[oauthKey])]);
      }

      result.sort(function(a, b) {
        return (a[0] === b[0] ? a[1].localeCompare(b[1]) : a[0].localeCompare(b[0]));
      });

      return result.map(function(tuple) { return tuple.join('='); }).join('&');
    }
  };

  Hmac.Temporary = function(consumerCredentials) {
    this.consumerCredentials = consumerCredentials;
  };

  Hmac.Temporary.prototype.sign = function(request) {
    var params = generateTemporaryCredentialParameters(this.consumerCredentials);
    var key = rfc3986Encode(this.consumerCredentials.consumerSecret) + '&';

    generateSignature(params, request, key);
    setRequestHeader(params, request);
  };

  Hmac.Token = function(consumerCredentials, tokenCredentials) {
    this.consumerCredentials = consumerCredentials;
    this.tokenCredentials = tokenCredentials;
  };

  Hmac.Token.prototype.sign = function(request) {
    var params = generateTokenCredentialParameters(this.consumerCredentials, this.tokenCredentials);
    var key = rfc3986Encode(this.consumerCredentials.consumerSecret) + '&' + rfc3986Encode(this.tokenCredentials.tokenSecret);

    generateSignature(params, request, key);
    setRequestHeader(params, request);
  };

  RAML.Client.AuthStrategies.Oauth1.Signer.Hmac = Hmac;
})();

(function() {
  /* jshint camelcase: false */
  'use strict';

  var generateTemporaryCredentialParameters = RAML.Client.AuthStrategies.Oauth1.Signer.generateTemporaryCredentialParameters,
      generateTokenCredentialParameters = RAML.Client.AuthStrategies.Oauth1.Signer.generateTokenCredentialParameters,
      rfc3986Encode = RAML.Client.AuthStrategies.Oauth1.Signer.rfc3986Encode,
      setRequestHeader = RAML.Client.AuthStrategies.Oauth1.Signer.setRequestHeader;

  var Plaintext = {};

  Plaintext.Temporary = function(consumerCredentials) {
    this.consumerCredentials = consumerCredentials;
  };

  Plaintext.Temporary.prototype.sign = function(request) {
    var params = generateTemporaryCredentialParameters(this.consumerCredentials);
    params.oauth_signature = rfc3986Encode(this.consumerCredentials.consumerSecret) + '&';
    params.oauth_signature_method = 'PLAINTEXT';

    setRequestHeader(params, request);
  };

  Plaintext.Token = function(consumerCredentials, tokenCredentials) {
    this.consumerCredentials = consumerCredentials;
    this.tokenCredentials = tokenCredentials;
  };

  Plaintext.Token.prototype.sign = function(request) {
    var params = generateTokenCredentialParameters(this.consumerCredentials, this.tokenCredentials);
    params.oauth_signature = rfc3986Encode(this.consumerCredentials.consumerSecret) + '&' + rfc3986Encode(this.tokenCredentials.tokenSecret);
    params.oauth_signature_method = 'PLAINTEXT';

    setRequestHeader(params, request);
  };

  RAML.Client.AuthStrategies.Oauth1.Signer.Plaintext = Plaintext;
})();

(function() {
  'use strict';

  var Oauth2 = function(scheme, credentials) {
    this.scheme = scheme;
    this.credentials = credentials;
  };

  function getScopes(credentials) {
    var scopes = [];

    if (credentials.scopes) {
      scopes = Object.keys(credentials.scopes).filter(function (scope) {
        return credentials.scopes[scope] === true;
      });
    }

    return scopes;
  }

  function popup(location) {
    var w    = 640;
    var h    = 480;
    var left = (screen.width / 2) - (w / 2);
    var top  = (screen.height / 2) - (h / 2);
    return window.open(location, 'Authentication', 'toolbar=no, location=no, directories=no, status=no, menubar=no, scrollbars=no, resizable=no, copyhistory=no, width=' + w + ', height=' + h + ', top=' + top + ', left=' + left);
  }

  Oauth2.prototype.authenticate = function(options, done) {
    var auth = new ClientOAuth2({
      clientId:         this.credentials.clientId,
      clientSecret:     this.credentials.clientSecret,
      accessTokenUri:   this.scheme.settings.accessTokenUri,
      authorizationUri: this.scheme.settings.authorizationUri,
      redirectUri:      RAML.Settings.oauth2RedirectUri,
      scopes:           getScopes(this.credentials)
    });
    var grantType = this.credentials.grant;

    if (grantType === 'token' || grantType === 'code' || grantType === 'authorization_code' || grantType === 'implicit') {
      window.oauth2Callback = function (uri) {
        auth[grantType].getToken(uri, function (err, user, raw) {
          if (err) {
            done(raw, err);
          }

          if (user && user.accessToken) {
            user.request(options, function (err, res) {
              done(res.raw, err);
            });
          }
        });
      };
      //// TODO: Find a way to handle 404
      popup(auth[grantType].getUri());
    }

    if (grantType === 'owner' || grantType === 'password') {
      auth.owner.getToken(this.credentials.username, this.credentials.password, function (err, user, raw) {
        if (err) {
          done(raw, err);
        }

        if (user && user.accessToken) {
          user.request(options, function (err, res) {
            done(res.raw, err);
          });
        }
      });
    }

    if (grantType === 'credentials'|| grantType === 'client_credentials') {
      auth.credentials.getToken(function (err, user, raw) {
        if (err) {
          done(raw, err);
        }

        if (user && user.accessToken) {
          user.request(options, function (err, res) {
            done(res.raw, err);
          });
        }
      });
    }
  };

  RAML.Client.AuthStrategies.Oauth2 = Oauth2;
})();

(function() {
  'use strict';

  var templateMatcher = /\{([^}]*)\}/g;

  function tokenize(template) {
    var tokens = template.split(templateMatcher);

    return tokens.filter(function(token) {
      return token.length > 0;
    });
  }

  function rendererFor(template, uriParameters, options) {
    return function renderer(context) {
      context = context || {};

      // Enforce request without URI parameters
      // requiredParameters.forEach(function(name) {
      //   if (!context[name]) {
      //     throw new Error('Missing required uri parameter: ' + name);
      //   }
      // });

      var templated = template.replace(templateMatcher, function(match, parameterName) {
        if (context[parameterName]) {
          if (typeof context[parameterName][0] !== 'object') {
            return context[parameterName];
          }
          var tempValue = JSON.stringify(
            RAML.Inspector.Properties.cleanupPropertyValue(context[parameterName][0]));

          if (options.encodeURIComponent) {
            tempValue = encodeURIComponent(tempValue);
          }

          return tempValue;
        }
        return '';
      });

      return templated;
    };
  }

  RAML.Client.ParameterizedString = function(template, uriParameters, options) {
    options = angular.extend({ parameterValues: {} }, options);
    template = template.replace(templateMatcher, function(match, parameterName) {
      if (options.parameterValues[parameterName]) {
        return options.parameterValues[parameterName];
      }
      return '{' + parameterName + '}';
    });

    this.parameters = uriParameters;
    this.templated = Object.keys(this.parameters || {}).length > 0;
    this.tokens = tokenize(template);
    this.render = rendererFor(template, uriParameters, options);
    this.toString = function() { return template; };
  };
})();

(function() {
  'use strict';

  RAML.Client.PathBuilder = {
    create: function(pathSegments) {
      return function pathBuilder(contexts) {
        contexts = contexts || [];

        return pathSegments.map(function(pathSegment, index) {
          return pathSegment.render(contexts[index]);
        }).join('');
      };
    }
  };
})();

(function() {
  'use strict';

  var CONTENT_TYPE = 'content-type';
  var FORM_DATA = 'multipart/form-data';

  var RequestDsl = function(options) {
    var rawData;
    var queryParams;
    var isMultipartRequest;

    this.data = function(data) {
      if (data === undefined) {
        return RAML.Utils.clone(rawData);
      } else {
        rawData = data;
      }
    };

    this.queryParams = function(parameters) {
      if (parameters === undefined) {
        return RAML.Utils.clone(queryParams);
      } else {
        queryParams = parameters;
      }
    };

    this.queryParam = function(name, value) {
      queryParams = queryParams || {};
      queryParams[name] = value;
    };

    this.header = function(name, value) {
      options.headers = options.headers || {};

      if (name.toLowerCase() === CONTENT_TYPE) {
        if (value === FORM_DATA) {
          isMultipartRequest = true;
          options.contentType = false;
          return;
        } else {
          isMultipartRequest = false;
          options.contentType = value;
        }
      }

      options.headers[name] = value;
    };

    this.headers = function(headers) {
      options.headers = {};
      isMultipartRequest = false;

      for (var name in headers) {
        this.header(name, headers[name]);
      }

      if (Object.keys(options.headers).length === 0) {
        options.headers = null;
      }
    };

    this.toOptions = function() {
      var o = RAML.Utils.copy(options);
      o.traditional = true;

      if (rawData) {
        if (isMultipartRequest) {
          var data = new FormData();

          var appendValueForKey = function(key) {
            return function(value) {
              data.append(key, value);
            };
          };

          for (var key in rawData) {
            rawData[key].forEach(appendValueForKey(key));
          }

          o.processData = false;
          o.data = data;
        } else {
          o.processData = true;
          o.data = rawData;
        }
      }

      o.baseUrl = options.url;

      if (!RAML.Utils.isEmpty(queryParams)) {
        var separator = (options.url.match('\\?') ? '&' : '?');

        o.baseUrl = options.url + separator;
        o.url = options.url + separator + jQuery.param(queryParams, true);
      }

      if (!RAML.Settings.disableProxy && RAML.Settings.proxy) {
        o.url = RAML.Settings.proxy + o.url;
      }

      return o;
    };
  };

  RAML.Client.Request = {
    create: function(url, method) {
      return new RequestDsl({ url: url, method: method });
    }
  };
})();

(function() {
  'use strict';

  // number regular expressions from http://yaml.org/spec/1.2/spec.html#id2804092

  var RFC1123 = /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/;

  function isEmpty(value) {
    return value == null || value === '';
  }

  var VALIDATIONS = {
    required: function(value) {
      return !isEmpty(value);
    },
    'boolean': function(value) {
      return isEmpty(value) || value === 'true' || value === 'false';
    },
    'enum': function(enumeration) {
      return function(value) {
        return isEmpty(value) || enumeration.indexOf(value) > -1;
      };
    },
    integer: function(value) {
      return isEmpty(value) || /^-?(0|[1-9][0-9]*)$/.test(value);
    },
    number: function(value) {
      return isEmpty(value) || /^-?(0|[1-9][0-9]*)(\.[0-9]*)?([eE][-+]?[0-9]+)?$/.test(value);
    },
    minimum: function(minimum) {
      return function(value) {
        return isEmpty(value) || value >= minimum;
      };
    },
    maximum: function(maximum) {
      return function(value) {
        return isEmpty(value) || value <= maximum;
      };
    },
    minLength: function(minimum) {
      return function(value) {
        return isEmpty(value) || value.length >= minimum;
      };
    },
    maxLength: function(maximum) {
      return function(value) {
        return isEmpty(value) || value.length <= maximum;
      };
    },
    pattern: function(pattern) {
      var regex = new RegExp(pattern);

      return function(value) {
        return isEmpty(value) || regex.test(value);
      };
    },
    date: function(value) {
      return isEmpty(value) || RFC1123.test(value);
    }
  };

  function baseValidations(definition) {
    var validations = {};

    if (definition.required) {
      validations.required = VALIDATIONS.required;
    }

    return validations;
  }

  function numberValidations(validations, definition) {
    if (definition.minimum != null) {
      validations.minimum = VALIDATIONS.minimum(definition.minimum);
    }

    if (definition.maximum != null) {
      validations.maximum = VALIDATIONS.maximum(definition.maximum);
    }
  }

  // function copyValidations(validations, types) {
  //   Object.keys(types).forEach(function(type) {
  //     validations[type] = VALIDATIONS[type](types[type]);
  //   });
  // }

  var VALIDATIONS_FOR_TYPE = {
    string: function(definition) {
      var validations = baseValidations(definition);

      if (Array.isArray(definition['enum'])) {
        validations['enum'] = VALIDATIONS['enum'](definition['enum']);
      }

      if (definition.minLength != null) {
        validations.minLength = VALIDATIONS.minLength(definition.minLength);
      }

      if (definition.maxLength != null) {
        validations.maxLength = VALIDATIONS.maxLength(definition.maxLength);
      }

      if (definition.pattern) {
        validations.pattern = VALIDATIONS.pattern(definition.pattern);
      }

      return validations;
    },

    integer: function(definition) {
      var validations = baseValidations(definition);
      validations.integer = VALIDATIONS.integer;
      numberValidations(validations, definition);
      return validations;
    },

    number: function(definition) {
      var validations = baseValidations(definition);
      validations.number = VALIDATIONS.number;
      numberValidations(validations, definition);
      return validations;
    },

    'boolean': function(definition) {
      var validations = baseValidations(definition);
      // Ignore better written in dot notation rule
      /*jshint -W069 */
      validations['boolean'] = VALIDATIONS['boolean'];
      /*jshint +W069 */
      return validations;
    },

    date: function(definition) {
      var validations = baseValidations(definition);
      validations.date = VALIDATIONS.date;
      return validations;
    }
  };

  function Validator(validations) {
    this.validations = validations;
  }

  Validator.prototype.validate = function(value) {
    var errors;

    for (var validation in this.validations) {
      if (!this.validations[validation](value)) {
        errors = errors || [];
        errors.push(validation);
      }
    }

    return errors;
  };

  Validator.from = function(definition) {
    if (!definition) {
      throw new Error('definition is required!');
    }

    var validations;

    if (VALIDATIONS_FOR_TYPE[definition.type]) {
      validations = VALIDATIONS_FOR_TYPE[definition.type](definition);
    } else {
      validations = {};
    }

    return new Validator(validations);
  };

  RAML.Client.Validator = Validator;
})();

(function() {
  'use strict';

  RAML.Filters.nameFromParameterizable = function() {
    return function(input) {
      if (typeof input === 'object' && input !== null) {
        return Object.keys(input)[0];
      } else if (input) {
        return input;
      } else {
        return undefined;
      }
    };
  };
})();

RAML.Inspector = (function() {
  'use strict';

  var exports = {};

  var METHOD_ORDERING = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE', 'CONNECT'];

  function extractResources(basePathSegments, api, securitySchemes) {
    var resources = [], apiResources = api.resources || [];

    apiResources.forEach(function(resource) {
      var resourcePathSegments = basePathSegments.concat(RAML.Client.createPathSegment(resource));
      var overview = exports.resourceOverviewSource(resourcePathSegments, resource);

      overview.methods = overview.methods.map(function(method) {
        return RAML.Inspector.Method.create(method, securitySchemes);
      });


      resources.push(overview);

      if (resource.resources) {
        var extracted = extractResources(resourcePathSegments, resource, securitySchemes);
        extracted.forEach(function(resource) {
          resources.push(resource);
        });
      }
    });

    return resources;
  }

  function groupResources(resources) {
    var currentPrefix, resourceGroups = [];

    (resources || []).forEach(function(resource) {
      var prefix = resource.pathSegments[0].toString();
      if (prefix === currentPrefix || prefix.indexOf(currentPrefix + '/') === 0) {
        resourceGroups[resourceGroups.length-1].push(resource);
      } else {
        currentPrefix = resource.pathSegments[0].toString();
        resourceGroups.push([resource]);
      }
    });

    return resourceGroups;
  }

  exports.resourceOverviewSource = function(pathSegments, resource) {
    var clone = RAML.Utils.clone(resource);

    clone.traits = resource.is;
    clone.resourceType = resource.type;
    clone.type = clone.is = undefined;
    clone.pathSegments = pathSegments;

    clone.methods = (resource.methods || []);

    clone.methods.sort(function(a, b) {
      var aOrder = METHOD_ORDERING.indexOf(a.method.toUpperCase());
      var bOrder = METHOD_ORDERING.indexOf(b.method.toUpperCase());

      return aOrder > bOrder ? 1 : -1;
    });

    clone.uriParametersForDocumentation = RAML.Inspector.Properties.normalizeNamedParameters(pathSegments
      .map(function(segment) { return segment.parameters; })
      .filter(function(params) { return !!params; })
      .reduce(function(accum, parameters) {
        for (var key in parameters) {
          accum[key] = parameters[key];
        }
        return accum;
      }, {}));

    if (Object.keys(clone.uriParametersForDocumentation).length === 0) {
      clone.uriParametersForDocumentation = null;
    }

    clone.toString = function() {
      return this.pathSegments.map(function(segment) { return segment.toString(); }).join('');
    };

    return clone;
  };

  exports.create = function(api) {
    // Perform deep copy to avoid mutating object.
    // TODO: Audit code and only use parts of object that are required.
    api = jQuery.extend(true, {}, api);

    if (api.baseUri) {
      api.baseUri = RAML.Client.createBaseUri(api);
    }

    api.resources = extractResources([], api, api.securitySchemes);
    api.resourceGroups = groupResources(api.resources);

    return api;
  };

  return exports;
})();

(function() {
  'use strict';

  var PARAMETER = /\{\*\}/;

  function wrapWithParameterizedHeader(name, definitions) {
    return definitions.map(function(definition) {
      return RAML.Inspector.ParameterizedHeader.fromRAML(name, definition);
    });
  }

  function filterHeaders(headers) {
    var filtered = {
      plain: {},
      parameterized: {}
    };

    Object.keys(headers || {}).forEach(function(key) {
      if (key.match(PARAMETER)) {
        filtered.parameterized[key] = wrapWithParameterizedHeader(key, headers[key]);
      } else {
        filtered.plain[key] = headers[key];
      }
    });

    if(Object.keys(filtered.plain).length === 0) {
      filtered.plain = null;
    }

    return filtered;
  }

  function processBody(body) {
    var content = body['application/x-www-form-urlencoded'];
    if (content) {
      RAML.Inspector.Properties.normalizeNamedParameters(content.formParameters);
    }

    content = body['multipart/form-data'];
    if (content) {
      RAML.Inspector.Properties.normalizeNamedParameters(content.formParameters);
    }
  }

  function processResponses(responses) {
    Object.keys(responses).forEach(function(status) {
      var response = responses[status];
      if (response) {
        RAML.Inspector.Properties.normalizeNamedParameters(response.headers);
      }
    });
  }

  function securitySchemesExtractor(securitySchemes) {
    securitySchemes = securitySchemes || [];

    return function() {
      var securedBy = this.securedBy || [],
          selectedSchemes = {};

      var overwrittenSchemes = {};

      securedBy.map(function(el) {
        if (el === null) {
          securitySchemes.push({
            anonymous: {
              type: 'Anonymous'
            }
          });
          securedBy.push('anonymous');
        }

        if (typeof el === 'object' && el) {
          var key = Object.keys(el)[0];

          overwrittenSchemes[key] = el[key];
          securedBy.push(key);
        }
      });

      securedBy = securedBy.filter(function(name) {
        return name !== null && typeof name !== 'object';
      });

      securitySchemes.forEach(function(scheme) {
        securedBy.forEach(function(name) {
          if (scheme[name]) {
            selectedSchemes[name] = jQuery.extend(true, {}, scheme[name]);
          }
        });
      });

      Object.keys(overwrittenSchemes).map(function (key) {
        Object.keys(overwrittenSchemes[key]).map(function (prop) {
          if (selectedSchemes[key].settings) {
            selectedSchemes[key].settings[prop] = overwrittenSchemes[key][prop];
          }
        });
      });

      if(Object.keys(selectedSchemes).length === 0) {
        selectedSchemes.anonymous = {
          type: 'Anonymous'
        };
      }

      return selectedSchemes;
    };
  }

  function allowsAnonymousAccess() {
    /*jshint validthis: true */
    var securedBy = this.securedBy || [null];
    return securedBy.some(function(name) { return name === null; });
  }

  RAML.Inspector.Method = {
    create: function(raml, securitySchemes) {
      var method = RAML.Utils.clone(raml);

      method.responseCodes = Object.keys(method.responses || {});
      method.securitySchemes = securitySchemesExtractor(securitySchemes);
      method.allowsAnonymousAccess = allowsAnonymousAccess;
      RAML.Inspector.Properties.normalizeNamedParameters(method.headers);
      RAML.Inspector.Properties.normalizeNamedParameters(method.queryParameters);

      method.headers = filterHeaders(method.headers);
      processBody(method.body || {});
      processResponses(method.responses || {});

      method.plainAndParameterizedHeaders = RAML.Utils.copy(method.headers.plain);
      Object.keys(method.headers.parameterized).forEach(function(parameterizedHeader) {
        method.plainAndParameterizedHeaders[parameterizedHeader] = method.headers.parameterized[parameterizedHeader].map(function(parameterized) {
          return parameterized.definition();
        });
      });

      return method;
    }
  };
})();

(function () {
  'use strict';

  function validate(value) {
    value = value ? value.trim() : '';

    if (value === '') {
      throw new Error();
    }

    return value;
  }

  function fromRAML(name, definition) {
    var parameterizedString = new RAML.Client.ParameterizedString(name, definition);

    return {
      create: function(value) {
        value = validate(value);

        var header = RAML.Utils.clone(definition);
        header.displayName = parameterizedString.render({'*': value});

        return header;
      },
      definition: function() {
        return definition;
      }
    };
  }

  RAML.Inspector.ParameterizedHeader = {
    fromRAML: fromRAML
  };
})();

(function() {
  'use strict';

  function ensureArray(value) {
    if (value === undefined || value === null) {
      return;
    }

    return (value instanceof Array) ? value : [ value ];
  }

  function normalizeNamedParameters(parameters) {
    Object.keys(parameters || {}).forEach(function(key) {
      if (parameters[key].properties) {
        normalizeNamedParameters(parameters[key].properties);
      }

      parameters[key] = ensureArray(parameters[key]);
    });

    return parameters;
  }

  function cleanupPropertyValue(value) {
    if (typeof value !== 'object') {
      return value;
    }
    var cleanedValue = {};

    if (Array.isArray(value)) {
      cleanedValue = value.map(function (arrayItem) {
        return cleanupPropertyValue(arrayItem[0]);
      });
    } else {
      Object.keys(value).forEach(function (key) {
        cleanedValue[key] = cleanupPropertyValue(value[key] ? value[key][0] : value[key]);

        // Remove empty array property
        if (!cleanedValue[key][0]) {
          delete cleanedValue[key];
        }
      });
    }

    return cleanedValue;
  }

  RAML.Inspector.Properties = {
    normalizeNamedParameters: normalizeNamedParameters,
    cleanupPropertyValue: cleanupPropertyValue
  };
})();

(function() {
  'use strict';

  var UNION_ARRAY_REGEXP = /^\([^\)]*\)\[\]$/;

  function cleanupTypeName(typeName) {
    return typeName.replace('[]', '').replace('(', '').replace(')', '').trim();
  }

  function isNativeType(typeName) {
    typeName = cleanupTypeName(typeName);
    var nativeTypes = [
      'object',
      'string',
      'number',
      'integer',
      'boolean',
      'date-only',
      'time-only',
      'datetime-only',
      'datetime',
      'file',
      'array'
    ];
    return nativeTypes.indexOf(typeName) !== -1;
  }

  function isSchema(typeName) {
    try {
      JSON.parse(typeName);
      return true;
    } catch (error) {
      return false;
    }
  }

  function find(name, collection) {
    var found;
    var length = collection.length;
    for (var i = 0; i < length; i++) {
      if (collection[i][name]) {
        found = collection[i][name];
        break;
      }
    }
    return found;
  }

  function findType(typeName, types) {
    if (types) {
      typeName = cleanupTypeName(typeName);
      var existingType = find(typeName, types);
      return existingType;
    }
  }

  function findSchema(schemaName, schemas) {
    if (schemas) {
      var existingSchema = find(schemaName, schemas);
      return existingSchema;
    }
  }

  function getSuperTypesProperties(properties, typeName, types) {
    if (!isNativeType(typeName)) {
      var superType = findType(typeName, types);

      if (superType) {
        var superTypeProperties = convertProperties(superType);
        properties = angular.extend({}, superTypeProperties, properties);
        return getSuperTypesProperties(properties, superType.type[0], types);
      }
    }
    return properties;
  }

  function convertProperties(type) {
    if (type.properties) {
      Object.keys(type.properties).forEach(function (propertyKey) {
        var aProperty = type.properties[propertyKey];
        if (type.discriminator && type.discriminator === aProperty[0].name) {
          aProperty[0].discriminator = true;
        }
      });
    }
    return type.properties;
  }

  function mergeType(type, types) {
    var resultingType = angular.copy(type);
    resultingType.type = resultingType.type || resultingType.schema;
    var properties = angular.copy(resultingType.properties || {});
    var currentType = Array.isArray(resultingType.type) ?
        resultingType.type[0] : resultingType.type;

    properties = convertProperties(resultingType);

    if (!isNativeType(currentType)) {
      resultingType.type.forEach(function (superType) {
        properties = getSuperTypesProperties(properties, superType, types);
      });
    }
    if (properties) {
      var propertiesKeys = Object.keys(properties).sort();

      if (propertiesKeys.length > 0) {
        resultingType.properties = propertiesKeys.map(function (key) {
          return ensureArray(properties[key]);
        });
      }
    }

    return resultingType;
  }

  function getTypeInfo(typeName) {
    var types = typeName.split('|');
    var typeInfo = {};

    if (types.length > 1) {
      typeInfo.type = 'union';
      typeInfo.isArray = UNION_ARRAY_REGEXP.test(typeName);
      typeInfo.parts = types.map(function (type) {
        return cleanupTypeName(type);
      });
    } else if (typeName.indexOf('[]') !== -1) {
      typeInfo.type = 'array';
      typeInfo.parts = [typeName.replace('[]', '').trim()];
    } else {
      typeInfo.type = 'custom';
      typeInfo.parts = [typeName.trim()];
    }

    return typeInfo;
  }

  function getTypeFromTypeInfo(typeInfo) {
    var type;
    if (typeInfo.type === 'union') {
      type = typeInfo.parts.join('|');
      if (typeInfo.isArray) {
        type = '(' + type + ')[]';
      }

      return type;
    } else if (typeInfo.type === 'array') {
      return typeInfo.parts.join('') + '[]';
    } else {
      return typeInfo.parts.join('');
    }
  }

  function ensureArray(type) {
    return Array.isArray(type) ? type : [type];
  }

  RAML.Inspector.Types = {
    mergeType:           mergeType,
    isNativeType:        isNativeType,
    isSchema:            isSchema,
    findType:            findType,
    findSchema:          findSchema,
    getTypeInfo:         getTypeInfo,
    getTypeFromTypeInfo: getTypeFromTypeInfo,
    ensureArray:         ensureArray,
    cleanupTypeName:     cleanupTypeName
  };
})();

(function() {
  'use strict';

  window.ramlErrors = {};

  CodeMirror.registerHelper('lint', 'yaml', function () {
    var found = [];

    found.push({
      message: window.ramlErrors.message,
      severity: 'error',
      from: CodeMirror.Pos(window.ramlErrors.line),
      to: CodeMirror.Pos(window.ramlErrors.line)
    });

    return found;
  });
})();

(function() {
  'use strict';

  var FORM_URLENCODED = 'application/x-www-form-urlencoded';
  var FORM_DATA = 'multipart/form-data';

  var BodyContent = function(contentTypes) {
    this.contentTypes = Object.keys(contentTypes).sort();
    this.selected = this.contentTypes[0];

    var definitions = this.definitions = {};
    this.contentTypes.forEach(function(contentType) {
      var definition = contentTypes[contentType] || {};

      if (definition.formParameters) {
        Object.keys(definition.formParameters).map(function (key) {
          definition.formParameters[key][0].id = key;
        });
      }

      switch (contentType) {
      case FORM_URLENCODED:
      case FORM_DATA:
        definitions[contentType] = new RAML.Services.TryIt.NamedParameters(definition.formParameters);
        break;
      default:
        definitions[contentType] = new RAML.Services.TryIt.BodyType(definition);
      }
    });
  };

  BodyContent.prototype.isForm = function(contentType) {
    return contentType === FORM_URLENCODED || contentType === FORM_DATA;
  };

  BodyContent.prototype.isSelected = function(contentType) {
    return contentType === this.selected;
  };

  BodyContent.prototype.fillWithExample = function($event) {
    $event.preventDefault();
    this.definitions[this.selected].fillWithExample();
  };

  BodyContent.prototype.hasExample = function(contentType) {
    return this.definitions[contentType].hasExample();
  };

  BodyContent.prototype.data = function() {
    if (this.selected) {
      return this.definitions[this.selected].data();
    }
  };

  BodyContent.prototype.copyFrom = function(oldContent) {
    var content = this;

    oldContent.contentTypes.forEach(function(contentType) {
      if (content.definitions[contentType]) {
        content.definitions[contentType].copyFrom(oldContent.definitions[contentType]);
      }
    });

    if (this.contentTypes.some(function(contentType) { return contentType === oldContent.selected; })) {
      this.selected = oldContent.selected;
    }
  };

  BodyContent.prototype.clear = function (info) {
    var that = this.definitions[this.selected];
    Object.keys(this.values).map(function (key) {
      if (typeof info[key][0]['enum'] === 'undefined' || info[key][0].overwritten === true) {
        that.values[key] = [''];
      }
    });
  };

  BodyContent.prototype.reset = function (info, field) {
    var that = this.definitions[this.selected];
    if (info) {
      Object.keys(info).map(function (key) {
        if (typeof field === 'undefined' || field === key) {
          if (typeof info[key][0]['enum'] === 'undefined') {
            that.values[key][0] = info[key][0].example;
          }
        }
      });
    }
  };

  RAML.Services.TryIt.BodyContent = BodyContent;
})();

(function() {
  'use strict';

  var BodyType = function(contentType) {
    this.contentType = contentType || {};
    this.value = undefined;
  };

  BodyType.prototype.fillWithExample = function() {
    var example;
    if (this.contentType.examples) {
      example = this.contentType.examples[0].value;
    } else {
      example = this.contentType.example;
    }

    if (typeof example === 'object') {
      this.value = JSON.stringify(example);
    } else {
      this.value = example;
    }

  };

  BodyType.prototype.hasExample = function() {
    return !!this.contentType.example || !!this.contentType.examples;
  };

  BodyType.prototype.data = function() {
    return this.value;
  };

  BodyType.prototype.copyFrom = function(oldBodyType) {
    this.value = oldBodyType.value;
  };

  RAML.Services.TryIt.BodyType = BodyType;
})();

(function() {
  'use strict';

  var Context = function(baseUriParameters, resource, method) {
    this.headers = new RAML.Services.TryIt.NamedParameters(method.headers.plain, method.headers.parameterized);
    this.queryParameters = new RAML.Services.TryIt.NamedParameters(method.queryParameters);

    resource.uriParametersForDocumentation = resource.uriParametersForDocumentation || {};

    if (baseUriParameters) {
      Object.keys(baseUriParameters).map(function (key) {
        resource.uriParametersForDocumentation[key] = [baseUriParameters[key]];
      });
    }

    if (Object.keys(resource.uriParametersForDocumentation).length === 0) {
      resource.uriParametersForDocumentation = null;
    }

    this.uriParameters = new RAML.Services.TryIt.NamedParameters(resource.uriParametersForDocumentation);

    if (method.body) {
      this.bodyContent = new RAML.Services.TryIt.BodyContent(method.body);
    }

    this.pathBuilder = new RAML.Client.PathBuilder.create(resource.pathSegments);
    this.pathBuilder.baseUriContext = {};
    this.pathBuilder.segmentContexts = resource.pathSegments.map(function() {
      return {};
    });
  };

  Context.prototype.merge = function(oldContext) {
    this.headers.copyFrom(oldContext.headers);
    this.queryParameters.copyFrom(oldContext.queryParameters);
    this.uriParameters.copyFrom(oldContext.uriParameters);
    if (this.bodyContent && oldContext.bodyContent) {
      this.bodyContent.copyFrom(oldContext.bodyContent);
    }

    this.pathBuilder.baseUriContext = oldContext.pathBuilder.baseUriContext;
    this.pathBuilder.segmentContexts = oldContext.pathBuilder.segmentContexts;
  };

  RAML.Services.TryIt.Context = Context;
})();

(function() {
  'use strict';

  var NamedParameter = function(definitions) {
    this.definitions = definitions;
    this.selected = definitions[0].type;
  };

  NamedParameter.prototype.hasMultipleTypes = function() {
    return this.definitions.length > 1;
  };

  NamedParameter.prototype.isSelected = function(definition) {
    return this.selected === definition.type;
  };

  RAML.Services.TryIt.NamedParameter = NamedParameter;
})();

(function() {
  'use strict';

  function copy(object) {
    var shallow = {};
    Object.keys(object || {}).forEach(function(key) {
      shallow[key] = new RAML.Services.TryIt.NamedParameter(object[key]);
    });

    return shallow;
  }

  function filterEmpty(object) {
    var copy = {};

    Object.keys(object).forEach(function(key) {
      var values = object[key].filter(function(value) {
        return value !== undefined && value !== null && (typeof value !== 'string' || value.trim().length > 0);
      });

      if (values.length > 0) {
        copy[key] = values;
      }
    });

    return copy;
  }

  var NamedParameters = function(plain, parameterized) {
    this.plain = copy(plain);
    this.parameterized = parameterized;
    Object.keys(this.plain).forEach(function (key) {
      var data = this.plain[key].definitions[0];

      if (typeof data['enum'] !== 'undefined') {
        if (!data.required) {
          var temp = [''];
          data['enum'] = temp.concat(data['enum']);
        }
      }

      if (key.charAt(0) === '$') {
        var tempKey = '&#36;' + key.substring(1);
        this.plain[tempKey] = this.plain[key];
      }
    }.bind(this));

    Object.keys(parameterized || {}).forEach(function(key) {
      parameterized[key].created = [];
    });

    this.values = {};
    Object.keys(this.plain).forEach(function(key) {
      this.values[key] = [undefined];
    }.bind(this));
  };

  NamedParameters.prototype.clear = function (info) {
    var that = this;
    Object.keys(this.values).map(function (key) {
      if (typeof info[key][0]['enum'] === 'undefined' || info[key][0].overwritten === true) {
        that.values[key] = [''];
      }
    });
  };

  NamedParameters.prototype.reset = function (info, field) {
    var that = this;
    if (info) {
      Object.keys(info).map(function (key) {
        if (typeof field === 'undefined' || field === key) {
          if (typeof info[key][0]['enum'] === 'undefined') {
            if (info[key][0].type === 'date' && typeof info[key][0].example === 'object') {
              info[key][0].example = info[key][0].example.toUTCString();
            }

            if (info[key][0].example) {
              that.values[key][0] = info[key][0].example;
            } else if (info[key][0].examples && info[key][0].examples[0] && info[key][0].examples[0].value) {
              that.values[key][0] = info[key][0].examples[0].value;
            }
          }
        }
      });
    }
  };

  NamedParameters.prototype.create = function(name, value) {
    var parameters = this.parameterized[name];

    var definition = parameters.map(function(parameterizedHeader) {
      return parameterizedHeader.create(value);
    });

    var parameterizedName = definition[0].displayName;

    parameters.created.push(parameterizedName);
    this.plain[parameterizedName] = new RAML.Services.TryIt.NamedParameter(definition);
    this.values[parameterizedName] = [undefined];
  };

  NamedParameters.prototype.remove = function(name) {
    delete this.plain[name];
    delete this.values[name];
    return;
  };

  NamedParameters.prototype.data = function() {
    return filterEmpty(this.values);
  };

  NamedParameters.prototype.copyFrom = function(oldParameters) {
    var parameters = this;

    Object.keys(oldParameters.parameterized || {}).forEach(function(key) {
      if (parameters.parameterized[key]) {
        oldParameters.parameterized[key].created.forEach(function(createdParam) {
          parameters.plain[createdParam] = oldParameters.plain[createdParam];
        });
      }
    });

    var keys = Object.keys(oldParameters.plain || {}).filter(function(key) {
      return parameters.plain[key];
    });

    keys.forEach(function(key) {
      parameters.values[key] = oldParameters.values[key];
    });
  };

  RAML.Services.TryIt.NamedParameters = NamedParameters;
})();

(function() {
  'use strict';

  function Clone() {}

  RAML.Utils = {
    clone: function(object) {
      Clone.prototype = object;
      return new Clone();
    },

    copy: function(object) {
      var copiedObject = {};
      for (var key in object) {
        copiedObject[key] = object[key];
      }
      return copiedObject;
    },

    isEmpty: function(object) {
      if (object) {
        return Object.keys(object).length === 0;
      } else {
        return true;
      }
    },

    filterEmpty: function (object) {
      var copy = {};

      Object.keys(object).forEach(function(key) {
        var value = object[key];
        var flag = value !== undefined && value !== null && (typeof value !== 'string' || value.trim().length > 0);

        if (flag) {
          copy[key] = value;
        }
      });

      return copy;
    }
  };
})();

(function (root) {
  var _hasOwnProperty = Object.prototype.hasOwnProperty;
  var btoa            = typeof Buffer === 'function' ? bufferBtoa : root.btoa;

  /**
   * Format error response types to regular strings for displaying to clients.
   *
   * Reference: http://tools.ietf.org/html/rfc6749#section-4.1.2.1
   *
   * @type {Object}
   */
  var ERROR_RESPONSES = {
    'invalid_request': [
      'The request is missing a required parameter, includes an',
      'invalid parameter value, includes a parameter more than',
      'once, or is otherwise malformed.'
    ].join(' '),
    'invalid_client': [
      'Client authentication failed (e.g., unknown client, no',
      'client authentication included, or unsupported',
      'authentication method).'
    ].join(' '),
    'invalid_grant': [
      'The provided authorization grant (e.g., authorization',
      'code, resource owner credentials) or refresh token is',
      'invalid, expired, revoked, does not match the redirection',
      'URI used in the authorization request, or was issued to',
      'another client.'
    ].join(' '),
    'unauthorized_client': [
      'The client is not authorized to request an authorization',
      'code using this method.'
    ].join(' '),
    'unsupported_grant_type': [
      'The authorization grant type is not supported by the',
      'authorization server.'
    ].join(' '),
    'access_denied': [
      'The resource owner or authorization server denied the request.'
    ].join(' '),
    'unsupported_response_type': [
      'The authorization server does not support obtaining',
      'an authorization code using this method.'
    ].join(' '),
    'invalid_scope': [
      'The requested scope is invalid, unknown, or malformed.'
    ].join(' '),
    'server_error': [
      'The authorization server encountered an unexpected',
      'condition that prevented it from fulfilling the request.',
      '(This error code is needed because a 500 Internal Server',
      'Error HTTP status code cannot be returned to the client',
      'via an HTTP redirect.)'
    ].join(' '),
    'temporarily_unavailable': [
      'The authorization server is currently unable to handle',
      'the request due to a temporary overloading or maintenance',
      'of the server.'
    ].join(' ')
  };

  /**
   * Iterate over a source object and copy properties to the destination object.
   *
   * @param  {Object} dest
   * @param  {Object} source
   * @return {Object}
   */
  function assign (dest /*, ...source */) {
    for (var i = 1; i < arguments.length; i++) {
      var source = arguments[i];

      for (var key in source) {
        if (_hasOwnProperty.call(source, key)) {
          dest[key] = source[key];
        }
      }
    }

    return dest;
  }

  /**
   * Support base64 in node like how it works in the browser.
   *
   * @param  {String} string
   * @return {String}
   */
  function bufferBtoa (string) {
    return new Buffer(string).toString('base64');
  }

  /**
   * Check if properties exist on an object and throw when they aren't.
   *
   * @throws {TypeError} If an expected property is missing.
   *
   * @param {Object} obj
   * @param {Array}  props
   */
  function expects (obj, props) {
    for (var i = 0; i < props.length; i++) {
      var prop = props[i];
    }
  }

  /**
   * Create a new object based on a source object with keys omitted.
   *
   * @param  {Object} source
   * @param  {Array}  keys
   * @return {Object}
   */
  function omit (source, keys) {
    var obj = {};

    // Iterate over the source object and set properties on a new object.
    Object.keys(source || {}).forEach(function (key) {
      if (keys.indexOf(key) === -1) {
        obj[key] = source[key];
      }
    });

    return obj;
  }

  /**
   * Attempt (and fix) URI component encoding.
   *
   * @param  {String} str
   * @return {String}
   */
  function encodeComponent (str) {
    return encodeURIComponent(str)
      .replace(/[!'()]/g, root.escape)
      .replace(/\*/g, '%2A');
  }

  /**
   * Attempt URI component decoding.
   *
   * @param  {String} str
   * @return {String}
   */
  function decodeComponent (str) {
    return decodeURIComponent(str);
  }

  /**
   * Convert an object into a query string.
   *
   * @param  {Object} obj
   * @param  {String} sep
   * @param  {String} eq
   * @return {String}
   */
  function uriEncode (obj, sep, eq) {
    var params = [];

    eq  = eq  || '=';
    sep = sep || '&';

    Object.keys(obj).forEach(function (key) {
      var value  = obj[key];
      var keyStr = encodeComponent(key) + eq;

      if (Array.isArray(value)) {
        for (var i = 0; i < value.length; i++) {
          params.push(keyStr + encodeComponent(value[i]));
        }
      } else if (value != null) {
        params.push(keyStr + encodeComponent(value));
      }
    });

    return params.join(sep);
  }

  /**
   * Convert a query string into an object.
   *
   * @param  {String} qs
   * @param  {String} sep
   * @param  {String} eq
   * @return {Object}
   */
  function uriDecode (qs, sep, eq) {
    eq  = eq  || '=';
    sep = sep || '&';
    qs  = qs.split(sep);

    var obj     = {};
    var maxKeys = 1000;
    var len     = qs.length > maxKeys ? maxKeys : qs.length;

    for (var i = 0; i < len; i++) {
      var key   = qs[i].replace(/\+/g, '%20');
      var value = '';
      var index = key.indexOf(eq);

      if (index !== -1) {
        value = key.substr(index + 1);
        key   = key.substr(0, index);
      }

      key   = decodeComponent(key);
      value = decodeComponent(value);

      if (!_hasOwnProperty.call(obj, key)) {
        obj[key] = value;
      } else if (Array.isArray(obj[key])) {
        obj[key].push(value);
      } else {
        obj[key] = [obj[key], value];
      }
    }

    return obj;
  }

  /**
   * Pull an authentication error from the response data.
   *
   * @param  {Object} data
   * @return {String}
   */
  function getAuthError (data) {
    var message = ERROR_RESPONSES[data.error] ||
      data.error ||
      data.error_message;

    // Return an error instance with the message if it exists.
    return message && new Error(message);
  }

  /**
   * Retrieve all the HTTP response headers for an XMLHttpRequest instance.
   *
   * @param  {XMLHttpRequest} xhr
   * @return {Object}
   */
  function getAllReponseHeaders (xhr) {
    var headers = {};

    // Split all header lines and iterate.
    xhr.getAllResponseHeaders().split('\n').forEach(function (header) {
      var index = header.indexOf(':');

      if (index === -1) {
        return;
      }

      var name  = header.substr(0, index).toLowerCase();
      var value = header.substr(index + 1).trim();

      headers[name] = value;
    });

    return headers;
  }

  /**
   * Retrieve body request with valid format depending content type
   *
   * @param {Mixed} data
   * @param {String} contentType
   * @return {Mixed}
   */
  function getBodyRequest(data, contentType) {
    if (contentType === 'application/x-www-form-urlencoded' && typeof data === 'object') {
      return uriEncode(data);
    }
    return data;
  }

  /**
   * Sanitize the scopes option to be a string.
   *
   * @param  {Array}  scopes
   * @return {String}
   */
  function sanitizeScope (scopes) {
    if (!Array.isArray(scopes)) {
      return scopes == null ? null : String(scopes);
    }

    return scopes.join(' ');
  }

  /**
   * Construct an object that can handle the multiple OAuth 2.0 flows.
   *
   * @param {Object} options
   */
  function ClientOAuth2 (options) {
    this.options = options;

    this.code        = this['authorization_code'] = new CodeFlow(this);
    this.token       = this.implicit              = new TokenFlow(this);
    this.owner       = this.password              = new OwnerFlow(this);
    this.credentials = this['client_credentials'] = new CredentialsFlow(this);
  }

  /**
   * Alias the token constructor.
   *
   * @type {Function}
   */
  ClientOAuth2.Token = ClientOAuth2Token;

  /**
   * Create a new token from existing data.
   *
   * @param  {String} access
   * @param  {String} [refresh]
   * @param  {String} [type]
   * @param  {Object} [data]
   * @return {Object}
   */
  ClientOAuth2.prototype.createToken = function (access, refresh, type, data) {
    return new ClientOAuth2Token(this, assign({}, data, {
      access_token:  access,
      refresh_token: refresh,
      token_type:    type
    }));
  };

  /**
   * Using the built-in request method, we'll automatically attempt to parse
   * the response.
   *
   * @param {Object}   options
   * @param {Function} done
   */
  ClientOAuth2.prototype._request = function (options, done) {
    return this.request(options, function (err, res) {
      if (err) {
        return done(err);
      }

      // Check the response status and fail.
      if (res.status && Math.floor(res.status / 100) !== 2) {
        err = new Error('HTTP Status ' + res.status);
        err.status = res.status;

        return done(err);
      }

      // Support already parsed responses in case of custom body parsing.
      if (typeof res.body !== 'string') {
        return done(null, res.body);
      }

      // Attempt to parse as JSON, falling back to parsing as a query string.
      try {
        done(null, JSON.parse(res.body), res.raw);
      } catch (e) {
        done(null, uriDecode(res.body), res.raw);
      }
    });
  };

  if (typeof window !== 'undefined') {
    /**
     * Make a HTTP request using XMLHttpRequest.
     *
     * @param {Object}   options
     * @param {Function} done
     */
    ClientOAuth2.prototype.request = function (options, done) {
      var xhr     = new root.XMLHttpRequest();
      var headers = options.headers || {};
      var body = getBodyRequest(options.data, options.contentType);

      // Open the request to the url and method.
      xhr.open(options.method, options.url);

      // When the request has loaded, attempt to automatically parse the body.
      xhr.onload = function () {
        return done(null, {
          raw:     xhr,
          status:  xhr.status,
          headers: getAllReponseHeaders(xhr),
          body:    xhr.responseText
        });
      };

      // Catch request errors.
      xhr.onerror = xhr.onabort = function () {
        return done(new Error(xhr.statusText || 'XHR aborted'));
      };

      // Set all request headers.
      Object.keys(headers).forEach(function (header) {
        xhr.setRequestHeader(header, headers[header]);
      });

      // Make the request with the body.
      xhr.send(body);
    };
  } else {
    var url   = require('url');
    var http  = require('http');
    var https = require('https');

    /**
     * Make a request using the built-in node http library.
     *
     * @param {Object}   options
     * @param {Function} done
     */
    ClientOAuth2.prototype.request = function (options, done) {
      var lib        = http;
      var reqOptions = url.parse(options.url);

      // If the protocol is over https, switch request library.
      if (reqOptions.protocol === 'https:') {
        lib = https;
      }

      // Alias request options.
      reqOptions.method  = options.method;
      reqOptions.headers = options.headers;

      // Send the http request and listen for the response to finish.
      var req = lib.request(reqOptions, function (res) {
        var data = '';

        // Callback to `done` if a response error occurs.
        res.on('error', done);

        // Concat all the data chunks into a string.
        res.on('data', function (chunk) {
          data += chunk;
        });

        // When the response is finished, attempt to parse the data string.
        res.on('end', function () {
          return done(null, {
            raw:     res,
            status:  res.statusCode,
            headers: res.headers,
            body:    data
          });
        });
      });

      // Callback to `done` if a request error occurs.
      req.on('error', done);

      // Send the body and make the request.
      req.write(options.body);
      req.end();
    };
  }

  /**
   * General purpose client token generator.
   *
   * @param {Object} client
   * @param {Object} data
   */
  function ClientOAuth2Token (client, data) {
    this.client = client;

    this.data = omit(data, [
      'access_token', 'refresh_token', 'token_type', 'expires_in', 'scope',
      'state', 'error', 'error_description', 'error_uri'
    ]);

    this.tokenType    = (data.token_type || 'bearer').toLowerCase();
    this.accessToken  = data.access_token;
    this.refreshToken = data.refresh_token;

    // Set the expiration date.
    if (data.expires_in) {
      this.expires = new Date();

      this.expires.setSeconds(this.expires.getSeconds() + data.expires_in);
    }
  }

  /**
   * Sign a standardised request object with user authentication information.
   *
   * @param  {Object} options
   * @return {Object}
   */
  ClientOAuth2Token.prototype.sign = function (options) {
    if (!this.accessToken) {
      throw new Error('Unable to sign without access token');
    }

    options.headers = options.headers || {};

    if (this.tokenType === 'bearer') {
      options.headers.Authorization = 'Bearer ' + this.accessToken;
    } else {
      var parts    = options.url.split('#');
      var token    = 'access_token=' + this.accessToken;
      var url      = parts[0].replace(/[?&]access_token=[^&#]/, '');
      var fragment = parts[1] ? '#' + parts[1] : '';

      // Prepend the correct query string parameter to the url.
      options.url = url + (url.indexOf('?') > -1 ? '&' : '?') + token + fragment;

      // Attempt to avoid storing the url in proxies, since the access token
      // is exposed in the query parameters.
      options.headers.Pragma           = 'no-store';
      options.headers['Cache-Control'] = 'no-store';
    }

    return options;
  };

  /**
   * Make a HTTP request as the user.
   *
   * @param {Object}   options
   * @param {Function} done
   */
  ClientOAuth2Token.prototype.request = function (options, done) {
    return this.client.client.request(this.sign(options), done);
  };

  /**
   * Refresh a user access token with the supplied token.
   *
   * @param {Function} done
   */
  ClientOAuth2Token.prototype.refresh = function (done) {
    var self    = this;
    var options = this.client.options;

    if (!this.refreshToken) {
      return done(new Error('No refresh token set'));
    }

    var authorization = btoa(options.clientId + ':' + options.clientSecret);

    return this.client._request({
      url: options.accessTokenUri,
      method: 'POST',
      headers: {
        'Accept':        'application/json, application/x-www-form-urlencoded',
        'Content-Type':  'application/x-www-form-urlencoded',
        'Authorization': 'Basic ' + authorization
      },
      data: uriEncode({
        refresh_token: this.refreshToken,
        grant_type:    'refresh_token'
      })
    }, function (err, data) {
      // If an error exists or the data contains an error, return `done`.
      if (err || (err = getAuthError(data))) {
        return done(err);
      }

      // Update stored tokens on the current instance.
      self.accessToken  = data.access_token;
      self.refreshToken = data.refresh_token;

      return done(null, self);
    });
  };

  /**
   * Check whether the token has expired.
   *
   * @return {Boolean}
   */
  ClientOAuth2Token.prototype.expired = function () {
    if (this.expires) {
      return Date.now() < this.expires.getTime();
    }

    return false;
  };

  /**
   * Support resource owner password credentials OAuth 2.0 grant.
   *
   * Reference: http://tools.ietf.org/html/rfc6749#section-4.3
   *
   * @param {ClientOAuth2} client
   */
  function OwnerFlow (client) {
    this.client = client;
  }

  /**
   * Make a request on behalf of the user credentials to get an acces token.
   *
   * @param {String}   username
   * @param {String}   password
   * @param {Function} done
   */
  OwnerFlow.prototype.getToken = function (username, password, done) {
    var self          = this;
    var options       = this.client.options;
    var authorization = btoa(options.clientId + ':' + options.clientSecret);

    return this.client._request({
      url: options.accessTokenUri,
      method: 'POST',
      headers: {
        'Accept':        'application/json, application/x-www-form-urlencoded',
        'Content-Type':  'application/x-www-form-urlencoded',
        'Authorization': 'Basic ' + authorization
      },
      data: uriEncode({
        scope:      sanitizeScope(options.scopes),
        username:   username,
        password:   password,
        grant_type: 'password'
      })
    }, function (err, data) {
      // If an error exists or the data contains an error, return `done`.
      if (err || (err = getAuthError(data))) {
        return done(err);
      }

      return done(null, new ClientOAuth2Token(self, data));
    });
  };

  /**
   * Support implicit OAuth 2.0 grant.
   *
   * Reference: http://tools.ietf.org/html/rfc6749#section-4.2
   *
   * @param {ClientOAuth2} client
   */
  function TokenFlow (client) {
    this.client = client;
  }

  /**
   * Get the uri to redirect the user to for implicit authentication.
   *
   * @param  {Object} options
   * @return {String}
   */
  TokenFlow.prototype.getUri = function (options) {
    options = assign({}, this.client.options, options);

    // Check the parameters have been set.
    expects(options, [
      'clientId',
      'redirectUri',
      'authorizationUri'
    ]);

    return options.authorizationUri + '?' + uriEncode({
      state:         options.state,
      scope:         sanitizeScope(options.scopes),
      client_id:     options.clientId,
      redirect_uri:  options.redirectUri,
      response_type: 'token'
    });
  };

  /**
   * Get the user access token from the url.
   *
   * @param {String}   url
   * @param {String}   [state]
   * @param {Function} done
   */
  TokenFlow.prototype.getToken = function (url, state, done) {
    var options = this.client.options;
    var err;

    // State is an optional pass in.
    if (typeof state === 'function') {
      done  = state;
      state = null;
    }

    // Make sure the url matches our expected redirect url.
    if (url.substr(0, options.redirectUri.length) !== options.redirectUri) {
      return done(new Error('Invalid url (should to match redirect): ' + url));
    }

    var queryString    = url.replace(/^[^\?]*|\#.*$/g, '').substr(1);
    var fragmentString = url.replace(/^[^\#]*/, '').substr(1);

    // Check whether a query string is present in the url.
    if (!queryString && !fragmentString) {
      return done(new Error('Unable to process url: ' + url));
    }

    // Merge the fragment with the the query string. This is because, at least,
    // Instagram has a bug where the OAuth 2.0 state is being passed back as
    // part of the query string instead of the fragment. For example:
    // "?state=123#access_token=abc"
    var data = assign(
      queryString ? uriDecode(queryString) : {},
      fragmentString ? uriDecode(fragmentString) : {}
    );

    // Check if the query string was populated with a known error.
    if (err = getAuthError(data)) {
      return done(err);
    }

    // Check whether the state is correct.
    if (state && data.state !== state) {
      return done(new Error('Invalid state:' + data.state));
    }

    // Initalize a new token and return.
    return done(null, new ClientOAuth2Token(this, data));
  };

  /**
   * Support client credentials OAuth 2.0 grant.
   *
   * Reference: http://tools.ietf.org/html/rfc6749#section-4.4
   *
   * @param {ClientOAuth2} client
   */
  function CredentialsFlow (client) {
    this.client = client;
  }

  /**
   * Request an access token using the client credentials.
   *
   * @param {Object}   [options]
   * @param {Function} done
   */
  CredentialsFlow.prototype.getToken = function (options, done) {
    var self = this;

    // Allow the options argument to be omitted.
    if (typeof options === 'function') {
      done = options;
      options = null;
    }

    options = assign({}, this.client.options, options);

    expects(options, [
      'clientId',
      'clientSecret',
      'accessTokenUri'
    ]);

    // Base64 encode the client id and secret for the Authorization header.
    var authorization = btoa(options.clientId + ':' + options.clientSecret);

    return this.client._request({
      url: options.accessTokenUri,
      method: 'POST',
      headers: {
        'Accept':        'application/json, application/x-www-form-urlencoded',
        'Content-Type':  'application/x-www-form-urlencoded',
        'Authorization': 'Basic ' + authorization
      },
      data: uriEncode({
        scope:      sanitizeScope(options.scopes),
        grant_type: 'client_credentials'
      })
    }, function (err, data) {
      // If an error exists or the data contains an error, return `done`.
      if (err || (err = getAuthError(data))) {
        return done(err);
      }

      return done(null, new ClientOAuth2Token(self, data));
    });
  };

  /**
   * Support authorization code OAuth 2.0 grant.
   *
   * Reference: http://tools.ietf.org/html/rfc6749#section-4.1
   *
   * @param {ClientOAuth2} client
   */
  function CodeFlow (client) {
    this.client = client;
  }

  /**
   * Generate the uri for doing the first redirect.
   *
   * @return {String}
   */
  CodeFlow.prototype.getUri = function (options) {
    options = assign({}, this.client.options, options);

    // Check the parameters have been set.
    expects(options, [
      'clientId',
      'redirectUri',
      'authorizationUri'
    ]);

    return options.authorizationUri + '?' + uriEncode({
      state:         options.state,
      scope:         sanitizeScope(options.scopes),
      client_id:     options.clientId,
      redirect_uri:  options.redirectUri,
      response_type: 'code'
    });
  };

  /**
   * Get the code token from the redirected uri and make another request for
   * the user access token.
   *
   * @param {String}   url
   * @param {String}   [state]
   * @param {Function} done
   */
  CodeFlow.prototype.getToken = function (url, state, done) {
    var self    = this;
    var options = this.client.options;
    var err;

    // State is an optional pass in.
    if (typeof state === 'function') {
      done  = state;
      state = null;
    }

    expects(options, [
      'clientId',
      'clientSecret',
      'redirectUri',
      'accessTokenUri'
    ]);

    // Make sure the url matches our expected redirect url.
    if (url.substr(0, options.redirectUri.length) !== options.redirectUri) {
      return done(new Error('Invalid url (should to match redirect): ' + url));
    }

    // Extract the query string from the url.
    var queryString = url.replace(/^[^\?]*|\#.*$/g, '').substr(1);

    // Check whether a query string is present in the url.
    if (!queryString) {
      return done(new Error('Unable to process url: ' + url));
    }

    var query = uriDecode(queryString);

    // Check if the query string was populated with a known error.
    if (err = getAuthError(query)) {
      return done(err);
    }

    // Check whether the state is correct.
    if (state && query.state !== state) {
      return done(new Error('Invalid state:' + query.state));
    }

    // Check whether the response code is set.
    if (!query.code) {
      return done(new Error('Missing code, unable to request token'));
    }

    return this.client._request({
      url: options.accessTokenUri,
      method: 'POST',
      headers: {
        'Accept':       'application/json, application/x-www-form-urlencoded',
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      data: uriEncode({
        code:          query.code,
        grant_type:    'authorization_code',
        redirect_uri:  options.redirectUri,
        client_id:     options.clientId,
        client_secret: options.clientSecret
      })
    }, function (err, data, raw) {
      // If an error exists or the data contains an error, return `done`.
      if (err || (err = getAuthError(data))) {
        return done(err, null, raw);
      }

      return done(null, new ClientOAuth2Token(self, data), raw);
    });
  };

  /**
   * Export the OAuth2 client for multiple environments.
   */
  if (typeof define === 'function' && define.amd) {
    define([], function () {
      return ClientOAuth2;
    });
  } else if (typeof exports === 'object') {
    module.exports = ClientOAuth2;
  } else {
    root.ClientOAuth2 = ClientOAuth2;
  }
})(this);

(function (root) {
  /**
   * Check if a value is empty.
   *
   * @param  {*}       value
   * @return {Boolean}
   */
  var isEmpty = function (value) {
    return value == null;
  };

  /**
   * Convert a value into a boolean.
   *
   * @param  {String}  value
   * @return {Boolean}
   */
  var toBoolean = function (value) {
    return [0, false, '', '0', 'false'].indexOf(value) === -1;
  };

  /**
   * Convert a value into a number. Non-number strings and infinite values will
   * sanitize into `NaN`.
   *
   * @param  {String} value
   * @return {Number}
   */
  var toNumber = function (value) {
    return isFinite(value) ? Number(value) : null;
  };

  /**
   * Convert a value into an integer. Use strict sanitization - if something is
   * not an integer, return `NaN`.
   *
   * @param  {String} value
   * @return {Number}
   */
  var toInteger = function (value) {
    return value % 1 === 0 ? Number(value) : null;
  };

  /**
   * Convert a value into a date.
   *
   * @param  {String} value
   * @return {Date}
   */
  var toDate = function (value) {
    return !isNaN(Date.parse(value)) ? new Date(value) : null;
  };

  /**
   * Returns the passed value unchanged.
   */
  var returnValue = function (value) {
    return value;
  }

  var toUnion = function (value, key, object, configs) {
    var any = null;
    configs.forEach(function (config) {
      config.unionTypes.forEach(function (type) {
        any = any || TYPES[type](value, key, object, configs);
      });
    });

    return any;
  }

  function isNativeType(typeName) {
    typeName = typeName.replace('[]', '');
    var nativeTypes = [
      'object',
      'string',
      'number',
      'integer',
      'boolean',
      'date-only',
      'time-only',
      'datetime-only',
      'datetime',
      'file',
      'array'
    ];
    return nativeTypes.indexOf(typeName) !== -1;
  }

  function convertType(config) {
    var newConfig = {};
    // Clone config object.
    Object.keys(config).forEach(function (key) {
      newConfig[key] = config[key];
    });

    if (Array.isArray(newConfig.type)) {
      newConfig.type = newConfig.type.map(function (aType) {
        var newType = aType.replace('[]', '');
        var parts = aType.split('|');
        if (parts.length > 1) {
          newType = 'union';
          newConfig.unionTypes = parts.map(function (part) {
            part = part.trim();
            return !isNativeType(part) ? 'object' : part;
          });
        } else {
          newType = !isNativeType(newType) ? 'object' : newType;
        }
        return newType;
      });
    }
    return newConfig;
  }

  /**
   * Convert the schema config into a single sanitization function.
   *
   * @param  {Object}   configs
   * @param  {Object}   rules
   * @param  {Object}   types
   * @return {Function}
   */
  var toSanitization = function (configs, rules, types) {
    configs = Array.isArray(configs) ? configs : [configs];

    // Map configurations into function sanitization chains.
    var sanitizations = configs.map(function (config) {
      var fns = [];

      // Push type sanitization first.
      if (typeof types[config.type] === 'function') {
        fns.push(types[config.type]);
      }

      // Iterate over the schema configuration and push sanitization functions
      // into the sanitization array.
      Object.keys(config).filter(function (rule) {
        return rule !== 'type' && rule !== 'repeat' && rule !== 'default';
      }).forEach(function (rule) {
        if (typeof rules[rule] === 'function') {
          fns.push(rules[rule](config[rule], rule, config));
        }
      });

      /**
       * Sanitize a single value using the function chain. Breaks when any value
       * returns an empty value (`null` or `undefined`).
       *
       * @param  {*}      value
       * @param  {String} key
       * @param  {Object} object
       * @return {*}
       */
      var sanitize = function (value, key, object) {
        // Iterate over each sanitization function and return a single value.
        fns.every(function (fn) {
          value = fn(value, key, object, configs);

          // Break when the value returns `null`.
          return value != null;
        });

        return value;
      };

      /**
       * Do the entire sanitization flow using the current config.
       *
       * @param  {*}      value
       * @param  {String} key
       * @param  {Object} object
       * @return {*}
       */
      return function sanitization (value, key, object) {
        // Immediately return empty values with attempting to sanitize.
        if (isEmpty(value)) {
          // Fallback to providing the default value instead.
          if (config["default"] != null) {
            return sanitization(config["default"], key, object);
          }

          // Return an empty array for repeatable values.
          return config.repeat && !config.required ? [] : value;
        }

        // Support repeated parameters as arrays.
        if (config.repeat) {
          // Turn the result into an array
          if (!Array.isArray(value)) {
            value = [value];
          }

          // Map every value to be sanitized into a new array.
          value = value.map(function (value) {
            return sanitize(value, key, object);
          });

          // If any of the values are empty, refuse the sanitization.
          return value.some(isEmpty) ? null : value;
        }

        return sanitize(value, key, object);
      };
    });

    /**
     * Pass in a value to be sanitized.
     *
     * @param  {*}      value
     * @param  {String} key
     * @param  {Object} object
     * @return {*}
     */
    return function (value, key, object) {
      var result = value;

      // Iterate over each sanitization until one is not empty.
      sanitizations.some(function (sanitization) {
        var sanitized = sanitization(value, key, object);

        // If the value was accepted and sanitized, return it.
        if (sanitized != null) {
          // Assign the sanitized value to the result.
          result = sanitized;

          return true;
        }

        return false;
      });

      return result;
    };
  };

  var TYPES = {
    string:          String,
    number:          toNumber,
    integer:         toInteger,
    'boolean':       toBoolean,
    date:            toDate,
    'date-only':     toDate,
    'time-only':     toDate,
    'datetime-only': toDate,
    'datetime':      toDate,
    object:          returnValue,
    union:           toUnion
  };

  /**
   * Every time the module is exported and executed, we return a new instance.
   *
   * @return {Function}
   */
  RAMLSanitize = function () {
    /**
     * Return a sanitization function based on the passed in schema.
     *
     * @param  {Object}   schema
     * @return {Function}
     */
    var sanitize = function (schema) {
      var sanitizations = {};

      // Map each parameter in the schema to a validation function.
      Object.keys(schema).forEach(function (param) {
        var config = convertType(schema[param]);
        var types  = sanitize.TYPES;
        var rules  = sanitize.RULES;

        sanitizations[param] = toSanitization(config, rules, types);
      });

      /**
       * Execute the returned function with a model to return a sanitized object.
       *
       * @param  {Object} model
       * @return {Object}
       */
      return function (model) {
        model = model || {};

        // Create a new model instance to be sanitized without any additional
        // properties or overrides occuring.
        var sanitized = {};

        // Iterate only the sanitized parameters to get a clean model.
        Object.keys(sanitizations).forEach(function (param) {
          var value    = model[param];
          var sanitize = sanitizations[param];

          // Ensure the value is a direct property on the model object before
          // sanitizing. The keeps model handling in sync with expectations.
          if (Object.prototype.hasOwnProperty.call(model, param)) {
            sanitized[param] = sanitize(value, param, model);
          }
        });

        return sanitized;
      };
    };

    /**
     * Provide sanitization based on types.
     *
     * @type {Object}
     */
    sanitize.TYPES = TYPES;

    /**
     * Provide sanitization based on rules.
     *
     * @type {Object}
     */
    sanitize.RULES = {};

    return sanitize;
  };

  /**
   * Export the raml-sanitize for multiple environments.
   */
  if (typeof define === 'function' && define.amd) {
    define([], function () {
      return RAMLSanitize;
    });
  } else if (typeof exports === 'object') {
    module.exports = RAMLSanitize;
  } else {
    root.RAMLSanitize = RAMLSanitize;
  }
})(this);

(function (root) {
  /**
   * `Object.prototype.toString` as a function.
   *
   * @type {Function}
   */
  var toString = Function.prototype.call.bind(Object.prototype.toString);

  /**
   * Check the value is a valid date.
   *
   * @param  {Date}    check
   * @return {Boolean}
   */
  var isDate = function (check) {
    return toString(check) === '[object Date]' && !isNaN(check.getTime());
  };

  /**
   * Check if the value is a boolean.
   *
   * @param  {Boolean}  check
   * @return {Boolean}
   */
  var isBoolean = function (check) {
    return typeof check === 'boolean';
  };

  /**
   * Check the value is a string.
   *
   * @param  {String}  check
   * @return {Boolean}
   */
  var isString = function (check) {
    return typeof check === 'string';
  };

  /**
   * Check if the value is an integer.
   *
   * @param  {Number}  check
   * @return {Boolean}
   */
  var isInteger = function (check) {
    return typeof check === 'number' && check % 1 === 0;
  };

  /**
   * Check if the value is a number.
   *
   * @param  {Number}  check
   * @return {Boolean}
   */
  var isNumber = function (check) {
    return typeof check === 'number' && isFinite(check);
  };

  /**
   * Check if the value is a JSON string.
   *
   * @param  {String}  check
   * @return {Boolean}
   */
  var isJSON = function (check) {
    try {
      JSON.parse(check);
      return true;
    } catch(e) {
      return false;
    }
  };

  var isUnion = function (check, key, object, configs) {
    var any = false;
    configs.forEach(function (config) {
      config.unionTypes.forEach(function (type) {
        any = any || TYPES[type](check, key, object, configs);
      });
    });

    return any;
  };

  /**
   * Check a number is not smaller than the minimum.
   *
   * @param  {Number}   min
   * @return {Function}
   */
  var isMinimum = function (min) {
    return function (check) {
      return check >= min;
    };
  };

  /**
   * Check a number doesn't exceed the maximum.
   *
   * @param  {Number}  max
   * @return {Boolean}
   */
  var isMaximum = function (max) {
    return function (check) {
      return check <= max;
    };
  };

  /**
   * Check a string is not smaller than a minimum length.
   *
   * @param  {Number}  min
   * @return {Boolean}
   */
  var isMinimumLength = function (min) {
    return function (check) {
      return check.length >= min;
    };
  };

  /**
   * Check a string does not exceed a maximum length.
   *
   * @param  {Number}  max
   * @return {Boolean}
   */
  var isMaximumLength = function (max) {
    return function (check) {
      return check.length <= max;
    };
  };

  /**
   * Check a value is equal to anything in an array.
   *
   * @param  {Array}    values
   * @return {Function}
   */
  var isEnum = function (values) {
    return function (check) {
      return values.indexOf(check) > -1;
    };
  };

  /**
   * Check if a pattern matches the value.
   *
   * @param  {(String|RegExp)} pattern
   * @return {Function}
   */
  var isPattern = function (pattern) {
    if (toString(pattern) !== '[object RegExp]') {
      pattern = new RegExp(pattern);
    }

    return pattern.test.bind(pattern);
  };

  /**
   * Convert arguments into an object.
   *
   * @param  {Boolean} valid
   * @param  {String}  rule
   * @param  {*}       value
   * @param  {String}  key
   * @return {Object}
   */
  var toValidationObject = function (valid, rule, value, key) {
    return { valid: valid, rule: rule, value: value, key: key };
  };

  /**
   * Convert a single config into a function.
   *
   * @param  {Object}   config
   * @param  {Object}   rules
   * @return {Function}
   */
  var toValidationFunction = function (config, rules) {
    var fns = [];

    // Iterate over all of the keys and dynamically push validation rules.
    Object.keys(config).forEach(function (rule) {
      if (rules.hasOwnProperty(rule)) {
        fns.push([rule, rules[rule](config[rule], rule)]);
      }
    });

    /**
     * Run every validation that has been attached.
     *
     * @param  {String} value
     * @param  {String} value
     * @param  {Object} object
     * @return {Object}
     */
    return function (value, key, object) {
      // Run each of the validations returning early when something fails.
      for (var i = 0; i < fns.length; i++) {
        var valid = fns[i][1](value, key, object);

        if (!valid) {
          return toValidationObject(false, fns[i][0], value, key);
        }
      }

      return toValidationObject(true, null, value, key);
    };
  };

  /**
   * Convert a rules object into a simple validation function.
   *
   * @param  {Object}   configs
   * @param  {Object}   rules
   * @param  {Object}   types
   * @return {Function}
   */
  var toValidation = function (configs, rules, types) {
    // Initialize the configs to an array if they aren't already.
    configs = Array.isArray(configs) ? configs : [configs];

    var isOptional        = !configs.length;
    var simpleValidations = [];
    var repeatValidations = [];

    // Support multiple type validations.
    configs.forEach(function (config) {
      var validation = [config.type, toValidationFunction(config, rules)];

      // Allow short-circuiting of non-required values.
      if (!config.required) {
        isOptional = true;
      }

      // Push validations into each stack depending on the "repeat".
      if (config.repeat) {
        repeatValidations.push(validation);
      } else {
        simpleValidations.push(validation);
      }
    });

    /**
     * Validate a value based on "type" and "repeat".
     *
     * @param  {*}      value
     * @param  {String} key
     * @param  {Object} object
     * @return {Object}
     */
    return function (value, key, object) {
      // Short-circuit validation if the value is `null`.
      if (value == null) {
        return toValidationObject(isOptional, 'required', value, key);
      }

      // Switch validation type depending on if the value is an array or not.
      var isArray = Array.isArray(value);

      // Select the validation stack to use based on the (repeated) value.
      var values      = isArray ? value : [value];
      var validations = isArray ? repeatValidations : simpleValidations;

      // Set the initial response to be an error.
      var response = toValidationObject(
        false, validations.length ? 'type' : 'repeat', value, key
      );

      // Iterate over each value and test using type validation.
      validations.some(function (validation) {
        // Non-existant types should always be invalid.
        if (!types.hasOwnProperty(validation[0])) {
          return false;
        }

        // Check all the types match. If they don't, attempt another validation.
        var isType = values.every(function (value) {
          return types[validation[0]](value, key, object, configs);
        });

        // Skip to the next check if not all types match.
        if (!isType) {
          return false;
        }

        // When every value is the correct type, run the validation on each value
        // and break the loop if we get a failure.
        values.every(function (value) {
          return (response = validation[1](value, key, object)).valid;
        });

        // Always break the loop when the type was successful. If anything has
        // failed, `response` will have been set to the invalid object.
        return true;
      });

      return response;
    };
  };

  function isNativeType(typeName) {
    typeName = typeName.replace('[]', '');
    var nativeTypes = [
      'object',
      'string',
      'number',
      'integer',
      'boolean',
      'date-only',
      'time-only',
      'datetime-only',
      'datetime',
      'file',
      'array'
    ];
    return nativeTypes.indexOf(typeName) !== -1;
  }

  function convertType(config) {
    var newConfig = {};
    // Clone config object.
    Object.keys(config).forEach(function (key) {
      newConfig[key] = config[key];
    });

    if (Array.isArray(newConfig.type)) {
      newConfig.type = newConfig.type.map(function (aType) {
        var newType = aType.replace('[]', '');
        var parts = aType.split('|');
        if (parts.length > 1) {
          newType = 'union';
          newConfig.unionTypes = parts.map(function (part) {
            part = part.trim();
            return !isNativeType(part) ? 'object' : part;
          });
        } else {
          newType = !isNativeType(newType) ? 'object' : newType;
        }
        return newType;
      });
    }
    return newConfig;
  }

  var TYPES = {
    date:            isDate,
    'date-only':     isDate,
    'time-only':     isDate,
    'datetime-only': isDate,
    datetime:        isDate,
    number:          isNumber,
    integer:         isInteger,
    'boolean':       isBoolean,
    string:          isString,
    object:          isJSON,
    union:           isUnion
  };

  /**
   * Every time you require the module you're expected to call it as a function
   * to create a new instance. This is to ensure two modules can't make competing
   * changes with their own validation rules.
   *
   * @return {Function}
   */
  RAMLValidate = function () {
    /**
     * Return a validation function that validates a model based on the schema.
     *
     * @param  {Object}   schema
     * @return {Function}
     */
    var validate = function (schema) {
      var validations = {};

      // Convert all parameters into validation functions.
      Object.keys(schema).forEach(function (param) {
        var config = convertType(schema[param]);
        var rules  = validate.RULES;
        var types  = validate.TYPES;

        validations[param] = toValidation(config, rules, types);
      });

      /**
       * The function accepts an object to be validated. All rules are already
       * precompiled.
       *
       * @param  {Object}  model
       * @return {Boolean}
       */
      return function (model) {
        model = model || {};

        // Map all validations to their object and filter for failures.
        var errors = Object.keys(validations).map(function (param) {
          var value      = model[param];
          var validation = validations[param];

          // Return the validation result.
          return validation(value, param, model);
        }).filter(function (validation) {
          return !validation.valid;
        });

        return {
          valid:  errors.length === 0,
          errors: errors
        };
      };
    };

    /**
     * Provide validation of types.
     *
     * @type {Object}
     */
    validate.TYPES = TYPES;

    /**
     * Provide overridable validation of parameters.
     *
     * @type {Object}
     */
    validate.RULES = {
      minimum:   isMinimum,
      maximum:   isMaximum,
      minLength: isMinimumLength,
      maxLength: isMaximumLength,
      'enum':    isEnum,
      pattern:   isPattern
    };

    /**
     * Return the validate function.
     */
    return validate;
  };

  /**
   * Export the raml-validate for multiple environments.
   */
  if (typeof define === 'function' && define.amd) {
    define([], function () {
      return RAMLValidate;
    });
  } else if (typeof exports === 'object') {
    module.exports = RAMLValidate;
  } else {
    root.RAMLValidate = RAMLValidate;
  }
})(this);

angular.module('ramlConsoleApp').run(['$templateCache', function($templateCache) {
  'use strict';

  $templateCache.put('directives/close-button.tpl.html',
    "<button class=\"raml-console-resource-close-btn\" ng-click=\"close($event)\">\n" +
    "  Close\n" +
    "</button>\n"
  );


  $templateCache.put('directives/documentation.tpl.html',
    "<div class=\"raml-console-resource-panel-primary\" ng-if=\"documentationEnabled\">\n" +
    "  <!-- Request -->\n" +
    "  <header class=\"raml-console-resource-header\">\n" +
    "    <h3 class=\"raml-console-resource-head\">\n" +
    "      Request\n" +
    "    </h3>\n" +
    "  </header>\n" +
    "  <div id=\"request-documentation\" class=\"raml-console-resource-panel-primary-row raml-console-resource-panel-content raml-console-is-active\" ng-class=\"{'raml-console-is-active':showRequestDocumentation}\">\n" +
    "    <h3 class=\"raml-console-resource-heading-a\">Description</h3>\n" +
    "\n" +
    "    <p markdown=\"methodInfo.description\" class=\"raml-console-marked-content\"></p>\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section\" id=\"docs-uri-parameters\" ng-if=\"resource.uriParametersForDocumentation\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">URI Parameters</h3>\n" +
    "      <properties list=\"resource.uriParametersForDocumentation\" show-examples=\"true\"></properties>\n" +
    "    </section>\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section\" id=\"docs-headers\" ng-if=\"methodInfo.headers.plain\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">Headers</h3>\n" +
    "      <properties list=\"methodInfo.headers.plain\" show-examples=\"true\"></properties>\n" +
    "    </section>\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section\" id=\"docs-query-parameters\" ng-if=\"methodInfo.queryParameters\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">Query Parameters</h3>\n" +
    "      <properties list=\"methodInfo.queryParameters\" show-examples=\"true\"></properties>\n" +
    "    </section>\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section\" id=\"docs-query-string\" ng-if=\"methodInfo.queryString\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">Query String</h3>\n" +
    "      <type-properties type=\"methodInfo.queryString\"></type-properties>\n" +
    "    </section>\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section raml-console-documentation-schemes\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">Security Schemes</h3>\n" +
    "      <ol class=\"raml-console-documentation-security-scheme\">\n" +
    "        <li class=\"raml-console-documentation-scheme\" ng-class=\"{'raml-console-is-active':isSchemeSelected(value)}\" ng-click=\"selectDocumentationScheme(value)\" ng-repeat=\"(key, value) in securitySchemes\">{{value.name}}</li>\n" +
    "      </ol>\n" +
    "\n" +
    "      <p ng-if\"documentationSchemeSelected.description\" markdown=\"documentationSchemeSelected.description\" class=\"raml-console-marked-content\"></p>\n" +
    "\n" +
    "      <section class=\"raml-console-resource-section raml-console-scheme-headers\" ng-if=\"documentationSchemeSelected.describedBy.headers\">\n" +
    "        <h4 class=\"raml-console-resource-heading-a\">Headers</h4>\n" +
    "        <properties list=\"documentationSchemeSelected.describedBy.headers\" show-examples=\"true\"></properties>\n" +
    "      </section>\n" +
    "\n" +
    "      <section class=\"raml-console-resource-section raml-console-scheme-query-parameters\" ng-if=\"documentationSchemeSelected.describedBy.queryParameters\">\n" +
    "        <h4 class=\"raml-console-resource-heading-a\">Query Parameters</h4>\n" +
    "        <properties list=\"documentationSchemeSelected.describedBy.queryParameters\" show-examples=\"true\"></properties>\n" +
    "      </section>\n" +
    "\n" +
    "      <section class=\"raml-console-resource-section raml-console-scheme-responses\" ng-if=\"documentationSchemeSelected.describedBy.responses\">\n" +
    "        <h4 class=\"raml-console-resource-heading-a\">Responses</h4>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-param\" ng-repeat=\"(code, info) in documentationSchemeSelected.describedBy.responses\">\n" +
    "          <h4 class=\"raml-console-resource-param-heading\">{{info.code}}</h4>\n" +
    "          <p markdown=\"info.description\" class=\"raml-console-marked-content\"></p>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "\n" +
    "      <section class=\"raml-console-resource-section raml-console-scheme-settings\" ng-if=\"documentationSchemeSelected.settings\">\n" +
    "        <h4 class=\"raml-console-resource-heading-a\">Settings</h4>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-param\" ng-repeat=\"(key, config) in documentationSchemeSelected.settings\">\n" +
    "          <h4 class=\"raml-console-resource-param-heading\">{{key}}</h4>\n" +
    "          <p>{{schemaSettingsDocumentation(config)}}</p>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "    </section>\n" +
    "\n" +
    "\n" +
    "    <section class=\"raml-console-resource-section\" ng-if=\"methodInfo.body\">\n" +
    "      <h3 class=\"raml-console-resource-heading-a\">\n" +
    "        Body\n" +
    "      </h3>\n" +
    "\n" +
    "      <h4 class=\"raml-console-request-body-heading\">\n" +
    "        <span ng-click=\"changeResourceBodyType($event, key)\" ng-class=\"{ 'raml-console-is-active' : bodySelected(key)}\" class=\"raml-console-flag raml-console-body-{{getBodyId(key)}}\" ng-repeat=\"(key, value) in methodInfo.body\">{{key}}</span>\n" +
    "      </h4>\n" +
    "\n" +
    "      <div ng-repeat=\"(key, value) in methodInfo.body\">\n" +
    "        <div ng-if=\"key === currentBodySelected\">\n" +
    "          <section ng-if=\"value.formParameters\">\n" +
    "             <div class=\"raml-console-resource-param\" ng-repeat=\"formParam in value.formParameters\">\n" +
    "              <h4 class=\"raml-console-resource-param-heading\">{{formParam[0].displayName}}<span class=\"raml-console-resource-param-instructional\">{{parameterDocumentation(formParam[0])}}</span></h4>\n" +
    "\n" +
    "              <p markdown=\"formParam[0].description\" class=\"raml-console-marked-content\"></p>\n" +
    "\n" +
    "              <p ng-if=\"formParam[0].example !== undefined\">\n" +
    "                <span class=\"raml-console-resource-param-example\"><b>Example:</b> {{formParam[0].example}}</span>\n" +
    "              </p>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <examples\n" +
    "            ng-if=\"value\"\n" +
    "            example-container=\"value\"\n" +
    "            get-beatified-example-ref=\"getBeatifiedExample\">\n" +
    "          </examples>\n" +
    "\n" +
    "          <raml-body\n" +
    "            ng-if=\"value\"\n" +
    "            body=\"value\"\n" +
    "            get-beatified-example-ref=\"getBeatifiedExample\">\n" +
    "          </raml-body>\n" +
    "        </div>\n" +
    "      </div>\n" +
    "    </section>\n" +
    "  </div>\n" +
    "\n" +
    "  <!-- Response -->\n" +
    "  <div ng-if=\"methodInfo.responseCodes\">\n" +
    "    <header class=\"raml-console-resource-header\">\n" +
    "      <h3 class=\"raml-console-resource-head\">\n" +
    "        Response\n" +
    "      </h3>\n" +
    "    </header>\n" +
    "\n" +
    "    <div class=\"raml-console-resource-response-jump\">\n" +
    "      <ul class=\"raml-console-resource-menu\">\n" +
    "        <li class=\"raml-console-resource-btns raml-console-resource-menu-item\" ng-repeat=\"code in methodInfo.responseCodes\">\n" +
    "          <button ng-click=\"showCodeDetails(code)\" class=\"raml-console-resource-btn raml-console-resource-menu-button raml-console-resource-menu-btn-{{getColorCode(code)}}\" ng-class=\"{ 'raml-console-button-is-active': isActiveCode(code) }\" href=\"#code{{code}}\">{{code}}</button>\n" +
    "        </li>\n" +
    "      </ul>\n" +
    "    </div>\n" +
    "\n" +
    "    <div class=\"raml-console-resource-panel-primary-row raml-console-resource-panel-content raml-console-is-active raml-console-response-container\" ng-class=\"{'raml-console-is-active':showResponseDocumentation}\">\n" +
    "      <section ng-if=\"isActiveCode(code)\" class=\"raml-console-resource-section raml-console-resource-response-section\" ng-repeat=\"code in methodInfo.responseCodes\">\n" +
    "        <a name=\"code{{code}}\"></a>\n" +
    "        <h3 class=\"raml-console-resource-heading-a\">Status {{code}}</h3>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-response\">\n" +
    "          <p markdown=\"methodInfo.responses[code].description\" class=\"raml-console-marked-content\"></p>\n" +
    "        </div>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-response\" ng-if=\"methodInfo.responses[code].headers\">\n" +
    "          <h4 class=\"raml-console-resource-body-heading\">Headers</h4>\n" +
    "          <properties list=\"methodInfo.responses[code].headers\"></properties>\n" +
    "        </div>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-response\" ng-if=\"methodInfo.responses[code].body\">\n" +
    "          <h4 class=\"raml-console-resource-body-heading\">\n" +
    "            Body\n" +
    "            <span\n" +
    "              ng-click=\"changeType($event, key, code)\"\n" +
    "              ng-class=\"{ 'raml-console-is-active': responseInfo[code].currentType === key}\"\n" +
    "              class=\"raml-console-flag\"\n" +
    "              ng-repeat=\"(key, value) in methodInfo.responses[code].body\">\n" +
    "                {{key}}\n" +
    "            </span>\n" +
    "          </h4>\n" +
    "\n" +
    "          <div ng-repeat=\"(key, value) in methodInfo.responses[code].body\">\n" +
    "            <div ng-if=\"responseInfo[code].currentType === key\">\n" +
    "              <examples\n" +
    "                ng-if=\"responseInfo[code] && responseInfo[code].currentType\"\n" +
    "                example-container=\"value\"\n" +
    "                get-beatified-example-ref=\"getBeatifiedExample\">\n" +
    "              </examples>\n" +
    "\n" +
    "              <raml-body\n" +
    "                ng-if=\"responseInfo[code] && responseInfo[code].currentType\"\n" +
    "                body=\"value\"\n" +
    "                get-beatified-example-ref=\"getBeatifiedExample\">\n" +
    "              </raml-body>\n" +
    "            </div>\n" +
    "          </div>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "\n" +
    "    </div>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/examples.tpl.html',
    "<div class=\"raml-console-examples-container\" ng-if=\"examples && examples.length\">\n" +
    "  <span>Examples:</span>\n" +
    "  <span\n" +
    "    class=\"raml-console-flag\"\n" +
    "    ng-class=\"{ 'raml-console-is-active': currentExample === $index }\"\n" +
    "    ng-repeat=\"example in examples\"\n" +
    "    ng-click=\"changeExample($index)\">{{example.name}}</span>\n" +
    "\n" +
    "  <div\n" +
    "      class=\"raml-console-resource-param-instructional\"\n" +
    "      ng-if=\"isXML\">\n" +
    "    Examples are shown as JSON string for demonstrative purposes only. Actual API output will be in a different format.\n" +
    "  </div>\n" +
    "  <pre class=\"raml-console-resource-pre\">\n" +
    "    <code class=\"raml-console-hljs\" hljs source=\"getBeatifiedExample(examples[currentExample].content)\"></code>\n" +
    "  </pre>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/method-list.tpl.html',
    "<div class=\"raml-console-tab-list\">\n" +
    "  <div class=\"raml-console-tab\" ng-repeat=\"method in resource.methods\" ng-click=\"showResource($event, $index)\">\n" +
    "    <span class=\"raml-console-tab-label raml-console-tab-{{method.method}}\">{{method.method.toLocaleUpperCase()}}</span>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/named-parameters.tpl.html',
    "<section>\n" +
    "  <header class=\"raml-console-sidebar-row raml-console-sidebar-subheader\">\n" +
    "    <h4 class=\"raml-console-sidebar-subhead\">{{title}}</h4>\n" +
    "    <button class=\"raml-console-sidebar-add-btn\" ng-click=\"addCustomParameter()\" ng-if=\"enableCustomParameters\"></button>\n" +
    "  </header>\n" +
    "\n" +
    "  <div class=\"raml-console-sidebar-row\">\n" +
    "    <p class=\"raml-console-sidebar-input-container raml-console-sidebar-input-container-custom\" ng-repeat=\"customParam in context.customParameters[type]\">\n" +
    "      <button class=\"raml-console-sidebar-input-delete\" ng-click=\"removeCutomParam(customParam)\"></button>\n" +
    "      <label for=\"custom-header\" class=\"raml-console-sidebar-label raml-console-sidebar-label-custom\">\n" +
    "        <input class=\"raml-console-sidebar-custom-input-for-label\" ng-model=\"customParam.name\" placeholder=\"custom key\">\n" +
    "      </label>\n" +
    "      <input name=\"custom-header\" class=\"raml-console-sidebar-input raml-console-sidebar-input-custom\" placeholder=\"custom value\" ng-model=\"customParam.value\">\n" +
    "    </p>\n" +
    "\n" +
    "    <p ng-show=\"showBaseUrl\" class=\"raml-console-sidebar-method\">{{$parent.methodInfo.method.toUpperCase()}}</p>\n" +
    "    <div ng-show=\"showBaseUrl\" class=\"raml-console-sidebar-method-content\">\n" +
    "      <div class=\"raml-console-sidebar-url\" ng-repeat=\"segment in segments\">\n" +
    "        <div ng-hide=\"segment.templated\">{{segment.name}}</div>\n" +
    "        <div ng-show=\"segment.templated\" ng-if=\"isValueProvided(context[type].values[segment.name][0])\" class=\"raml-console-sidebar-url-segment\">{{cleanupValue(context[type].values[segment.name][0])}}</div>\n" +
    "        <div ng-show=\"segment.templated\" ng-if=\"!isValueProvided(context[type].values[segment.name][0])\" class=\"raml-console-sidebar-url-segment\"><span ng-non-bindable>&#123;</span>{{segment.name}}<span ng-non-bindable>&#125;</span></div>\n" +
    "      </div>\n" +
    "    </div>\n" +
    "\n" +
    "    <p class=\"raml-console-sidebar-input-container\" ng-repeat=\"param in context[type].plain\">\n" +
    "      <span class=\"raml-console-sidebar-input-tooltip-container\" ng-if=\"param.definitions[0].description\" ng-class=\"{'raml-console-sidebar-input-tooltip-container-enum': param.definitions[0].enum}\">\n" +
    "        <button tabindex=\"-1\" class=\"raml-console-sidebar-input-tooltip\"><span class=\"raml-console-visuallyhidden\">Show documentation</span></button>\n" +
    "        <span class=\"raml-console-sidebar-tooltip-flyout\">\n" +
    "          <span markdown=\"param.definitions[0].description\" class=\"raml-console-marked-content\"></span>\n" +
    "        </span>\n" +
    "      </span>\n" +
    "\n" +
    "      <raml-field context=\"context\" type=\"type\" param=\"param.definitions[0]\" model=\"context[type].values[param.definitions[0].id]\"></raml-field>\n" +
    "    </p>\n" +
    "  </div>\n" +
    "</section>\n"
  );


  $templateCache.put('directives/properties.tpl.html',
    "<div>\n" +
    "  <div class=\"raml-console-resource-param\" ng-repeat=\"property in listArray\" ng-if=\"!property[0].isFromSecurityScheme && !isPattern(property[0].displayName)\" ng-init=\"vm.isCollapsed = !!collapsible\">\n" +
    "    <div ng-init=\"type = getType(property[0])\">\n" +
    "      <h4 class=\"raml-console-resource-param-heading\" style=\"position: relative\">\n" +
    "        <span ng-if=\"isCollapsible(type)\" ng-click=\"vm.isCollapsed = !vm.isCollapsed\" style=\"cursor: pointer\">{{ vm.isCollapsed ? '▶' : '▼' }}</span>&nbsp;{{type.displayName}}\n" +
    "        <span ng-if=\"!hidePropertyDetails\" class=\"raml-console-resource-param-instructional\">{{parameterDocumentation(type)}}</span>\n" +
    "        <span ng-if=\"hidePropertyDetails\" class=\"raml-console-resource-param-instructional\">{{typeDocumentation(type)}}</span>\n" +
    "        <span class=\"raml-console-resource-param-instructional\" ng-repeat=\"typeName in type.type\">\n" +
    "          <span ng-if=\"isNativeType(typeName)\">{{typeName}}</span>\n" +
    "          <span ng-if=\"isSchema(typeName)\">Schema</span>\n" +
    "          <span ng-if=\"!isNativeType(typeName) && !isSchema(typeName)\">\n" +
    "            <type type-name=\"typeName\" hide-type-links=\"hideTypeLinks\"></type>\n" +
    "          </span>\n" +
    "        </span>\n" +
    "      </h4>\n" +
    "\n" +
    "      <div ng-if=\"!vm.isCollapsed\">\n" +
    "        <p ng-if=\"type.description\" markdown=\"type.description\" class=\"raml-console-marked-content\"></p>\n" +
    "\n" +
    "        <p ng-if=\"type.example !== undefined && showExamples\">\n" +
    "          <span class=\"raml-console-resource-param-example\"><b>Example:</b> {{type.example}}</span>\n" +
    "        </p>\n" +
    "\n" +
    "        <pre ng-if=\"isSchema(type.type[0])\" class=\"raml-console-resource-pre\">\n" +
    "          <code class=\"raml-console-hljs\" hljs source=\"type.type[0]\"></code>\n" +
    "        </pre>\n" +
    "\n" +
    "        <properties\n" +
    "          style=\"padding-left: 10px; margin-top: 11px;\"\n" +
    "          list=\"type.properties\"\n" +
    "          ng-if=\"type.properties\"\n" +
    "          hide-type-links=\"hideTypeLinks\"\n" +
    "          is-nested-property=\"true\">\n" +
    "        </properties>\n" +
    "      </div>\n" +
    "    </div>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-body.tpl.html',
    "<div class=\"raml-console-schema-container\" ng-if=\"isSchema\">\n" +
    "  <p><button ng-click=\"showSchema($event)\" class=\"raml-console-resource-btn\">Show Schema</button></p>\n" +
    "  <pre class=\"raml-console-resource-pre raml-console-resource-pre-toggle\">\n" +
    "    <code class=\"raml-console-hljs\" hljs source=\"getBeatifiedExample(definition)\"></code>\n" +
    "  </pre>\n" +
    "</div>\n" +
    "\n" +
    "<div class=\"raml-console-schema-container\" ng-if=\"isType\">\n" +
    "  <type-properties type=\"body\"></type-properties>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-client-generator.tpl.html',
    "<div class=\"raml-console-meta-button-container\">\n" +
    "  <a\n" +
    "    class=\"raml-console-meta-button raml-console-meta-button-first\"\n" +
    "    ng-click=\"open = !open\"\n" +
    "    click-outside=\"open = false\">\n" +
    "    Download API Client\n" +
    "  </a>\n" +
    "\n" +
    "  <div class=\"raml-console-meta-button-dropdown\" ng-show=\"open\">\n" +
    "    <a class=\"raml-console-meta-button-dropdown-item\" ng-click=\"downloadJavaScriptClient()\">\n" +
    "      JavaScript\n" +
    "    </a>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-console-loader.tpl.html',
    "<div class=\"raml-console-error-container raml-console-error-primary\">\n" +
    "  <raml-console-spinner ng-if=\"!vm.loaded\"></raml-console-spinner>\n" +
    "\n" +
    "  <raml-console ng-if=\"vm.raml\" raml=\"vm.raml\" options=\"vm.options\"></raml-console>\n" +
    "\n" +
    "  <div ng-if=\"vm.error\">\n" +
    "    <div class=\"raml-console-initializer-container raml-console-initializer-primary\">\n" +
    "      <h1 class=\"raml-console-title\">RAML Console</h1>\n" +
    "\n" +
    "      <section>\n" +
    "        <header class=\"raml-console-initializer-row raml-console-initializer-subheader\">\n" +
    "          <h4 class=\"raml-console-initializer-subhead\">Error while parsing</h4>\n" +
    "        </header>\n" +
    "\n" +
    "        <div class=\"raml-console-initializer-row\">\n" +
    "          <p class=\"raml-console-initializer-input-container\" style=\"height: 550px;\">\n" +
    "            <textarea id=\"raml\" ui-codemirror=\"{\n" +
    "              gutters:      ['CodeMirror-lint-markers'],\n" +
    "              lineNumbers:  true,\n" +
    "              lineWrapping: false,\n" +
    "              lint:         true,\n" +
    "              mode:         'yaml',\n" +
    "              tabSize:      2,\n" +
    "              theme:        'raml-console'\n" +
    "            }\" ng-model=\"vm.error.buffer\"></textarea>\n" +
    "          </p>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "    </div>\n" +
    "\n" +
    "    {{vm.raml}}\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-console-spinner.tpl.html',
    "<div class=\"raml-console-spinner\">\n" +
    "  <div class=\"raml-console-rect1\"></div>\n" +
    "  <div class=\"raml-console-rect2\"></div>\n" +
    "  <div class=\"raml-console-rect3\"></div>\n" +
    "  <div class=\"raml-console-rect4\"></div>\n" +
    "  <div class=\"raml-console-rect5\"></div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-console.tpl.html',
    "<div>\n" +
    "  <div class=\"raml-console-meta-button-group\">\n" +
    "    <theme-switcher ng-if=\"!disableThemeSwitcher\"></theme-switcher>\n" +
    "    <raml-client-generator ng-if=\"!disableRamlClientGenerator\"></raml-client-generator>\n" +
    "  </div>\n" +
    "\n" +
    "  <h1 ng-if=\"!disableTitle\" class=\"raml-console-title\">{{raml.title}}</h1>\n" +
    "\n" +
    "  <root-documentation></root-documentation>\n" +
    "\n" +
    "  <root-types types=\"types\" ng-if=\"types\"></root-types>\n" +
    "\n" +
    "  <ol ng-class=\"{'raml-console-resources-container-no-title': disableTitle, 'raml-console-resources-container': !disableTitle}\" id=\"raml-console-resources-container\" class=\"raml-console-resource-list raml-console-resource-list-root raml-console-resources-resourcesCollapsed\">\n" +
    "    <li id=\"raml_documentation\" class=\"raml-console-resource-list-item raml-console-documentation-header\">\n" +
    "      <div ng-if=\"proxy\" align=\"right\" class=\"raml-console-resource-proxy\">\n" +
    "        <label for=\"raml-console-api-behind-firewall\">API is behind a firewall <a href=\"http://www.mulesoft.org/documentation/display/current/Accessing+Your+API+Behind+a+Firewall\" target=\"_blank\">(?)</a></label>\n" +
    "        <input id=\"raml-console-api-behind-firewall\" type=\"checkbox\" ng-model=\"disableProxy\" ng-change=\"updateProxyConfig(disableProxy)\">\n" +
    "      </div>\n" +
    "      <header class=\"raml-console-resource raml-console-resource-root raml-console-clearfix\">\n" +
    "        <span ng-if=\"hasResourcesWithChilds()\" class=\"raml-console-flag raml-console-resource-heading-flag raml-console-toggle-all\" ng-click=\"collapseAll($event, resourceList, 'resourcesCollapsed')\" ng-class=\"{'raml-console-resources-expanded':!resourcesCollapsed}\">\n" +
    "          <span ng-if=\"!resourcesCollapsed\">collapse</span>\n" +
    "          <span ng-if=\"resourcesCollapsed\">expand</span> all\n" +
    "        </span>\n" +
    "\n" +
    "        <div class=\"raml-console-resource-path-container\">\n" +
    "          <h2 class=\"raml-console-resource-section-title\">\n" +
    "            <span class=\"raml-console-resource-path-active\">Resources</span>\n" +
    "          </h2>\n" +
    "        </div>\n" +
    "        <close-button></close-button>\n" +
    "      </header>\n" +
    "    </li>\n" +
    "\n" +
    "    <li id=\"{{generateId(resource.pathSegments)}}\" class=\"raml-console-resource-list-item\" ng-repeat=\"resourceGroup in inspectedRaml.resourceGroups\">\n" +
    "      <header class=\"raml-console-resource raml-console-resource-root raml-console-clearfix\" ng-class=\"{ 'raml-console-is-active':showPanel }\" ng-init=\"resource = resourceGroup[0]\">\n" +
    "        <div class=\"raml-console-resource-path-container\" ng-init=\"index=$index\" ng-class=\"{'raml-console-resource-with-description': resource.description}\">\n" +
    "          <button class=\"raml-console-resource-root-toggle\" ng-class=\"{'raml-console-is-active': resourceList[$index]}\" ng-if=\"resourceGroup.length > 1\" ng-click=\"toggle($event, index, resourceList, 'resourcesCollapsed')\"></button>\n" +
    "\n" +
    "          <h2 class=\"raml-console-resource-heading raml-console-resource-heading-large\">\n" +
    "            <a ng-if=\"resourceGroup.length > 1\" class=\"raml-console-resource-path-active\" ng-class=\"{'raml-console-resource-heading-hover':resourceGroup.length > 1}\" ng-repeat='segment in resource.pathSegments' ng-click=\"toggle($event, index, resourceList, 'resourcesCollapsed')\">{{segment.toString()}}</a>\n" +
    "\n" +
    "            <a ng-if=\"resourceGroup.length == 1\" style=\"cursor: default;\" class=\"raml-console-resource-path-active\" ng-repeat='segment in resource.pathSegments'>{{segment.toString()}}</a>\n" +
    "          </h2>\n" +
    "\n" +
    "          <resource-type></resource-type>\n" +
    "\n" +
    "          <span ng-if=\"methodInfo.is\" class=\"raml-console-flag raml-console-resource-heading-flag raml-console-resource-trait\"><b>Traits:</b> {{readTraits(methodInfo.is)}}</span>\n" +
    "\n" +
    "          <span ng-hide=\"methodInfo.is\" ng-if=\"resource.traits\" class=\"raml-console-flag raml-console-resource-heading-flag\"><b>Traits:</b> {{readResourceTraits(resource.traits)}}</span>\n" +
    "\n" +
    "          <span class=\"raml-console-resource-level-description raml-console-marked-content\" markdown=\"resource.description\"></span>\n" +
    "\n" +
    "        </div>\n" +
    "        <method-list></method-list>\n" +
    "        <close-button></close-button>\n" +
    "      </header>\n" +
    "\n" +
    "      <resource-panel></resource-panel>\n" +
    "\n" +
    "      <!-- Child Resources -->\n" +
    "      <ol class=\"raml-console-resource-list\" ng-class=\"{'raml-console-is-collapsed': resourcesCollapsed}\">\n" +
    "\n" +
    "        <li id=\"{{generateId(resource.pathSegments)}}\" class=\"raml-console-resource-list-item\" ng-repeat=\"resource in resourceGroup\" ng-if=\"!$first\">\n" +
    "          <div class=\"raml-console-resource raml-console-clearfix\" ng-class=\"{ 'raml-console-is-active':showPanel }\">\n" +
    "            <div class=\"raml-console-resource-path-container\" ng-class=\"{'raml-console-resource-with-description': resource.description}\">\n" +
    "              <h3 class=\"raml-console-resource-heading\" style=\"cursor: default;\">\n" +
    "                <span ng-repeat-start='segment in resource.pathSegments' ng-if=\"!$last\">{{segment.toString()}}</span><span ng-repeat-end ng-if=\"$last\" class=\"raml-console-resource-path-active\">{{segment.toString()}}</span>\n" +
    "              </h3>\n" +
    "\n" +
    "              <resource-type></resource-type>\n" +
    "              <span ng-if=\"methodInfo.is\" class=\"raml-console-flag raml-console-resource-heading-flag raml-console-resource-trait\"><b>Traits:</b> {{readTraits(methodInfo.is)}}</span>\n" +
    "\n" +
    "              <span ng-hide=\"methodInfo.is\" ng-if=\"resource.traits\" class=\"raml-console-flag raml-console-resource-heading-flag\"><b>Traits:</b> {{readResourceTraits(resource.traits)}}</span>\n" +
    "\n" +
    "              <span class=\"raml-console-resource-level-description raml-console-marked-content\" markdown=\"resource.description\"></span>\n" +
    "            </div>\n" +
    "\n" +
    "            <method-list></method-list>\n" +
    "            <close-button></close-button>\n" +
    "          </div>\n" +
    "\n" +
    "          <resource-panel></resource-panel>\n" +
    "        </li>\n" +
    "      </ol>\n" +
    "\n" +
    "    </li>\n" +
    "  </ol>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-field.tpl.html',
    "<div style=\"position: relative;\">\n" +
    "  <label for=\"{{param.id}}\" class=\"raml-console-sidebar-label\">\n" +
    "    {{param.displayName}}\n" +
    "    <a class=\"raml-console-sidebar-override\" ng-if=\"canOverride(param)\" ng-click=\"overrideField($event, param)\">Override</a>\n" +
    "    <span class=\"raml-console-side-bar-required-field\" ng-if=\"param.required\">*</span>\n" +
    "    <label ng-if=\"param.isFromSecurityScheme\" class=\"raml-console-sidebar-security-label\">from security scheme</label>\n" +
    "    <span class=\"raml-console-resource-param-instructional\">{{toString(param.type)}}</span>\n" +
    "  </label>\n" +
    "\n" +
    "  <div ng-if=\"!param.properties && !isArray(param)\">\n" +
    "    <span class=\"raml-console-sidebar-input-tooltip-container raml-console-sidebar-input-left\" ng-if=\"hasExampleValue(param)\">\n" +
    "      <button tabindex=\"-1\" class=\"raml-console-sidebar-input-reset\" ng-click=\"reset(param)\"><span class=\"raml-console-visuallyhidden\">Reset field</span></button>\n" +
    "      <span class=\"raml-console-sidebar-tooltip-flyout-left\">\n" +
    "        <span>Use example value</span>\n" +
    "      </span>\n" +
    "    </span>\n" +
    "\n" +
    "    <select id=\"select_{{param.id}}\" ng-if=\"isEnum(param)\" name=\"param.id\" class=\"raml-console-sidebar-input\" ng-model=\"model[0]\" style=\"margin-bottom: 0;\" ng-change=\"onChange()\">\n" +
    "     <option ng-repeat=\"enum in unique(param.enum)\" value=\"{{enum}}\" ng-selected=\"{{param.example === enum}}\">{{enum}}</option>\n" +
    "    </select>\n" +
    "\n" +
    "    <input id=\"{{param.id}}\" ng-hide=\"!isDefault(param)\" class=\"raml-console-sidebar-input\" ng-model=\"model[0]\" ng-class=\"{'raml-console-sidebar-field-no-default': !hasExampleValue(param)}\" validate=\"param\" dynamic-name=\"param.id\" ng-change=\"onChange()\"/>\n" +
    "\n" +
    "    <input id=\"checkbox_{{param.id}}\" ng-if=\"isBoolean(param)\" class=\"raml-console-sidebar-input\" type=\"checkbox\" ng-model=\"model[0]\" dynamic-name=\"param.id\" ng-change=\"onChange()\" />\n" +
    "\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </div>\n" +
    "\n" +
    "  <div ng-if=\"!param.properties && isArray(param)\" ng-init=\"model[0] = [[undefined]]\">\n" +
    "    <button class=\"raml-console-sidebar-add-btn\" ng-click=\"addArrayElement(model[0])\"></button>\n" +
    "    <div ng-repeat=\"aModel in model[0] track by $index\" style=\"position: relative;\">\n" +
    "      <button class=\"raml-console-sidebar-input-delete\" ng-click=\"removeArrayElement(model[0], $index)\"></button>\n" +
    "      <span class=\"raml-console-sidebar-input-tooltip-container raml-console-sidebar-input-left\" ng-if=\"hasExampleValue(param)\">\n" +
    "        <button tabindex=\"-1\" class=\"raml-console-sidebar-input-reset\" ng-click=\"reset(param)\"><span class=\"raml-console-visuallyhidden\">Reset field</span></button>\n" +
    "        <span class=\"raml-console-sidebar-tooltip-flyout-left\">\n" +
    "          <span>Use example value</span>\n" +
    "        </span>\n" +
    "      </span>\n" +
    "\n" +
    "      <select id=\"select_{{param.id}}\" ng-if=\"isEnum(param)\" name=\"param.id\" class=\"raml-console-sidebar-input\" ng-model=\"aModel[0]\" style=\"margin-bottom: 0;\" ng-change=\"onChange()\">\n" +
    "       <option ng-repeat=\"enum in unique(param.enum)\" value=\"{{enum}}\" ng-selected=\"{{param.example === enum}}\">{{enum}}</option>\n" +
    "      </select>\n" +
    "\n" +
    "      <input id=\"{{param.id}}\" ng-hide=\"!isDefault(param)\" class=\"raml-console-sidebar-input\" ng-model=\"aModel[0]\" validate=\"param\" dynamic-name=\"param.id\" ng-change=\"onChange()\"/>\n" +
    "\n" +
    "      <input id=\"checkbox_{{param.id}}\" ng-if=\"isBoolean(param)\" class=\"raml-console-sidebar-input\" type=\"checkbox\" ng-model=\"aModel[0]\" dynamic-name=\"param.id\" ng-change=\"onChange()\" />\n" +
    "\n" +
    "      <span class=\"raml-console-field-validation-error\"></span>\n" +
    "    </div>\n" +
    "  </div>\n" +
    "\n" +
    "  <div ng-if=\"param.properties\" style=\"padding-left: 10px\">\n" +
    "    <raml-field ng-repeat=\"aParam in param.properties\" context=\"context\" type=\"type\" param=\"aParam[0]\" ng-init=\"model[0][aParam[0].name] = [undefined]\" model=\"model[0][aParam[0].name]\"></raml-field>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/raml-initializer.tpl.html',
    "<div class=\"raml-console-initializer-container raml-console-initializer-primary\">\n" +
    "  <div ng-if=\"!vm.isLoading && !vm.raml\">\n" +
    "    <h1 class=\"raml-console-title\">RAML Console</h1>\n" +
    "\n" +
    "    <div class=\"raml-console-initializer-content-wrapper\">\n" +
    "      <section>\n" +
    "        <header class=\"raml-console-initializer-row raml-console-initializer-subheader\">\n" +
    "          <h4 class=\"raml-console-initializer-subhead\">Initialize from the URL of a RAML file</h4>\n" +
    "        </header>\n" +
    "\n" +
    "        <div class=\"raml-console-initializer-row\">\n" +
    "          <form novalidate ng-submit=\"vm.loadFromUrl(vm.ramlUrl)\">\n" +
    "            <p class=\"raml-console-initializer-input-container\" ng-class=\"{'raml-console-initializer-input-container-error': vm.isLoadedFromUrl && vm.error}\">\n" +
    "              <input id=\"ramlPath\" autofocus class=\"raml-console-initializer-input raml-console-initializer-raml-field\" ng-model=\"vm.ramlUrl\">\n" +
    "            </p>\n" +
    "            <div class=\"raml-console-parser-error\" ng-if=\"vm.isLoadedFromUrl && vm.error\">\n" +
    "              <span>{{ vm.error.message }}</span>\n" +
    "            </div>\n" +
    "            <div class=\"raml-console-initializer-action-group\" align=\"right\">\n" +
    "              <button id=\"loadRamlFromUrl\" class=\"raml-console-initializer-action raml-console-initializer-action-btn\" type=\"submit\">Load from URL</button>\n" +
    "            </div>\n" +
    "          </form>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "\n" +
    "      <section>\n" +
    "        <header class=\"raml-console-initializer-row raml-console-initializer-subheader\">\n" +
    "          <h4 class=\"raml-console-initializer-subhead\">or parse RAML in here</h4>\n" +
    "        </header>\n" +
    "\n" +
    "        <div class=\"raml-console-initializer-row\">\n" +
    "          <p class=\"raml-console-initializer-input-container\" ng-class=\"{'raml-console-initializer-input-container-error': !vm.isLoadedFromUrl && vm.error}\">\n" +
    "            <textarea id=\"raml\" ui-codemirror=\"vm.codeMirror\" ng-model=\"vm.ramlString\"></textarea>\n" +
    "          </p>\n" +
    "          <div class=\"raml-console-initializer-action-group\" align=\"right\">\n" +
    "            <button id=\"loadRaml\" class=\"raml-console-initializer-action raml-console-initializer-action-btn\" ng-click=\"vm.loadFromString(vm.ramlString)\">Load RAML</button>\n" +
    "          </div>\n" +
    "        </div>\n" +
    "      </section>\n" +
    "    </div>\n" +
    "  </div>\n" +
    "\n" +
    "  <raml-console ng-if=\"vm.raml\" raml=\"vm.raml\"></raml-console>\n" +
    "\n" +
    "  <raml-console-spinner ng-if=\"vm.isLoading\"></raml-console-spinner>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/resource-panel.tpl.html',
    "<div class=\"raml-console-resource-panel\" ng-if=\"showPanel\" ng-class=\"{ 'raml-console-has-sidebar-collapsed': singleView }\">\n" +
    "  <div class=\"raml-console-resource-no-baseuri\" ng-hide=\"raml.baseUri\">\n" +
    "    <strong>Try-it</strong> is disabled because <strong>baseUri</strong> is not present\n" +
    "  </div>\n" +
    "  <div class=\"raml-console-resource-panel-wrapper\">\n" +
    "    <documentation></documentation>\n" +
    "\n" +
    "    <sidebar ng-show=\"raml.baseUri\"></sidebar>\n" +
    "\n" +
    "    <div class=\"raml-console-sidebar-controls raml-console-sidebar-controls-collapse\" ng-click=\"collapseSidebar($event)\" style=\"right: -1px; position: absolute;\"ng-hide=\"!raml.baseUri\" ng-if=\"!disableTryIt\">\n" +
    "      <button class=\"raml-console-collapse\" style=\"height: 21px; margin-top: 9px;\">\n" +
    "        <svg style=\"transform: rotate(-180deg); display: inline;\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" x=\"0px\" y=\"0px\" viewBox=\"0 0 612 792\" enable-background=\"new 0 0 612 792\" xml:space=\"preserve\">\n" +
    "          <g id=\"Layer_3\">\n" +
    "            <polygon fill=\"#585961\" points=\"480.9,396 142.1,46.2 142.1,745.8  \"/>\n" +
    "          </g>\n" +
    "        </svg>\n" +
    "        <span class=\"raml-console-discoverable\">Try it</span>\n" +
    "      </button>\n" +
    "    </div>\n" +
    "\n" +
    "    <div class=\"raml-console-sidebar-controls raml-console-sidebar-controls-fullscreen\" ng-click=\"toggleSidebar($event)\" style=\"right: -1px; position: absolute;\">\n" +
    "      <button class=\"raml-console-collapse\" style=\"height: 21px; margin-top: 9px;\">\n" +
    "\n" +
    "        <svg style=\"transform: rotate(-180deg); display: inline;\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" x=\"0px\" y=\"0px\" viewBox=\"0 0 612 792\" enable-background=\"new 0 0 612 792\" xml:space=\"preserve\">\n" +
    "          <g id=\"Layer_3\">\n" +
    "            <polygon fill=\"#585961\" points=\"480.9,396 142.1,46.2 142.1,745.8  \"/>\n" +
    "          </g>\n" +
    "        </svg>\n" +
    "        <span class=\"raml-console-discoverable\">Try it</span>\n" +
    "      </button>\n" +
    "    </div>\n" +
    "  </div>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/resource-type.tpl.html',
    "<span ng-if=\"resource.resourceType\" class=\"raml-console-flag raml-console-resource-heading-flag\"><b>Type:</b> {{resource.resourceType}}</span>\n"
  );


  $templateCache.put('directives/root-documentation.tpl.html',
    "<ol id=\"raml-console-documentation-container\" ng-if=\"raml.documentation\" class=\"raml-console-resource-list raml-console-resource-list-root raml-console-root-documentation raml-console-resources-documentationCollapsed\">\n" +
    "  <li class=\"raml-console-resource-list-item raml-console-documentation-header\" ng-if=\"raml.documentation.length > 0\">\n" +
    "    <header class=\"raml-console-resource raml-console-resource-root raml-console-clearfix\">\n" +
    "      <span ng-if=\"hasDocumentationWithIndex()\" class=\"raml-console-flag raml-console-resource-heading-flag raml-console-toggle-all\" ng-click=\"collapseAll($event, documentList, 'documentationCollapsed')\" ng-class=\"{'raml-console-resources-expanded':!documentationCollapsed}\"><span ng-if=\"!documentationCollapsed\">collapse</span><span ng-if=\"documentationCollapsed\">expand</span> all</span>\n" +
    "      <div class=\"raml-console-resource-path-container\">\n" +
    "        <h2 class=\"raml-console-resource-section-title\">\n" +
    "          <span class=\"raml-console-resource-path-active\">Documentation</span>\n" +
    "        </h2>\n" +
    "      </div>\n" +
    "    </header>\n" +
    "  </li>\n" +
    "\n" +
    "  <li id=\"{{generateDocId(doc.title)}}\" class=\"raml-console-resource-list-item raml-console-documentation\" ng-repeat=\"doc in raml.documentation\">\n" +
    "    <div ng-init=\"content = getMarkdownHeaders(doc.content)\">\n" +
    "      <div class=\"raml-console-resource raml-console-clearfix raml-console-document-header\">\n" +
    "        <div class=\"raml-console-resource-path-container\" style=\"padding-top: 11px;\" ng-init=\"index=$index\">\n" +
    "          <h3 class=\"raml-console-resource-heading\">\n" +
    "            <button class=\"raml-console-resource-root-toggle\" ng-if=\"content.length > 0\" ng-click=\"toggle($event, index, documentList, 'documentationCollapsed')\" ng-class=\"{'raml-console-is-active': documentList[index]}\"></button>\n" +
    "            <span class=\"raml-console-resource-path-active raml-console-document-heading\" ng-click=\"toggleSection($event, 'all', doc.title)\">{{doc.title}}</span>\n" +
    "          </h3>\n" +
    "          <select ng-if=\"content.length > 0\" ng-model=\"selectedSection\" ng-if=\"documentationEnabled\" class=\"raml-console-document-section-selector\" ng-change=\"sectionChange(selectedSection)\">\n" +
    "            <option value=\"all\">-- choose a section --</option>\n" +
    "            <option ng-repeat=\"header in filterHeaders(content)\" value=\"{{header.value}}\" ng-selected=\"header.value == selectedDocumentSection\">{{header.label}}</option>\n" +
    "          </select>\n" +
    "        </div>\n" +
    "        <button class=\"raml-console-resource-close-btn\" ng-click=\"closeDocumentation($event)\">\n" +
    "          Close\n" +
    "        </button>\n" +
    "      </div>\n" +
    "\n" +
    "      <div class=\"raml-console-resource-panel raml-console-documentation-content\" ng-if=\"documentationEnabled\">\n" +
    "        <div class=\"raml-console-resource-panel-wrapper\">\n" +
    "          <div class=\"raml-console-documentation-section-content raml-console-marked-content\" markdown=\"getDocumentationContent(doc.content, selectedDocumentSection)\"></div>\n" +
    "        </div>\n" +
    "      </div>\n" +
    "\n" +
    "      <ol class=\"raml-console-resource-list raml-console-documentation-contents\" ng-if=\"content.length > 0\" ng-class=\"{'raml-console-is-collapsed': documentationCollapsed}\">\n" +
    "        <li ng-repeat=\"header in content\" class=\"raml-console-resource-list-item\">\n" +
    "           <div class=\"raml-console-resource raml-console-clearfix raml-console-documentation-clearfix\">\n" +
    "            <div class=\"raml-console-resource-path-container raml-console-documentation-path-container\">\n" +
    "              <h3 class=\"raml-console-resource-heading raml-console-documentation-heading raml-console-md-heading-{{header.heading}}\">\n" +
    "                <div class=\"raml-console-resource-path-active\">\n" +
    "                  <div class=\"raml-consoledocumentation-title raml-console-document-heading\" ng-click=\"toggleSection($event, header.value, doc.title)\">{{header.label}}</div>\n" +
    "                </div>\n" +
    "              </h3>\n" +
    "            </div>\n" +
    "          </div>\n" +
    "        </li>\n" +
    "      </ol>\n" +
    "    </div>\n" +
    "  </li>\n" +
    "</ol>\n"
  );


  $templateCache.put('directives/root-types.tpl.html',
    "<ol id=\"raml-console-documentation-container\" class=\"raml-console-resource-list raml-console-resource-list-root raml-console-root-documentation raml-console-resources-documentationCollapsed\" ng-init=\"vm.isCollapsed = true\">\n" +
    "  <li class=\"raml-console-resource-list-item raml-console-documentation-header\">\n" +
    "    <header class=\"raml-console-resource raml-console-resource-root raml-console-clearfix\">\n" +
    "      <div class=\"raml-console-resource-path-container\">\n" +
    "        <h2 class=\"raml-console-resource-section-title raml-console-documentation-heading\" ng-click=\"vm.isCollapsed = !vm.isCollapsed\">\n" +
    "          <button class=\"raml-console-resource-root-toggle\" ng-class=\"{'raml-console-is-active': vm.isCollapsed}\"></button>\n" +
    "          <span class=\"raml-console-resource-path-active\">Types</span>\n" +
    "        </h2>\n" +
    "      </div>\n" +
    "    </header>\n" +
    "  </li>\n" +
    "  <li ng-if=\"!vm.isCollapsed\" class=\"raml-console-resource-panel raml-console-type-panel\" style=\"background: white; padding: 32px;\">\n" +
    "    <properties list=\"theTypes\" collapsible=\"true\" hide-property-details=\"true\"></types>\n" +
    "  </li>\n" +
    "</ol>\n"
  );


  $templateCache.put('directives/sidebar.tpl.html',
    "  <form name=\"form\" class=\"raml-console-sidebar\" novalidate ng-class=\"{ 'raml-console-is-collapsed': singleView }\" ng-if=\"!disableTryIt\" ng-init=\"setFormScope(this)\">\n" +
    "    <div class=\"raml-console-sidebar-flex-wrapper\">\n" +
    "      <div class=\"raml-console-sidebar-content\">\n" +
    "        <header class=\"raml-console-sidebar-row raml-console-sidebar-header\">\n" +
    "          <h3 class=\"raml-console-sidebar-head\">\n" +
    "            Try it\n" +
    "            <a ng-if=\"!singleView\" class=\"raml-console-sidebar-fullscreen-toggle\" ng-click=\"collapseSidebar($event)\"><div class=\"raml-console-close-sidebar\">&times;</div></a>\n" +
    "            <a ng-if=\"!singleView\" class=\"raml-console-sidebar-collapse-toggle\" ng-click=\"closeSidebar($event)\"><div class=\"raml-console-close-sidebar\">&times;</div></a>\n" +
    "\n" +
    "            <a ng-if=\"singleView\" class=\"raml-console-sidebar-collapse-toggle\" ng-click=\"toggleSidebar($event)\"><div class=\"raml-console-close-sidebar\">&times;</div></a>\n" +
    "\n" +
    "            <a ng-if=\"!singleView\" class=\"raml-console-sidebar-resize-toggle raml-console-sidebar-resize\" ng-click=\"toggleSidebar($event)\">\n" +
    "              <svg x=\"0px\" y=\"0px\" viewBox=\"0 0 850 1000\" class=\"raml-console-full-resize\" fill=\"#808080\" xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\"><path d=\"M421.29 589.312q0 7.254 -5.58 12.834l-185.256 185.256 80.352 80.352q10.602 10.602 10.602 25.11t-10.602 25.11 -25.11 10.602h-249.984q-14.508 0 -25.11 -10.602t-10.602 -25.11v-249.984q0 -14.508 10.602 -25.11t25.11 -10.602 25.11 10.602l80.352 80.352 185.256 -185.256q5.58 -5.58 12.834 -5.58t12.834 5.58l63.612 63.612q5.58 5.58 5.58 12.834zm435.798 -482.112v249.984q0 14.508 -10.602 25.11t-25.11 10.602 -25.11 -10.602l-80.352 -80.352 -185.256 185.256q-5.58 5.58 -12.834 5.58t-12.834 -5.58l-63.612 -63.612q-5.58 -5.58 -5.58 -12.834t5.58 -12.834l185.256 -185.256 -80.352 -80.352q-10.602 -10.602 -10.602 -25.11t10.602 -25.11 25.11 -10.602h249.984q14.508 0 25.11 10.602t10.602 25.11z\"/></svg>\n" +
    "\n" +
    "              <svg x=\"0px\" y=\"0px\" viewBox=\"0 0 850 1000\" class=\"raml-console-small-resize\" fill=\"#808080\" xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\"><path d=\"M428.544 535.744v249.984q0 14.508 -10.602 25.11t-25.11 10.602 -25.11 -10.602l-80.352 -80.352 -185.256 185.256q-5.58 5.58 -12.834 5.58t-12.834 -5.58l-63.612 -63.612q-5.58 -5.58 -5.58 -12.834t5.58 -12.834l185.256 -185.256 -80.352 -80.352q-10.602 -10.602 -10.602 -25.11t10.602 -25.11 25.11 -10.602h249.984q14.508 0 25.11 10.602t10.602 25.11zm421.29 -374.976q0 7.254 -5.58 12.834l-185.256 185.256 80.352 80.352q10.602 10.602 10.602 25.11t-10.602 25.11 -25.11 10.602h-249.984q-14.508 0 -25.11 -10.602t-10.602 -25.11v-249.984q0 -14.508 10.602 -25.11t25.11 -10.602 25.11 10.602l80.352 80.352 185.256 -185.256q5.58 -5.58 12.834 -5.58t12.834 5.58l63.612 63.612q5.58 5.58 5.58 12.834z\"/></svg>\n" +
    "            </a>\n" +
    "          </h3>\n" +
    "        </header>\n" +
    "\n" +
    "        <div class=\"raml-console-sidebar-content-wrapper\">\n" +
    "          <section ng-if=\"protocols.length > 1\">\n" +
    "            <header class=\"raml-console-sidebar-row raml-console-sidebar-subheader raml-console-sidebar-subheader-top\">\n" +
    "              <h4 class=\"raml-console-sidebar-subhead\">Protocols</h4>\n" +
    "            </header>\n" +
    "            <div class=\"raml-console-sidebar-row raml-console-sidebar-securty\">\n" +
    "              <select ng-change=\"protocolChanged(currentProtocol)\" class=\"raml-console-sidebar-input\" ng-model=\"currentProtocol\" style=\"margin-bottom: 0;\">\n" +
    "               <option ng-repeat=\"protocol in protocols\" value=\"{{protocol}}\">{{protocol}}</option>\n" +
    "              </select>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <section>\n" +
    "            <header class=\"raml-console-sidebar-row raml-console-sidebar-subheader\" ng-class=\"{'raml-console-sidebar-subheader-top':raml.protocols.length == 1}\">\n" +
    "              <h4 class=\"raml-console-sidebar-subhead\">Authentication</h4>\n" +
    "            </header>\n" +
    "            <div class=\"raml-console-sidebar-row raml-console-sidebar-securty\">\n" +
    "              <div class=\"raml-console-toggle-group raml-console-sidebar-toggle-group\">\n" +
    "                <label class=\"raml-console-sidebar-label\">Security Scheme</label>\n" +
    "                <span class=\"raml-console-resource-param-instructional\">Custom Security Schemes are not supported in Try It</span>\n" +
    "                <select ng-change=\"securitySchemeChanged(currentScheme)\" class=\"raml-console-sidebar-input\" ng-model=\"currentScheme\" style=\"margin-bottom: 0;\">\n" +
    "                 <option ng-repeat=\"(key, scheme) in securitySchemes\" value=\"{{scheme.id}}\">{{scheme.name}}</option>\n" +
    "                </select>\n" +
    "              </div>\n" +
    "            </div>\n" +
    "\n" +
    "            <div ng-switch=\"currentSchemeType\">\n" +
    "              <basic-auth ng-switch-when=\"Basic Authentication\" credentials='credentials'></basic-auth>\n" +
    "              <oauth1 ng-switch-when=\"OAuth 1.0\" credentials='credentials'></oauth1>\n" +
    "              <oauth2 ng-switch-when=\"OAuth 2.0\" credentials='credentials'></oauth2>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <named-parameters ng-if=\"resource.uriParametersForDocumentation\" src=\"resource.uriParametersForDocumentation\" context=\"context\" type=\"uriParameters\" title=\"URI Parameters\" show-base-url></named-parameters>\n" +
    "\n" +
    "          <named-parameters src=\"methodInfo.headers.plain\" context=\"context\" type=\"headers\" title=\"Headers\" enable-custom-parameters></named-parameters>\n" +
    "\n" +
    "          <named-parameters src=\"methodInfo.queryParameters\" context=\"context\" type=\"queryParameters\" title=\"Query Parameters\" enable-custom-parameters></named-parameters>\n" +
    "\n" +
    "          <section ng-if=\"methodInfo.queryString\">\n" +
    "            <header class=\"raml-console-sidebar-row raml-console-sidebar-subheader\">\n" +
    "              <h4 class=\"raml-console-sidebar-subhead\">Query String</h4>\n" +
    "            </header>\n" +
    "\n" +
    "            <div class=\"raml-console-sidebar-row\" style=\"padding-bottom: 0;\">\n" +
    "              <div\n" +
    "                class=\"raml-console-resource-param-instructional\"\n" +
    "                ng-show=\"queryStringHasError\"\n" +
    "                style=\"color: red;\">\n" +
    "                  Query String format is invalid, JSON string expected\n" +
    "              </div>\n" +
    "              <div\n" +
    "                class=\"raml-console-codemirror-body-editor\"\n" +
    "                ui-codemirror=\"{ lineNumbers: true, tabSize: 2, theme : 'raml-console', mode: context.bodyContent.selected }\"\n" +
    "                ng-model=\"context.queryString\">\n" +
    "              </div>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <section id=\"sidebar-body\" ng-if=\"methodInfo.body\">\n" +
    "            <header class=\"raml-console-sidebar-row raml-console-sidebar-subheader\">\n" +
    "              <h4 class=\"raml-console-sidebar-subhead\">Body</h4>\n" +
    "            </header>\n" +
    "\n" +
    "            <div class=\"raml-console-sidebar-row\" style=\"padding-bottom: 0;\">\n" +
    "              <select ng-change=\"requestBodySelectionChange(context.bodyContent.selected)\" class=\"raml-console-sidebar-input\" ng-model=\"context.bodyContent.selected\" style=\"margin-bottom: 0;\">\n" +
    "               <option ng-repeat=\"(key, scheme) in methodInfo.body\" value=\"{{key}}\">{{key}}</option>\n" +
    "              </select>\n" +
    "            </div>\n" +
    "\n" +
    "            <div class=\"raml-console-sidebar-row\" ng-switch=\"context.bodyContent.isForm(context.bodyContent.selected)\">\n" +
    "              <div ng-switch-when=\"false\">\n" +
    "                <div class=\"raml-console-codemirror-body-editor\" ui-codemirror=\"{ lineNumbers: true, tabSize: 2, theme : 'raml-console', mode: context.bodyContent.selected }\" ng-model=\"context.bodyContent.definitions[context.bodyContent.selected].value\"></div>\n" +
    "                <div class=\"raml-console-sidebar-prefill raml-console-sidebar-row\" align=\"right\" ng-if=\"context.bodyContent.definitions[context.bodyContent.selected].hasExample()\">\n" +
    "                  <button class=\"raml-console-sidebar-action-prefill\" ng-click=\"prefillBody(context.bodyContent.selected)\">Prefill with example</button>\n" +
    "                </div>\n" +
    "              </div>\n" +
    "\n" +
    "\n" +
    "              <div ng-switch-when=\"true\">\n" +
    "                <p class=\"raml-console-sidebar-input-container\" ng-repeat=\"param in context.bodyContent.definitions[context.bodyContent.selected].plain\">\n" +
    "                  <span class=\"raml-console-sidebar-input-tooltip-container\" ng-if=\"param.definitions[0].description\">\n" +
    "                    <button tabindex=\"-1\" class=\"raml-console-sidebar-input-tooltip\"><span class=\"raml-console-visuallyhidden\">Show documentation</span></button>\n" +
    "                    <span class=\"raml-console-sidebar-tooltip-flyout\">\n" +
    "                      <span markdown=\"param.definitions[0].description\" class=\"raml-console-marked-content\"></span>\n" +
    "                    </span>\n" +
    "                  </span>\n" +
    "\n" +
    "                  <raml-field context=\"context\" type=\"type\" param=\"param.definitions[0]\" model=\"context.bodyContent.definitions[context.bodyContent.selected].values[param.definitions[0].id]\"></raml-field>\n" +
    "                </p>\n" +
    "              </div>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <section>\n" +
    "            <div class=\"raml-console-sidebar-row\">\n" +
    "              <span\n" +
    "                class=\"raml-console-resource-param-instructional\"\n" +
    "                ng-show=\"customStrategyError\"\n" +
    "                style=\"color: red;\">\n" +
    "                  Custom Security Schemes are not supported in Try It\n" +
    "              </span>\n" +
    "              <div class=\"raml-console-sidebar-action-group\">\n" +
    "                <button ng-hide=\"showSpinner\" type=\"submit\" class=\"raml-console-sidebar-action raml-console-sidebar-action-{{methodInfo.method}}\" ng-click=\"tryIt($event)\" ng-class=\"{'raml-console-sidebar-action-force':context.forceRequest}\"><span ng-if=\"context.forceRequest\">Force</span> {{methodInfo.method.toUpperCase()}}\n" +
    "                </button>\n" +
    "                <button ng-if=\"showSpinner\" type=\"submit\" class=\"raml-console-sidebar-action raml-console-sidebar-action-{{methodInfo.method}} raml-console-sidebar-action-cancel-request\" ng-click=\"cancelRequest()\">Cancel <div class=\"raml-console-spinner-request\" ng-if=\"showSpinner\">Loading ...</div></button>\n" +
    "                <button class=\"raml-console-sidebar-action raml-console-sidebar-action-clear\" ng-click=\"clearFields()\">Clear</button>\n" +
    "                <button class=\"raml-console-sidebar-action raml-console-sidebar-action-reset\" ng-click=\"resetFields()\">Reset</button>\n" +
    "              </div>\n" +
    "            </div>\n" +
    "          </section>\n" +
    "\n" +
    "          <div ng-if=\"responseDetails\">\n" +
    "            <section id=\"request_{{generateId(resource.pathSegments)}}\" class=\"raml-console-side-bar-try-it-description\">\n" +
    "              <header class=\"raml-console-sidebar-row raml-console-sidebar-header\">\n" +
    "                <h3 class=\"raml-console-sidebar-head raml-console-sidebar-head-expand\">\n" +
    "                  <button ng-class=\"{'raml-console-is-open':showRequestMetadata, 'raml-console-is-collapsed':!showRequestMetadata}\" class=\"raml-console-sidebar-expand-btn\" ng-click=\"toggleRequestMetadata()\">\n" +
    "                    Request\n" +
    "                  </button>\n" +
    "                </h3>\n" +
    "              </header>\n" +
    "              <div class=\"raml-console-sidebar-request-metadata\" ng-class=\"{'raml-console-is-active':showRequestMetadata}\">\n" +
    "\n" +
    "                <div class=\"raml-console-sidebar-row\">\n" +
    "                  <div ng-if=\"requestOptions.url\">\n" +
    "                    <h3 class=\"raml-console-sidebar-response-head raml-console-sidebar-response-head-pre\">Request URL</h3>\n" +
    "                    <div class=\"raml-console-sidebar-response-item\">\n" +
    "                      <p class=\"raml-console-sidebar-response-metadata raml-console-sidebar-request-url\">{{requestOptions.url}}</p>\n" +
    "                    </div>\n" +
    "                  </div>\n" +
    "\n" +
    "                  <div ng-if=\"requestOptions.headers\">\n" +
    "                    <h3 class=\"raml-console-sidebar-response-head\">Headers</h3>\n" +
    "                    <div class=\"raml-console-sidebar-response-item\">\n" +
    "                      <p class=\"raml-console-sidebar-response-metadata\" ng-repeat=\"(key, value) in requestOptions.headers\">\n" +
    "                        <b>{{key}}:</b> <br>{{getHeaderValue(value)}}\n" +
    "                      </p>\n" +
    "                    </div>\n" +
    "                  </div>\n" +
    "\n" +
    "                  <div ng-if=\"requestOptions.data\">\n" +
    "                    <h3 class=\"raml-console-sidebar-response-head raml-console-sidebar-response-head-pre\">Body</h3>\n" +
    "                    <div ng-switch=\"context.bodyContent.isForm(context.bodyContent.selected)\">\n" +
    "                      <div ng-switch-when=\"false\" class=\"raml-console-sidebar-pre raml-console-sidebar-request-body\">\n" +
    "                        <div ui-codemirror=\"{ readOnly: 'nocursor', tabSize: 2, lineNumbers: true, theme : 'raml-console', mode: context.bodyContent.selected }\" ng-model=\"requestOptions.data\"></div>\n" +
    "                      </div>\n" +
    "                      <div ng-switch-when=\"true\" class=\"raml-console-sidebar-response-item\">\n" +
    "                        <p class=\"raml-console-sidebar-response-metadata\" ng-repeat=\"(key, value) in context.bodyContent.definitions[context.bodyContent.selected].values\">\n" +
    "                          <b>{{key}}:</b> <br>{{value}}\n" +
    "                        </p>\n" +
    "                      </div>\n" +
    "                    </div>\n" +
    "                  </div>\n" +
    "                </div>\n" +
    "              </div>\n" +
    "            </section>\n" +
    "\n" +
    "            <section class=\"raml-console-side-bar-try-it-description\">\n" +
    "              <header class=\"raml-console-sidebar-row raml-console-sidebar-header\">\n" +
    "                <h3 class=\"raml-console-sidebar-head\">\n" +
    "                  <button ng-class=\"{'raml-console-is-open':showResponseMetadata, 'raml-console-is-collapsed':!showResponseMetadata}\" class=\"raml-console-sidebar-expand-btn\" ng-click=\"toggleResponseMetadata()\">\n" +
    "                    Response\n" +
    "                  </button>\n" +
    "                </h3>\n" +
    "              </header>\n" +
    "\n" +
    "              <div class=\"raml-console-sidebar-row raml-console-sidebar-response\" ng-class=\"{'raml-console-is-active':showResponseMetadata}\">\n" +
    "                <h3 class=\"raml-console-sidebar-response-head\">Status</h3>\n" +
    "                <p class=\"raml-console-sidebar-response-item\">{{response.status}}</p>\n" +
    "\n" +
    "                <h3 class=\"raml-console-sidebar-response-head\" ng-if=\"response.headers\">Headers</h3>\n" +
    "                <div class=\"raml-console-sidebar-response-item\">\n" +
    "                  <p class=\"raml-console-sidebar-response-metadata\" ng-repeat=\"(key, value) in response.headers\">\n" +
    "                    <b>{{key}}:</b> <br>{{value}}\n" +
    "                  </p>\n" +
    "                </div>\n" +
    "                <div ng-if=\"response.body\">\n" +
    "                  <h3 class=\"raml-console-sidebar-response-head raml-console-sidebar-response-head-pre\">Body</h3>\n" +
    "                  <div class=\"raml-console-sidebar-pre\">\n" +
    "                    <div ui-codemirror=\"{ readOnly: true, tabSize: 2, lineNumbers: true, theme : 'raml-console', mode: response.contentType }\" ng-model=\"response.body\" ng-style=\"editorStyle\">\n" +
    "                    </div>\n" +
    "                  </div>\n" +
    "                </div>\n" +
    "              </div>\n" +
    "            </section>\n" +
    "          </div>\n" +
    "      </div>\n" +
    "    </div>\n" +
    "  </div>\n" +
    "</form>\n"
  );


  $templateCache.put('directives/spinner.tpl.html',
    "<img src=\"img/spinner.gif\">\n"
  );


  $templateCache.put('directives/theme-switcher.tpl.html',
    "<div class=\"raml-console-meta-button-container\">\n" +
    "  <a class=\"raml-console-meta-button\">Switch Theme</a>\n" +
    "</div>\n"
  );


  $templateCache.put('directives/type-properties.tpl.html',
    "<div>\n" +
    "  <properties list=\"properties\" hide-property-details=\"true\" show-examples=\"true\"></properties>\n" +
    "<div>\n"
  );


  $templateCache.put('directives/type.tpl.html',
    "<span ng-repeat=\"type in typeInfo.parts\">\n" +
    "  <span ng-if=\"$first && typeInfo.isArray\">(</span>\n" +
    "  <span ng-if=\"!$first && typeInfo.type === 'union'\"> | </span>\n" +
    "  <a href=\"\" ng-click=\"selectType($event, cleanupTypeName(type))\" ng-if=\"showTypeLink(type)\">{{cleanupTypeName(type)}}</a>\n" +
    "  <span ng-if=\"!showTypeLink(type)\">{{type}}</span>\n" +
    "  <span ng-if=\"typeInfo.type === 'array'\">[]</span>\n" +
    "  <span ng-if=\"$last && typeInfo.isArray\">)[]</span>\n" +
    "</span>\n" +
    "<div ng-if=\"selectedType\" class=\"raml-console-type-info-popover\">\n" +
    "  <h3>\n" +
    "    <span>{{selectedType.displayName}}</span>\n" +
    "    <div class=\"raml-console-subtitle\">\n" +
    "      <span ng-repeat-start=\"superType in getSupertTypes(selectedType)\">{{superType}}</span>\n" +
    "      <span ng-if=\"!$last\" ng-repeat-end>, </span>\n" +
    "    </div>\n" +
    "  </h3>\n" +
    "  <properties\n" +
    "    ng-click=\"$event.preventDefault()\"\n" +
    "    list=\"selectedType.properties\"\n" +
    "    ng-if=\"selectedType.properties\"\n" +
    "    is-nested-property=\"true\"\n" +
    "    hide-type-links=\"true\">\n" +
    "  </properties>\n" +
    "</div>\n"
  );


  $templateCache.put('security/basic_auth.tpl.html',
    "<div class=\"raml-console-sidebar-row\">\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"username\" class=\"raml-console-sidebar-label\">Username <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"text\" name=\"username\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.username\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"password\" class=\"raml-console-sidebar-label\">Password <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"password\" name=\"password\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.password\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "</div>\n"
  );


  $templateCache.put('security/oauth1.tpl.html',
    "<div class=\"raml-console-sidebar-row\">\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"consumerKey\" class=\"raml-console-sidebar-label\">Consumer Key <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"text\" name=\"consumerKey\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.consumerKey\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"consumerSecret\" class=\"raml-console-sidebar-label\">Consumer Secret <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"password\" name=\"consumerSecret\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.consumerSecret\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "</div>\n"
  );


  $templateCache.put('security/oauth2.tpl.html',
    "<div class=\"raml-console-sidebar-row\">\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"clientId\" class=\"raml-console-sidebar-label\">Authorization Grant</label>\n" +
    "    <select class=\"raml-console-sidebar-input\" ng-model=\"credentials.grant\">\n" +
    "     <option ng-repeat=\"grant in grants\" value=\"{{grant.value}}\" ng-selected=\"grant.value=='token'\">{{grant.label}}</option>\n" +
    "    </select>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"clientId\" class=\"raml-console-sidebar-label\">Client ID <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"text\" name=\"clientId\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.clientId\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\" ng-if=\"!isImplicitEnabled()\">\n" +
    "    <label for=\"clientSecret\" class=\"raml-console-sidebar-label\">Client Secret <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"password\" name=\"clientSecret\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.clientSecret\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\" ng-if=\"ownerOptionsEnabled()\">\n" +
    "    <label for=\"username\" class=\"raml-console-sidebar-label\">Username <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"text\" name=\"username\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.username\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\" ng-if=\"ownerOptionsEnabled()\">\n" +
    "    <label for=\"password\" class=\"raml-console-sidebar-label\">Password <span class=\"raml-console-side-bar-required-field\">*</span></label>\n" +
    "    <input required=\"true\" type=\"password\" name=\"password\" class=\"raml-console-sidebar-input raml-console-sidebar-security-field\" ng-model=\"credentials.password\" ng-change=\"onChange()\"/>\n" +
    "    <span class=\"raml-console-field-validation-error\"></span>\n" +
    "  </p>\n" +
    "\n" +
    "  <p class=\"raml-console-sidebar-input-container\">\n" +
    "    <label for=\"password\" class=\"raml-console-sidebar-label\">Scopes</label>\n" +
    "    <ol class=\"raml-console-sidebar-oauth-scopes\">\n" +
    "      <li ng-repeat=\"scope in scopes\">\n" +
    "        <input type=\"checkbox\" ng-model=\"credentials.scopes[scope]\"> {{scope}}\n" +
    "      </li>\n" +
    "    </ol>\n" +
    "  </p>\n" +
    "</div>\n"
  );

}]);
