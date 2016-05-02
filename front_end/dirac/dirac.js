(function() {

var featureFlags = {};

var knownFeatureFlags = [
  "enable-repl",
  "enable-parinfer",
  "enable-friendly-locals",
  "enable-clustered-locals",
  "inline-custom-formatters"];

function featureToIndex(feature) {
  return knownFeatureFlags.indexOf(feature);
}

function hasFeature(feature) {
  var flag = featureFlags[feature];
  if (flag !== undefined) {
    return flag;
  }
  var featureIndex = knownFeatureFlags.indexOf(feature);
  if (featureIndex === -1) {
    return true;
  }
  var activeFlags = Runtime.queryParam("dirac_flags") || "";
  var result = activeFlags[featureIndex] !== '0';
  featureFlags[feature] = result;
  return result;
}

// taken from https://github.com/joliss/js-string-escape/blob/master/index.js
function stringEscape(string) {
  return ('' + string).replace(/["'\\\n\r\u2028\u2029]/g, function (character) {
    // Escape all characters not included in SingleStringCharacters and
    // DoubleStringCharacters on
    // http://www.ecma-international.org/ecma-262/5.1/#sec-7.8.4
    switch (character) {
      case '"':
      case "'":
      case '\\':
        return '\\' + character
      // Four possible LineTerminator characters need to be escaped:
      case '\n':
        return '\\n'
      case '\r':
        return '\\r'
      case '\u2028':
        return '\\u2028'
      case '\u2029':
        return '\\u2029'
    }
  })
}

function codeAsString(code) {
  return "'" + stringEscape(code) + "'";
}

function evalInContext(context, code, callback) {
  if (!context) {
    console.warn("Requested evalInContext with null context:", code);
    return;
  }
  var resultCallback = function(result, wasThrown, value, exceptionDetails) {
    if (dirac._DEBUG_EVAL) {
      console.log("evalInContext/resultCallback: result", result, "wasThrown", wasThrown, "value", value, "exceptionDetails", exceptionDetails);
    }
    if (callback) {
      callback(value, wasThrown, exceptionDetails);
    }
  };
  try {
    if (dirac._DEBUG_EVAL) {
      console.log("evalInContext", context, code);
    }
    context.evaluate(code, "console", true, true, true, false, false, resultCallback);
  } catch (e) {
    console.error("failed js evaluation in context:", context, "code", code);
  }
}

function lookupCurrentContext() {
  return WebInspector.context.flavor(WebInspector.ExecutionContext);
}

function hasCurrentContext() {
  return lookupCurrentContext()?true:false;
}

function evalInCurrentContext(code, callback) {
  if (dirac._DEBUG_EVAL) {
    console.log("evalInCurrentContext called:", code, callback);
  }
  evalInContext(lookupCurrentContext(), code, callback);
}

function lookupDefaultContext() {
  if (dirac._DEBUG_EVAL) {
    console.log("lookupDefaultContext called");
  }
  if (!WebInspector.targetManager) {
    if (dirac._DEBUG_EVAL) {
      console.log("  !WebInspector.targetManager => bail out");
    }
    return null;
  }
  var target = WebInspector.targetManager.mainTarget();
  if (!target) {
    if (dirac._DEBUG_EVAL) {
      console.log("  !target => bail out");
    }
    return null;
  }
  var executionContexts = target.runtimeModel.executionContexts();
  if (dirac._DEBUG_EVAL) {
    console.log("  execution contexts:", executionContexts);
  }
  for (var i = 0; i < executionContexts.length; ++i) {
    var executionContext = executionContexts[i];
    if (executionContext.isDefault || executionContext.isMainWorldContext) {  // isMainWorldContext for backward compatibility
      if (dirac._DEBUG_EVAL) {
        console.log("  execution context #"+i+" isDefault:", executionContext);
      }
      return executionContext;
    }
  }
  if (executionContexts.length>0) {
    if (dirac._DEBUG_EVAL) {
      console.log("  lookupDefaultContext failed to find valid context => return the first one");
    }
    return executionContexts[0];
  }
  if (dirac._DEBUG_EVAL) {
    console.log("  lookupDefaultContext failed to find valid context => no context avail");
  }
  return null;
}

function hasDefaultContext() {
  return lookupDefaultContext()?true:false;
}

function evalInDefaultContext(code, callback) {
  if (dirac._DEBUG_EVAL) {
    console.log("evalInDefaultContext called:", code, callback);
  }
  evalInContext(lookupDefaultContext(), code, callback);
}

// don't forget to update externs.js too
window.dirac = {
  _DEBUG_EVAL: false,
  hasFeature: hasFeature,
  hasREPL: hasFeature("enable-repl"),
  hasParinfer: hasFeature("enable-parinfer"),
  hasFriendlyLocals: hasFeature("enable-friendly-locals"),
  hasClusteredLocals: hasFeature("enable-clustered-locals"),
  hasInlineCFs: hasFeature("inline-custom-formatters"),
  codeAsString: codeAsString,
  stringEscape: stringEscape,
  evalInCurrentContext: evalInCurrentContext,
  hasCurrentContext: hasCurrentContext,
  evalInDefaultContext: evalInDefaultContext,
  hasDefaultContext: hasDefaultContext
};

})();