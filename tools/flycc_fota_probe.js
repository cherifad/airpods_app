// FlyCC FOTA observation hooks (Frida 16.x, Java bridge bundled).
//
// Purpose:
//   - log the DFU information the app reads (versions, company, module);
//   - optionally force the version used for the update lookup;
//   - block startDataTransfer so the app performs the HTTP download but never
//     writes flash ("download-only" capture).
//
// Usage (rooted phone or emulator):
//   frida -U -f com.airoha.twshelper -l tools/flycc_fota_probe.js
//
// Set FORCE_VERSION to a key present in the downloaded `fota` file when you
// want the app to offer a package even though the earbuds are up to date.

var FORCE_VERSION = ""; // e.g. "1.2.3"; leave empty to only log
var BLOCK_TRANSFER = true;

function log(line) {
  console.log("[fota-probe] " + line);
}

Java.perform(function () {
  var FotaInfo = null;
  try {
    FotaInfo = Java.use("com.airoha.sdk.api.ota.FotaInfo");
    [
      "getFwVersion",
      "getPartnerFwVersion",
      "getCompanyName",
      "getPartnerCompanyName",
      "getModuleName",
      "getPartnerModuleName",
    ].forEach(function (name) {
      if (!FotaInfo[name]) return;
      var original = FotaInfo[name];
      FotaInfo[name].implementation = function () {
        var value = original.call(this);
        log(name + " = " + value);
        if (FORCE_VERSION && (name === "getFwVersion" || name === "getPartnerFwVersion")) {
          log("overriding " + name + " with " + FORCE_VERSION);
          return FORCE_VERSION;
        }
        return value;
      };
    });
  } catch (error) {
    log("FotaInfo hook failed: " + error);
  }

  [
    "com.airoha.sdk.AB1568FotaControl",
    "com.airoha.sdk.AB1562FotaControl",
    "com.airoha.sdk.AB155xFotaControl",
  ].forEach(function (className) {
    try {
      var control = Java.use(className);
      control.startDataTransfer.overloads.forEach(function (overload) {
        var original = overload;
        overload.implementation = function () {
          var args = Array.prototype.slice.call(arguments);
          log(className + ".startDataTransfer(" + args.join(", ") + ")");
          if (BLOCK_TRANSFER) {
            log("transfer blocked: download-only capture");
            return true;
          }
          return original.call(this, ...args);
        };
      });
      log("hooked " + className);
    } catch (error) {
      log(className + " not present: " + error);
    }
  });
});
